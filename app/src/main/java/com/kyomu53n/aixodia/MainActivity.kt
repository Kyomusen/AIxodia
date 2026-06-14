package com.kyomu53n.aixodia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kyomu53n.aixodia.ai.AiClientFactory
import com.kyomu53n.aixodia.ai.AiConfig
import com.kyomu53n.aixodia.ai.AiProvider
import com.kyomu53n.aixodia.executor.ExecutorLoop
import com.kyomu53n.aixodia.vision.ScreenEyes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var etTask: TextInputEditText
    private lateinit var btnRun: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnGrant: MaterialButton
    private lateinit var btnSettings: ImageButton

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loop: ExecutorLoop? = null
    private val SHIZUKU_CODE = 1001

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvLog           = findViewById(R.id.tvLog)
        scrollLog       = findViewById(R.id.scrollLog)
        etTask          = findViewById(R.id.etTask)
        btnRun          = findViewById(R.id.btnRun)
        btnStop         = findViewById(R.id.btnStop)
        btnGrant        = findViewById(R.id.btnGrant)
        btnSettings     = findViewById(R.id.btnSettings)

        setupShizuku()
        setupButtons()
        setupKeyboardInsets()
        requestRuntimePermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permResult)
    }

    // ─────────────────────────────────────────────
    // Keyboard insets
    // ─────────────────────────────────────────────

    private fun setupKeyboardInsets() {
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = maxOf(imeInsets.bottom, navInsets.bottom)
            }
            insets
        }
    }

    // ─────────────────────────────────────────────
    // Shizuku
    // ─────────────────────────────────────────────

    private val binderReceived = Shizuku.OnBinderReceivedListener { checkOrRequestPermission() }
    private val binderDead     = Shizuku.OnBinderDeadListener { setShizukuStatus(false) }
    private val permResult     = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_CODE) {
            val ok = result == PackageManager.PERMISSION_GRANTED
            setShizukuStatus(ok)
            btnGrant.visibility = if (ok) View.GONE else View.VISIBLE
        }
    }

    private fun setupShizuku() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permResult)
    }

    private fun checkOrRequestPermission() {
        if (Shizuku.isPreV11()) { setShizukuStatus(false); return }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }
        setShizukuStatus(granted)
        btnGrant.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun setShizukuStatus(ok: Boolean) = runOnUiThread {
        tvShizukuStatus.setTextColor(if (ok) 0xFF44FF44.toInt() else 0xFFFF4444.toInt())
    }

    // ─────────────────────────────────────────────
    // Buttons
    // ─────────────────────────────────────────────

    private fun setupButtons() {
        btnGrant.setOnClickListener { Shizuku.requestPermission(SHIZUKU_CODE) }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnRun.setOnClickListener {
            val task = etTask.text?.toString()?.trim() ?: ""
            if (task.isEmpty()) { appendLog("⚠ Enter a task first"); return@setOnClickListener }
            startLoop(task)
        }
        btnStop.setOnClickListener {
            loop?.stop()
            setRunningState(false)
        }
    }

    // ─────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001
                )
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission")
                .setMessage("Allow AIXODIA to show floating stop button over other apps?")
                .setPositiveButton("Allow") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Skip", null)
                .show()
        }
    }

    // ─────────────────────────────────────────────
    // Executor loop
    // ─────────────────────────────────────────────

    private fun startLoop(task: String) {
        val fullConfig = ConfigStore.load(this)
        val config = fullConfig.aiConfig
        if (config.apiKey.isEmpty()) { appendLog("⚠ No API key — open Settings first"); return }

        clearLog()
        setRunningState(true)

        // Log real screen size for debugging
        scope.launch(Dispatchers.IO) {
            val screenSize = ScreenEyes.getScreenSize()
            runOnUiThread {
                appendLog("Screen: ${screenSize?.width ?: "?"}x${screenSize?.height ?: "?"}px")
            }
        }

        val client = AiClientFactory.create(config)
        loop = ExecutorLoop(
            client  = client,
            onLog   = { msg: String ->
                runOnUiThread { appendLog(msg) }
                when {
                    msg.startsWith("AI:")       -> AutomationService.headsUp(this, "AI thinking", msg.removePrefix("AI:").trim().take(80))
                    msg.startsWith("→")         -> AutomationService.headsUp(this, "Action", msg.trim().take(80))
                    msg.startsWith("✓")         -> AutomationService.headsUp(this, "Done ✓", msg.trim().take(80))
                    msg.startsWith("✗")         -> AutomationService.headsUp(this, "Error ✗", msg.trim().take(80))
                }
                AutomationService.update(this, msg.take(60))
            },
            onDone  = { summary: String ->
                runOnUiThread { appendLog("✓ $summary"); setRunningState(false) }
                AutomationService.stop(this)
            },
            onError = { err: String ->
                runOnUiThread { appendLog("✗ $err"); setRunningState(false) }
                AutomationService.stop(this)
            }
        )

        AutomationService.stopCallback = {
            loop?.stop()
            runOnUiThread { setRunningState(false) }
        }
        AutomationService.start(this, task.take(60))

        scope.launch {
            loop?.start(task = task, targetPackage = fullConfig.targetPkg.ifEmpty { null })
        }
    }

    private fun setRunningState(running: Boolean) {
        btnRun.isEnabled  = !running
        btnStop.isEnabled = running
    }

    // ─────────────────────────────────────────────
    // Log
    // ─────────────────────────────────────────────

    private fun appendLog(msg: String) {
        val prev = tvLog.text.toString()
        tvLog.text = if (prev.isEmpty()) msg else "$prev\n$msg"
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearLog() { tvLog.text = "" }

    // ─────────────────────────────────────────────
    // Settings dialog
    // ─────────────────────────────────────────────

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val spinner        = view.findViewById<Spinner>(R.id.spinnerProvider)
        val etApiKey       = view.findViewById<TextInputEditText>(R.id.etApiKey)
        val etModel        = view.findViewById<TextInputEditText>(R.id.etModel)
        val etBaseUrl      = view.findViewById<TextInputEditText>(R.id.etBaseUrl)
        val etPkg          = view.findViewById<TextInputEditText>(R.id.etTargetPkg)
        val etSystemPrompt = view.findViewById<TextInputEditText>(R.id.etSystemPrompt)
        val tilBaseUrl     = view.findViewById<TextInputLayout>(R.id.tilBaseUrl)

        val providers = AiProvider.entries.map { it.name }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)

        val fullConfig = ConfigStore.load(this)
        val cfg = fullConfig.aiConfig

        spinner.setSelection(AiProvider.entries.indexOf(cfg.provider).coerceAtLeast(0))
        etApiKey.setText(cfg.apiKey)
        etModel.setText(cfg.model)
        val defaultUrl = AiConfig.defaultBaseUrl(cfg.provider)
        etBaseUrl.setText(if (cfg.baseUrl == defaultUrl) "" else cfg.baseUrl)
        etPkg.setText(fullConfig.targetPkg)
        etSystemPrompt.setText(fullConfig.systemPrompt)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val prov = AiProvider.entries[pos]
                if (etModel.text.isNullOrBlank() ||
                    etModel.text.toString() == ConfigStore.defaultModel(cfg.provider)) {
                    etModel.setText(ConfigStore.defaultModel(prov))
                }
                tilBaseUrl.hint = "Base URL (default: ${AiConfig.defaultBaseUrl(prov)})"
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val prov = AiProvider.entries[spinner.selectedItemPosition]
                val baseUrl = etBaseUrl.text?.toString()?.trim()?.ifEmpty {
                    AiConfig.defaultBaseUrl(prov)
                } ?: AiConfig.defaultBaseUrl(prov)
                val newCfg = AiConfig(
                    provider     = prov,
                    apiKey       = etApiKey.text?.toString()?.trim() ?: "",
                    model        = etModel.text?.toString()?.trim() ?: ConfigStore.defaultModel(prov),
                    baseUrl      = baseUrl,
                    systemPrompt = etSystemPrompt.text?.toString() ?: ""
                )
                ConfigStore.save(
                    ctx          = this,
                    config       = newCfg,
                    targetPkg    = etPkg.text?.toString()?.trim() ?: "",
                    systemPrompt = etSystemPrompt.text?.toString() ?: ""
                )
                appendLog("Settings saved (${prov.name} / ${newCfg.model})")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}