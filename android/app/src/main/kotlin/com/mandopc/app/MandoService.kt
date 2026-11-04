package com.mandopc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.mandopc.contracts.FRAME_SIZE
import com.mandopc.contracts.TransportException
import com.mandopc.session.FrameSink
import com.mandopc.session.Rumble
import com.mandopc.session.SessionEngine
import com.mandopc.session.SessionPhase
import com.mandopc.transport.AoaTransport

class MandoService : Service(), FrameSink {

    private lateinit var transport: AoaTransport
    private lateinit var identityStore: PreferencesIdentityStore
    private lateinit var engine: SessionEngine

    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastStatusText: String = ""

    private var lastPublishAtUs: Long = 0L
    private var hasPublished: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        transport = AoaTransport(this)
        identityStore = PreferencesIdentityStore(this)
        val identity = identityStore.loadOrCreate()
        MandoRuntime.setRequestedSlot(identity.requestedSlot)
        engine = SessionEngine(
            uuid = identity.uuid,
            sink = this,
            model = Build.MODEL ?: "Android",
            capabilities = 0b1111,
        )
        engine.setRequestedSlot(identity.requestedSlot, 0L)
        engine.onRumble = ::vibrate
        transport.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(statusText())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.action == ACTION_SLOT) {
            val slot = intent.getIntExtra(EXTRA_SLOT, MandoRuntime.requestedSlot).coerceIn(0, 4)
            identityStore.saveRequestedSlot(slot)
            MandoRuntime.setRequestedSlot(slot)
            engine.setRequestedSlot(slot, nowUs())
            updateNotification()
        }

        acquireWakeLock()
        if (!running) {
            running = true
            worker = Thread({ runLoop() }, "mando-session").also { it.start() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        val thread = worker
        worker = null
        thread?.interrupt()
        thread?.join(500)
        runCatching { engine.disconnect() }
        transport.stop()
        releaseWakeLock()
        MandoRuntime.phase = SessionPhase.Disconnected
        MandoRuntime.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun send(frame: ByteArray) {
        try {
            transport.write(frame)
        } catch (_: TransportException) {
        }
    }

    private fun runLoop() {
        val frame = ByteArray(FRAME_SIZE)
        var wasOpen = false
        var nextWakeUs = nowUs()
        while (running) {
            val now = nowUs()
            val open = transport.isOpen
            if (open != wasOpen) {
                if (open) {
                    engine.beginHandshake(now)
                } else {
                    engine.disconnect()
                    MandoRuntime.reset()
                }
                wasOpen = open
            }
            if (open) {
                drainFrames(frame, now)
                engine.tick(now, MandoRuntime.snapshot())
            }
            publishStatus(now)
            nextWakeUs += LOOP_PERIOD_US
            val sleepUs = nextWakeUs - nowUs()
            if (sleepUs > 0L) {
                try {
                    Thread.sleep(sleepUs / 1_000L, ((sleepUs % 1_000L) * 1_000L).toInt())
                } catch (_: InterruptedException) {
                    return
                }
            } else {
                nextWakeUs = nowUs()
            }
        }
    }

    private fun drainFrames(frame: ByteArray, nowUs: Long) {
        while (running) {
            val read = try {
                transport.read(frame)
            } catch (_: TransportException) {
                return
            }
            if (!read) return
            engine.onFrame(frame, nowUs)
        }
    }

    private fun publishStatus(nowUs: Long) {
        if (hasPublished && nowUs - lastPublishAtUs < STATUS_PERIOD_US) return
        hasPublished = true
        lastPublishAtUs = nowUs
        MandoRuntime.phase = engine.phase
        MandoRuntime.assignedSlot = engine.assignedSlot
        MandoRuntime.rttMs = engine.rttMs
        MandoRuntime.hostName = engine.hostName
        MandoRuntime.lastError = engine.lastError
        val text = statusText()
        if (text != lastStatusText) {
            lastStatusText = text
            updateNotification()
        }
    }

    private fun statusText(): String = when (engine.phase) {
        SessionPhase.Streaming -> "J${engine.assignedSlot} · ${engine.rttMs} ms"
        SessionPhase.Handshaking -> "Conectando con la PC…"
        SessionPhase.Error -> "Error: ${engine.lastError ?: "desconocido"}"
        SessionPhase.Disconnected -> "Esperando cable USB…"
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sesión Mando PC",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Estado del mando y del enlace USB"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, MandoService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Detener",
            stopPending,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Mando PC")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText()))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun vibrate(rumble: Rumble) {
        val peak = maxOf(rumble.left, rumble.right)
        if (peak <= 0) return
        val amplitude = (peak * 255 / 65_535).coerceIn(1, 255)
        val duration = if (rumble.durationMs == 0) 100L else rumble.durationMs.toLong()
        val effect = VibrationEffect.createOneShot(duration, amplitude)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java) ?: return
            val vibrator = manager.defaultVibrator
            if (vibrator.hasVibrator()) vibrator.vibrate(effect)
        } else {
            val vibrator = getSystemService(Vibrator::class.java) ?: return
            if (vibrator.hasVibrator()) vibrator.vibrate(effect)
        }
    }

    private fun nowUs(): Long = System.nanoTime() / 1_000L

    companion object {
        const val ACTION_START: String = "com.mandopc.app.START"
        const val ACTION_STOP: String = "com.mandopc.app.STOP"
        const val ACTION_SLOT: String = "com.mandopc.app.SLOT"
        const val EXTRA_SLOT: String = "slot"

        private const val CHANNEL_ID = "mando_session"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_TAG = "mando:session"
        private const val WAKE_TIMEOUT_MS = 2 * 60 * 60 * 1000L
        private const val LOOP_PERIOD_US = 1_000L
        private const val STATUS_PERIOD_US = 250_000L
    }
}
