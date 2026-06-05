package com.kyomu53n.aixodia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class OverlayService : Service() {

    private lateinit var floatingWindow: FloatingControlWindow
    private lateinit var executorAgent: ExecutorAgent
    private var isAiRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        floatingWindow = FloatingControlWindow(this)
        executorAgent = ExecutorAgent(this)

        setupVolumeUpCallback()
        setupFloatingWindowCallbacks()

        // Start ScreenCaptureService if projection was set up by MainActivity
        if (ScreenCaptureService.mediaProjectionReady()) {
            startService(Intent(this, ScreenCaptureService::class.java))
        }

        floatingWindow.show(FloatingControlWindow.Mode.INTERACTIVE)
        floatingWindow.appendLog("AIxodia ready. Enter a command and press Start.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle capture result if passed from MainActivity
        intent?.let {
            val resultCode = it.getIntExtra("RESULT_CODE", -1)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra("DATA")
            }
            if (resultCode != -1 && data != null) {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val metrics = resources.displayMetrics
                ScreenCaptureService.setupProjection(
                    resultCode = resultCode,
                    data = data,
                    manager = manager,
                    width = metrics.widthPixels,
                    height = metrics.heightPixels,
                    density = metrics.densityDpi
                )
                startService(Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("DATA", data)
                })
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAi()
        floatingWindow.dismiss()
        ScreenControllerService.onVolumeUpPressed = null
        stopService(Intent(this, ScreenCaptureService::class.java))
        super.onDestroy()
    }

    private fun setupVolumeUpCallback() {
        ScreenControllerService.onVolumeUpPressed = {
            if (isAiRunning) {
                floatingWindow.appendLog("Volume Up pressed - stopping AI...")
                stopAi()
            }
        }
    }

    private fun setupFloatingWindowCallbacks() {
        floatingWindow.onStartClick {
            if (!isAiRunning) {
                startAi()
            } else {
                stopAi()
            }
        }

        floatingWindow.onSettingsClick {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    private fun startAi() {
        val command = floatingWindow.getCommandText()
        if (command.isBlank()) {
            floatingWindow.appendLog("Error: Please enter a command.")
            return
        }

        isAiRunning = true
        floatingWindow.setStartButtonText("Stop (Vol↑)")
        floatingWindow.switchMode(FloatingControlWindow.Mode.PASSTHROUGH)
        floatingWindow.setLog("AI running...")
        floatingWindow.setStatusText("● Running")
        floatingWindow.setStatusColor(ContextCompat.getColor(this, R.color.statusRunning))

        // Ensure ScreenCaptureService is running
        if (!ScreenCaptureService.isServiceRunning) {
            startService(Intent(this, ScreenCaptureService::class.java))
        }

        ScreenCaptureService.startOverlay(this)

        executorAgent.startExecutionLoop(command) { log ->
            floatingWindow.appendLog(log)
        }
    }

    private fun stopAi() {
        isAiRunning = false
        executorAgent.stopExecutionLoop()
        ScreenCaptureService.stopOverlay()

        floatingWindow.setStartButtonText("Start")
        floatingWindow.switchMode(FloatingControlWindow.Mode.INTERACTIVE)
        floatingWindow.appendLog("AI stopped.")
        floatingWindow.setStatusText("● Idle")
        floatingWindow.setStatusColor(ContextCompat.getColor(this, R.color.statusIdle))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AIxodia overlay service notification"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "aixodia_overlay"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_AI = "com.kyomu53n.aixodia.STOP_AI"
    }
}
