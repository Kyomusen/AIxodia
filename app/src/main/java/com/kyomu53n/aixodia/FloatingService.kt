package com.kyomu53n.aixodia

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private var iconView: View? = null
    private var menuView: View? = null
    private lateinit var executor: ExecutorAgent
    private val logBuf = StringBuilder()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        executor = ExecutorAgent(this, resources.displayMetrics) { msg -> appendLog(msg) }
        startFg()
        showIcon()
        appendLog("AIxodia ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("proj_code", -1) ?: -1
        val data = intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra("proj_data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra<Intent>("proj_data")
            }
        }

        if (data != null) {
            serviceScope.launch {
                try {
                    val projection = withContext(Dispatchers.IO) {
                        (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                            .getMediaProjection(code, data)
                    }
                    if (projection != null) {
                        projection.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() { appendLog("Screen Capture stopped") }
                        }, null)
                        executor.setupProjection(projection)
                        appendLog("Screen Capture ready")
                    }
                } catch (e: Exception) {
                    appendLog("Error: ${e.message}")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        executor.stop()
        executor.destroy()
        removeIcon()
        removeMenu()
        super.onDestroy()
    }

    private fun startFg() {
        val chId = "nte_fg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "AIxodia Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        startForeground(9001, NotificationCompat.Builder(this, chId)
            .setContentTitle("AIxodia")
            .setContentText("AI Agent is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build())
    }

    private fun showIcon() {
        if (iconView?.isAttachedToWindow == true) return
        iconView = LayoutInflater.from(this).inflate(R.layout.layout_floating_icon, null)
        val params = iconParams()
        iconView!!.setOnTouchListener { v, e ->
            var drag = false
            var initX = params.x.toFloat()
            var initY = params.y.toFloat()
            var initTx = 0f
            var initTy = 0f
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { initTx = e.rawX; initTy = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - initTx) > 5 || Math.abs(e.rawY - initTy) > 5) {
                        drag = true
                        params.x = initX.toInt() + (e.rawX - initTx).toInt()
                        params.y = initY.toInt() + (e.rawY - initTy).toInt()
                        if (iconView?.isAttachedToWindow == true) {
                            try { wm.updateViewLayout(iconView, params) } catch (_: Exception) {}
                        }
                    }; true
                }
                MotionEvent.ACTION_UP -> { if (!drag) { removeIcon(); showMenu() }; false }
                else -> false
            }
        }
        wm.addView(iconView, params)
    }

    private fun showMenu() {
        menuView = LayoutInflater.from(this).inflate(R.layout.layout_floating_menu, null)
        val metrics = resources.displayMetrics
        val menuWidth = (metrics.widthPixels * 0.4).toInt()

        val params = WindowManager.LayoutParams(
            menuWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 40 }

        val tvLog = menuView!!.findViewById<TextView>(R.id.tvLog)
        val tvStatus = menuView!!.findViewById<TextView>(R.id.tvRunStatus)
        val etCommand = menuView!!.findViewById<EditText>(R.id.etCommand)
        tvLog.text = logBuf.toString().ifEmpty { "[Ready]" }

        menuView!!.findViewById<Button>(R.id.btnAutoStart).setOnClickListener {
            if (ScreenControllerService.sharedInstance == null) {
                appendLog("Please enable Accessibility first")
                return@setOnClickListener
            }
            if (!executor.isProjectionReady) {
                appendLog("Screen Capture not ready")
                return@setOnClickListener
            }
            val cmd = etCommand?.text?.toString()?.trim() ?: ""
            if (cmd.isEmpty()) {
                appendLog("Please enter a command")
                return@setOnClickListener
            }
            executor.start(cmd)
            tvStatus.text = "Running"
            tvStatus.setTextColor(0xFF00C853.toInt())
            appendLog("AI started")
        }

        menuView!!.findViewById<Button>(R.id.btnAutoStop).setOnClickListener {
            executor.stop()
            tvStatus.text = "Stopped"
            tvStatus.setTextColor(0xFFFF6B6B.toInt())
            appendLog("AI stopped")
        }

        menuView!!.findViewById<Button>(R.id.btnHide).setOnClickListener { removeMenu(); showIcon() }
        menuView!!.findViewById<Button>(R.id.btnClose).setOnClickListener { executor.stop(); stopSelf() }

        try {
            if (menuView?.isAttachedToWindow == false) wm.addView(menuView, params)
        } catch (_: Exception) {}
    }

    private fun removeIcon() {
        iconView?.let { try { if (it.isAttachedToWindow) wm.removeView(it) } catch (_: Exception) {} }
        iconView = null
    }

    private fun removeMenu() {
        menuView?.let { try { if (it.isAttachedToWindow) wm.removeView(it) } catch (_: Exception) {} }
        menuView = null
    }

    private fun appendLog(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            logBuf.appendLine(msg)
            val lines = logBuf.lines().takeLast(100)
            logBuf.clear()
            logBuf.append(lines.joinToString("\n"))
            menuView?.let { view ->
                if (view.isAttachedToWindow) {
                    val tv = view.findViewById<TextView>(R.id.tvLog)
                    val sv = view.findViewById<ScrollView>(R.id.scrollLog)
                    tv?.text = logBuf.toString()
                    sv?.post { if (view.isAttachedToWindow) sv.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun iconParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
