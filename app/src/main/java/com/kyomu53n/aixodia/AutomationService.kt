package com.kyomu53n.aixodia

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.WindowManager.LayoutParams.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * AutomationService — Foreground service that:
 * - Shows persistent notification while AI is running
 * - Shows heads-up notification on start/done/error
 * - Displays floating STOP button overlay (emergency stop)
 */
class AutomationService : Service() {

    companion object {
        const val ACTION_START   = "START"
        const val ACTION_STOP    = "STOP"
        const val ACTION_UPDATE  = "UPDATE"
        const val ACTION_HEADSUP = "HEADSUP"
        const val EXTRA_MSG      = "msg"
        const val CHANNEL_ID    = "aixodia_automation"
        const val CHANNEL_HU    = "aixodia_headsup"
        const val NOTIF_ID      = 1001
        const val NOTIF_HU_ID   = 1002

        var stopCallback: (() -> Unit)? = null

        fun start(ctx: Context, msg: String = "AI running...") {
            val i = Intent(ctx, AutomationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MSG, msg)
            }
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AutomationService::class.java).apply {
                action = ACTION_STOP
            })
        }

        fun update(ctx: Context, msg: String) {
            ctx.startService(Intent(ctx, AutomationService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_MSG, msg)
            })
        }

        fun headsUp(ctx: Context, title: String, msg: String) {
            ctx.startService(Intent(ctx, AutomationService::class.java).apply {
                action = ACTION_HEADSUP
                putExtra("title", title)
                putExtra(EXTRA_MSG, msg)
            })
        }
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val msg = intent.getStringExtra(EXTRA_MSG) ?: "AI running..."
                startForeground(NOTIF_ID, buildNotification(msg))
                showFloatingStop()
                showHeadsUp("AIXODIA", msg)
            }
            ACTION_UPDATE  -> {
                val msg = intent.getStringExtra(EXTRA_MSG) ?: ""
                updateNotification(msg)
            }
            ACTION_HEADSUP -> {
                val title = intent.getStringExtra("title") ?: "AIXODIA"
                val msg   = intent.getStringExtra(EXTRA_MSG) ?: ""
                showHeadsUp(title, msg)
            }
            ACTION_STOP -> {
                removeFloatingStop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeFloatingStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ─────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────

    private fun buildNotification(msg: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AutomationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AIXODIA")
            .setContentText(msg)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(msg: String) {
        NotificationManagerCompat.from(this)
            .notify(NOTIF_ID, buildNotification(msg))
    }

    private fun showHeadsUp(title: String, msg: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_HU)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_HU_ID, n)
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Automation Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while AI automation is running"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HU, "Automation Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Heads-up alerts for automation events"
            }
        )
    }

    // ─────────────────────────────────────────────
    // Floating STOP button
    // ─────────────────────────────────────────────

    private fun showFloatingStop() {
        if (floatView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val tv = TextView(this).apply {
            text = "■ STOP"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCCCC0000.toInt())
            setPadding(24, 12, 24, 12)
            elevation = 10f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }

        tv.setOnClickListener {
            stopCallback?.invoke()
            AutomationService.stop(this@AutomationService)
        }

        var initX = 0
        var initY = 0
        var initTX = 0
        var initTY = 0

        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTX = e.rawX.toInt(); initTY = e.rawY.toInt()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (initTX - e.rawX.toInt())
                    params.y = initY + (e.rawY.toInt() - initTY)
                    wm.updateViewLayout(tv, params)
                    true
                }
                else -> false
            }
        }

        wm.addView(tv, params)
        floatView = tv
    }

    private fun removeFloatingStop() {
        floatView?.let {
            runCatching { windowManager?.removeView(it) }
            floatView = null
        }
    }
}