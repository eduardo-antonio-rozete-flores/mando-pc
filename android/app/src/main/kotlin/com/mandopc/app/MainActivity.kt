package com.mandopc.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.mandopc.session.SessionPhase

class MainActivity : Activity() {

    private lateinit var controllerView: XboxControllerView
    private lateinit var statusView: TextView
    private lateinit var slotView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, STATUS_PERIOD_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        controllerView = XboxControllerView(this).apply {
            setEventSink { MandoRuntime.emit(it) }
        }
        statusView = overlayText()
        slotView = overlayText().apply {
            isClickable = true
            setOnClickListener { cycleSlot() }
        }

        val root = FrameLayout(this)
        root.addView(
            controllerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(statusView, overlayParams(Gravity.BOTTOM or Gravity.START))
        root.addView(slotView, overlayParams(Gravity.BOTTOM or Gravity.END))
        setContentView(root)

        requestNotificationPermission()
        startSession()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(statusTick)
        handler.post(statusTick)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusTick)
        controllerView.cancelAll()
        controllerView.setEventSink(null)
        super.onDestroy()
    }

    private fun cycleSlot() {
        val next = when (MandoRuntime.requestedSlot) {
            0 -> 1
            1 -> 2
            2 -> 3
            3 -> 4
            else -> 0
        }
        MandoRuntime.setRequestedSlot(next)
        startSession(MandoService.ACTION_SLOT, next)
        updateStatus()
    }

    private fun startSession(action: String = MandoService.ACTION_START, slot: Int = -1) {
        val intent = Intent(this, MandoService::class.java).setAction(action)
        if (slot >= 0) intent.putExtra(MandoService.EXTRA_SLOT, slot)
        startForegroundService(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun updateStatus() {
        statusView.text = when (MandoRuntime.phase) {
            SessionPhase.Streaming -> "J${MandoRuntime.assignedSlot} · ${MandoRuntime.rttMs} ms"
            SessionPhase.Handshaking -> "Conectando…"
            SessionPhase.Error -> "Error: ${MandoRuntime.lastError ?: "?"}"
            SessionPhase.Disconnected -> "Sin PC"
        }
        slotView.text = if (MandoRuntime.requestedSlot == 0) {
            "SLOT AUTO"
        } else {
            "SLOT ${MandoRuntime.requestedSlot}"
        }
    }

    private fun overlayText(): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setBackgroundColor(0xCC101418.toInt())
    }

    private fun overlayParams(gravity: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            gravity,
        ).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val STATUS_PERIOD_MS = 500L
    }
}
