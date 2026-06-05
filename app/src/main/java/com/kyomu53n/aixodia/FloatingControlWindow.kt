package com.kyomu53n.aixodia

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat

class FloatingControlWindow(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Interactive state views
    private val interactiveView: LinearLayout
    private val passthroughView: LinearLayout

    // Interactive widgets
    private val etCommand: EditText
    private val tvLog: TextView
    private val btnStart: Button
    private val btnSettings: Button

    // Passthrough widgets
    private val tvStatus: TextView
    private val tvLastLog: TextView

    private var currentMode: Mode = Mode.INTERACTIVE
    private var startClickListener: (() -> Unit)? = null
    private var settingsClickListener: (() -> Unit)? = null

    enum class Mode { INTERACTIVE, PASSTHROUGH }

    init {
        // --- Interactive View ---
        interactiveView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(ContextCompat.getColor(context, R.color.overlayBg))
            layoutParams = ViewGroup.LayoutParams(
                dp(320),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val tvTitle = TextView(context).apply {
            text = "AIxodia"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        titleRow.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        interactiveView.addView(titleRow)

        tvLog = TextView(context).apply {
            setTextColor(android.graphics.Color.parseColor("#B0B0B0"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            minLines = 3
            maxLines = 6
            setPadding(8, 8, 8, 8)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        interactiveView.addView(tvLog)

        etCommand = EditText(context).apply {
            hint = "Enter command..."
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            setBackgroundResource(android.R.drawable.editbox_background)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        interactiveView.addView(etCommand)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        btnStart = Button(context).apply {
            text = "Start"
            setOnClickListener { startClickListener?.invoke() }
        }
        buttonRow.addView(btnStart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        btnSettings = Button(context).apply {
            text = "Settings"
            setOnClickListener { settingsClickListener?.invoke() }
        }
        buttonRow.addView(btnSettings, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        interactiveView.addView(buttonRow)

        // --- Passthrough View ---
        passthroughView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(ContextCompat.getColor(context, R.color.overlayBgPassthrough))
            layoutParams = ViewGroup.LayoutParams(
                dp(250),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        tvStatus = TextView(context).apply {
            text = "● Running"
            setTextColor(ContextCompat.getColor(context, R.color.statusRunning))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        passthroughView.addView(tvStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        tvLastLog = TextView(context).apply {
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            textSize = 11f
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
            setPadding(8, 0, 0, 0)
        }
        passthroughView.addView(tvLastLog, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun show(mode: Mode = Mode.INTERACTIVE) {
        dismiss()
        currentMode = mode
        val view = when (mode) {
            Mode.INTERACTIVE -> interactiveView
            Mode.PASSTHROUGH -> passthroughView
        }
        val flags = when (mode) {
            Mode.INTERACTIVE -> {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
            }
            Mode.PASSTHROUGH -> {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(100)
        }
        windowManager.addView(view, params)
    }

    fun dismiss() {
        try {
            if (interactiveView.isAttachedToWindow) windowManager.removeView(interactiveView)
            if (passthroughView.isAttachedToWindow) windowManager.removeView(passthroughView)
        } catch (_: Exception) {}
    }

    fun switchMode(mode: Mode) {
        dismiss()
        show(mode)
    }

    fun appendLog(text: String) {
        tvLog.append("$text\n")
        tvLastLog.text = text
    }

    fun setLog(text: String) {
        tvLog.text = text
        tvLastLog.text = text
    }

    fun setStartButtonText(text: String) {
        btnStart.text = text
    }

    fun setStatusText(text: String) {
        tvStatus.text = text
    }

    fun setStatusColor(color: Int) {
        tvStatus.setTextColor(color)
    }

    fun getCommandText(): String = etCommand.text.toString()

    fun setCommandText(text: String) {
        etCommand.setText(text)
    }

    fun onStartClick(listener: () -> Unit) {
        startClickListener = listener
    }

    fun onSettingsClick(listener: () -> Unit) {
        settingsClickListener = listener
    }

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density).toInt()
    }
}
