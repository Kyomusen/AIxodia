package com.kyomu53n.aixodia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var floatingWindow: FloatingControlWindow
    private lateinit var executorAgent: ExecutorAgent
    private var isAiRunning = false
    private var mediaProjection: MediaProjection? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingWindow = FloatingControlWindow(this)
        executorAgent = ExecutorAgent(this, resources.displayMetrics) { msg ->
            floatingWindow.appendLog(msg)
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        setupVolumeUpCallback()
        setupFloatingWindowCallbacks()

        floatingWindow.show(FloatingControlWindow.Mode.INTERACTIVE)
        floatingWindow.appendLog("AIxodia ready. Enter a command and press Start.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val code = it.getIntExtra("proj_code", -1)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra("proj_data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra<Intent>("proj_data")
            }

            if (data != null && mediaProjection == null) {
                serviceScope.launch {
                    try {
                        val projection = withContext(Dispatchers.IO) {
                            (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                                .getMediaProjection(code, data)
                        }
                        if (projection != null) {
                            projection.registerCallback(object : MediaProjection.Callback() {
                                override fun onStop() {
                                    floatingWindow.appendLog("Screen Capture stopped")
                                }
                            }, null)
                            mediaProjection = projection
                            executorAgent.setupProjection(projection)
                            floatingWindow.appendLog("Screen Capture ready")
                        }
                    } catch (e: Exception) {
                        floatingWindow.appendLog("Error: ${e.message}")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopAi()
        floatingWindow.dismiss()
        ScreenControllerService.onVolumeUpPressed = null
        executorAgent.destroy()
        mediaProjection?.stop()
        mediaProjection = null
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

        if (!executorAgent.isProjectionReady) {
            floatingWindow.appendLog("Error: Screen Capture not ready.")
            return
        }

        isAiRunning = true
        floatingWindow.setStartButtonText("Stop (Vol↑)")
        floatingWindow.switchMode(FloatingControlWindow.Mode.PASSTHROUGH)
        floatingWindow.setLog("AI running...")
        floatingWindow.setStatusText("● Running")
        floatingWindow.setStatusColor(ContextCompat.getColor(this, R.color.statusRunning))

        executorAgent.start(command)
    }

    private fun stopAi() {
        isAiRunning = false
        executorAgent.stop()
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
