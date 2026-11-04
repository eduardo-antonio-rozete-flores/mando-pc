package com.mandopc.transport

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import com.mandopc.contracts.DisconnectReason
import com.mandopc.contracts.FRAME_SIZE
import com.mandopc.contracts.Transport
import com.mandopc.contracts.TransportException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Transporte AOA sobre el descriptor de accesorio que entrega Android.
 *
 * El host (Fedora) hace el handshake AOA y arranca el modo accesorio.
 * Android entrega un [UsbAccessory]; aqui se abren los flujos de ese
 * descriptor, que corresponden a los endpoints bulk de AOA.
 *
 * Un hilo lector dedicado reensambla tramas de 64 bytes en una cola
 * circular. [read] nunca bloquea y [write] escribe la trama completa.
 */
class AoaTransport(
    context: Context,
    capacityFrames: Int = FrameRing.DEFAULT_CAPACITY,
) : Transport {

    companion object {
        const val ACTION_USB_PERMISSION: String = "com.mandopc.transport.USB_PERMISSION"
        private const val READ_BUFFER_SIZE = 16 * 1024
        private const val READER_NAME = "aoa-reader"
    }

    private val appContext: Context = context.applicationContext
    private val usbManager: UsbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val ring = FrameRing(capacityFrames)
    private val assembler = FrameAssembler(ring)
    private val lock = Any()

    @Volatile
    private var started = false

    @Volatile
    private var opened = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var permissionRequested = false

    private var accessory: UsbAccessory? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var readerThread: Thread? = null
    private var reason: DisconnectReason? = null

    val isOpen: Boolean
        get() = opened

    val error: String?
        get() = lastError

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    extractAccessory(intent)?.let(::onAttached)
                }

                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    val detached = extractAccessory(intent)
                    if (detached == null || detached == accessory) {
                        fail(DisconnectReason.CableRemoved)
                    }
                }

                ACTION_USB_PERMISSION -> {
                    val granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    permissionRequested = false
                    val grantedAccessory = extractAccessory(intent) ?: findAccessory()
                    if (granted && grantedAccessory != null) {
                        tryOpen(grantedAccessory)
                    }
                }
            }
        }
    }

    /**
     * Registra el receptor de conexion/desconexion y abre el accesorio
     * si ya esta conectado y hay permiso. Es idempotente.
     */
    fun start() {
        ensureStarted()
        val current = findAccessory() ?: return
        if (usbManager.hasPermission(current)) {
            tryOpen(current)
        } else {
            requestPermission(current)
        }
    }

    /**
     * Deja de escuchar el cable. Cierra el canal si estaba abierto.
     */
    fun stop() {
        close()
        synchronized(lock) {
            if (started) {
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                }
                started = false
            }
        }
        permissionRequested = false
    }

    override fun open() {
        synchronized(lock) {
            if (opened) return
        }
        ensureStarted()
        val current = findAccessory() ?: throw TransportException.Io("no AOA accessory attached")
        if (!usbManager.hasPermission(current)) {
            requestPermission(current)
            throw TransportException.Io("USB permission pending for AOA accessory")
        }
        try {
            synchronized(lock) {
                if (!opened) openLocked(current)
            }
        } catch (e: SecurityException) {
            lastError = e.message
            throw TransportException.Io("cannot open AOA accessory: ${e.message}")
        }
    }

    override fun close() {
        synchronized(lock) {
            if (opened) reason = DisconnectReason.Normal
            opened = false
            closeStreamsLocked()
        }
        ring.clear()
    }

    override fun read(out: ByteArray): Boolean {
        require(out.size >= FRAME_SIZE) { "out must hold at least $FRAME_SIZE bytes" }
        if (ring.poll(out)) return true
        val failure = synchronized(lock) {
            if (opened) null else closedExceptionLocked()
        }
        if (failure != null) throw failure
        return false
    }

    override fun write(frame: ByteArray) {
        require(frame.size == FRAME_SIZE) { "frame must be exactly $FRAME_SIZE bytes" }
        val stream = synchronized(lock) {
            if (!opened) throw closedExceptionLocked()
            output ?: throw TransportException.NotOpen()
        }
        try {
            synchronized(stream) {
                stream.write(frame)
            }
        } catch (e: IOException) {
            fail(DisconnectReason.Error)
            throw TransportException.Io("AOA write failed: ${e.message}")
        }
    }

    override fun onDisconnect(): DisconnectReason? = synchronized(lock) {
        val current = reason
        reason = null
        current
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun ensureStarted() {
        if (started) return
        synchronized(lock) {
            if (started) return
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            started = true
        }
    }

    private fun onAttached(attached: UsbAccessory) {
        if (!usbManager.hasPermission(attached)) {
            requestPermission(attached)
            return
        }
        tryOpen(attached)
    }

    private fun tryOpen(attached: UsbAccessory) {
        try {
            synchronized(lock) {
                if (opened) return
                openLocked(attached)
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        }
    }

    private fun openLocked(attached: UsbAccessory) {
        closeStreamsLocked()
        val pfd = usbManager.openAccessory(attached)
            ?: throw TransportException.Io("openAccessory returned null")
        val inStream: FileInputStream
        val outStream: FileOutputStream
        try {
            inStream = FileInputStream(pfd.fileDescriptor)
            outStream = FileOutputStream(pfd.fileDescriptor)
        } catch (e: IOException) {
            try {
                pfd.close()
            } catch (_: IOException) {
            }
            throw TransportException.Io("cannot open AOA streams: ${e.message}")
        }

        accessory = attached
        descriptor = pfd
        input = inStream
        output = outStream
        ring.clear()
        assembler.reset()
        reason = null
        lastError = null
        opened = true

        val thread = Thread({ readerLoop(inStream) }, READER_NAME)
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    private fun readerLoop(stream: FileInputStream) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) {
                    fail(DisconnectReason.CableRemoved)
                    return
                }
                if (read > 0) {
                    assembler.feed(buffer, read)
                }
            }
        } catch (_: IOException) {
            fail(DisconnectReason.Error)
        }
    }

    private fun fail(newReason: DisconnectReason) {
        synchronized(lock) {
            if (!opened) return
            opened = false
            reason = newReason
            closeStreamsLocked()
        }
    }

    private fun closeStreamsLocked() {
        val inStream = input
        val outStream = output
        val pfd = descriptor
        input = null
        output = null
        descriptor = null
        readerThread = null
        accessory = null

        try {
            inStream?.close()
        } catch (_: IOException) {
        }
        try {
            outStream?.close()
        } catch (_: IOException) {
        }
        try {
            pfd?.close()
        } catch (_: IOException) {
        }
    }

    private fun closedExceptionLocked(): TransportException {
        val current = reason
        return if (current != null && current != DisconnectReason.Normal) {
            TransportException.Disconnected(current)
        } else {
            TransportException.NotOpen()
        }
    }

    private fun requestPermission(attached: UsbAccessory) {
        if (permissionRequested) return
        permissionRequested = true
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(attached, pending)
    }

    @Suppress("DEPRECATION")
    private fun extractAccessory(intent: Intent): UsbAccessory? =
        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as? UsbAccessory

    private fun findAccessory(): UsbAccessory? =
        usbManager.accessoryList?.firstOrNull()
}
