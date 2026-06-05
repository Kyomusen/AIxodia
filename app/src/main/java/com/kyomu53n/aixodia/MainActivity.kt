package com.kyomu53n.aixodia

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kyomu53n.aixodia.databinding.ActivityMainBinding

private const val REQ_OVERLAY = 1001
private const val REQ_PROJECT = 1002

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager
    private lateinit var projectionManager: MediaProjectionManager

    private var captureResultCode: Int? = null
    private var captureData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager(this)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupViews()
        updatePermissionStatus()
    }

    private fun setupViews() {
        val savedKey = configManager.getApiKey()
        binding.etApiKey.setText(savedKey)
        updateApiKeyStatus(savedKey)

        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            configManager.saveApiKey(key)
            updateApiKeyStatus(key)
        }

        binding.btnRequestOverlay.setOnClickListener {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        }

        binding.btnRequestAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnRequestNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2003)
            }
        }

        binding.btnRequestCapture.setOnClickListener {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_PROJECT)
        }

        binding.btnStartService.setOnClickListener {
            val apiKey = configManager.getApiKey()
            if (apiKey.isEmpty()) return@setOnClickListener
            if (captureResultCode == null || captureData == null) return@setOnClickListener

            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra("proj_code", captureResultCode ?: -1)
                putExtra("proj_data", captureData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            finish()
        }
    }

    @Deprecated("Use ActivityResult API but matching working example pattern")
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

    private fun updateApiKeyStatus(key: String) {
        binding.tvApiKeyStatus.text = if (key.isNotEmpty()) {
            "API Key: Set (${key.take(4)}...${key.takeLast(4)})"
        } else {
            "API Key: Not set"
        }
        binding.tvApiKeyStatus.setTextColor(
            if (key.isNotEmpty()) android.graphics.Color.parseColor("#00C853")
            else android.graphics.Color.parseColor("#CC0000")
        )
    }

    private fun updatePermissionStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val hasAccessibility = isAccessibilityServiceEnabled()

        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        binding.btnRequestOverlay.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        binding.btnRequestAccessibility.visibility = if (hasAccessibility) View.GONE else View.VISIBLE
        binding.btnRequestNotification.visibility = if (hasNotification || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) View.GONE else View.VISIBLE

        val statusText = buildString {
            append("Permissions:\n")
            append(if (hasOverlay) "✅ Overlay" else "❌ Overlay").append("\n")
            append(if (hasAccessibility) "✅ Accessibility" else "❌ Accessibility").append("\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append(if (hasNotification) "✅ Notifications" else "❌ Notifications").append("\n")
            }
            append(if (captureResultCode != null) "✅ Screen Capture" else "❌ Screen Capture")
        }
        binding.tvPermissionStatus.text = statusText

        binding.btnStartService.isEnabled = hasOverlay && hasAccessibility && hasNotification &&
            configManager.getApiKey().isNotEmpty() && captureResultCode != null
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/$packageName.ScreenControllerService"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(':').any { it.equals(service, ignoreCase = true) }
        } catch (_: Exception) {
            return false
        }
    }
}
