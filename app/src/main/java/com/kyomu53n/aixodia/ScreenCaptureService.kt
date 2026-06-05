package com.kyomu53n.aixodia

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "ScreenCaptureServiceChannel"
        private const val NOTIFICATION_ID = 531

        var isServiceRunning = false
            private set

        private var mediaProjection: MediaProjection? = null
        private var imageReader: ImageReader? = null
        private var virtualDisplay: VirtualDisplay? = null

        private var screenWidth = 1080
        private var screenHeight = 1920
        private var screenDensity = 400

        // Callback for captured frames (used by ExecutorAgent)
        private var onCaptureCallback: ((Bitmap) -> Unit)? = null

        // OverlayView for preventing screen capture of our own window
        private var overlayView: android.view.View? = null
        private var windowManager: WindowManager? = null

        private var pendingResultCode: Int? = null
        private var pendingData: Intent? = null
        private var pendingManager: MediaProjectionManager? = null

        @SuppressLint("WrongConstant")
        fun setupProjection(
            resultCode: Int,
            data: Intent,
            manager: MediaProjectionManager,
            width: Int,
            height: Int,
            density: Int
        ) {
            screenWidth = width
            screenHeight = height
            screenDensity = density
            mediaProjection = manager.getMediaProjection(resultCode, data)
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        }

        fun initializeProjectionOnStart(context: Context) {
            // projection will be set up when MainActivity passes the result
            // This method is called from OverlayService to ensure it's ready
        }

        fun registerCallback(callback: (Bitmap) -> Unit) {
            onCaptureCallback = callback
        }

        fun startOverlay(context: Context) {
            if (overlayView != null) return
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = android.view.View(context).apply {
                setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0)) // nearly transparent
            }
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            try {
                windowManager?.addView(overlayView, params)
            } catch (_: Exception) {}
        }

        fun stopOverlay() {
            try {
                overlayView?.let {
                    if (it.isAttachedToWindow) windowManager?.removeView(it)
                }
            } catch (_: Exception) {}
            overlayView = null
            windowManager = null
        }

        fun captureScreen(): Bitmap? {
            val projection = mediaProjection ?: return null
            val reader = imageReader ?: return null

            if (virtualDisplay == null) {
                virtualDisplay = projection.createVirtualDisplay(
                    "ScreenCapture",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null
                )
            }

            var bitmap: Bitmap? = null
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    if (rowPadding > 0) {
                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                        bitmap.recycle()
                        bitmap = croppedBitmap
                    }
                    onCaptureCallback?.invoke(bitmap!!)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    image.close()
                }
            }

            virtualDisplay?.release()
            virtualDisplay = null
            return bitmap
        }

        fun mediaProjectionReady(): Boolean {
            return mediaProjection != null
        }

        fun captureCurrentScreen(): Bitmap? = captureScreen()
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopOverlay()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIxodia AI Agent")
            .setContentText("Screen Capture Core is running in background...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
