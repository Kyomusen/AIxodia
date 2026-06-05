package com.kyomu53n.aixodia

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

private const val REQ_OVERLAY = 1001
private const val REQ_PROJECT = 1002
private const val PREFS = "aixodia_prefs"
private const val KEY_API = "gemini_api_key"

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var captureResultCode: Int? = null
    private var captureData: Intent? = null
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configManager = ConfigManager(this)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupViews()
        updatePermissionStatus()
    }

    private fun setupViews() {
        val savedKey = configManager.getApiKey()
        findViewById<EditText>(R.id.etKey).setText(savedKey)
        updateKeyStatus(savedKey)

        findViewById<Button>(R.id.btnValidate).setOnClickListener {
            val key = findViewById<EditText>(R.id.etKey).text.toString().trim()
            configManager.saveApiKey(key)
            updateKeyStatus(key)
        }

        findViewById<Button>(R.id.btnRequestOverlay).setOnClickListener {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        }

        findViewById<Button>(R.id.btnRequestNotification).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2003)
            }
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val key = configManager.getApiKey()
            if (key.isEmpty()) return@setOnClickListener
            if (captureResultCode == null || captureData == null) return@setOnClickListener

            val intent = Intent(this, FloatingService::class.java).apply {
                putExtra("proj_code", captureResultCode ?: -1)
                putExtra("proj_data", captureData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            finish()
        }

        findViewById<Button>(R.id.btnExit).setOnClickListener { finish() }
    }

    @Deprecated("Using startActivityForResult matching NTE Fishing pattern")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            updatePermissionStatus()
        } else if (requestCode == REQ_PROJECT && resultCode == RESULT_OK && data != null) {
            captureResultCode = resultCode
            captureData = data
            updatePermissionStatus()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updateKeyStatus(key: String) {
        val tv = findViewById<TextView>(R.id.tvApiKeyStatus)
        if (key.isNotEmpty()) {
            tv.text = "API Key: Set (${key.take(4)}...${key.takeLast(4)})"
            tv.setTextColor(0xFF00C853.toInt())
        } else {
            tv.text = "API Key: Not set"
            tv.setTextColor(0xFFCC0000.toInt())
        }
    }

    private fun updatePermissionStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        findViewById<Button>(R.id.btnRequestOverlay).visibility = if (hasOverlay) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnRequestNotification).visibility =
            if (hasNotification || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) View.GONE else View.VISIBLE

        val statusText = buildString {
            append("Permissions:\n")
            append(if (hasOverlay) "✅ Overlay" else "❌ Overlay").append("\n")
            append(if (hasAccessibility) "✅ Accessibility" else "❌ Accessibility").append("\n")
            append(if (captureResultCode != null) "✅ Screen Capture" else "❌ Screen Capture").append("\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append(if (hasNotification) "✅ Notifications" else "❌ Notifications")
            }
        }
        findViewById<TextView>(R.id.tvPermissionStatus).text = statusText

        findViewById<Button>(R.id.btnStart).isEnabled = hasOverlay && hasAccessibility && hasNotification &&
            configManager.getApiKey().isNotEmpty() && captureResultCode != null
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/$packageName.ScreenControllerService"
        try {
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabledServices.split(':').any { it.equals(service, ignoreCase = true) }
        } catch (_: Exception) { return false }
    }
}
