package com.kyomu53n.aixodia

import android.content.Context
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
import com.kyomu53n.aixodia.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager

    private val overlayRequestCode = 2001
    private val accessibilityRequestCode = 2002
    private val notificationRequestCode = 2003
    private val captureRequestCode = 1001

    private var captureResultCode: Int? = null
    private var captureData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager(this)

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
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${packageName}")
            )
            startActivityForResult(intent, overlayRequestCode)
        }

        binding.btnRequestAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, accessibilityRequestCode)
        }

        binding.btnStartService.setOnClickListener {
            val apiKey = configManager.getApiKey()
            if (apiKey.isEmpty()) return@setOnClickListener

            startOverlayService()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        captureResultCode?.let { rc ->
            captureData?.let { data ->
                intent.putExtra("RESULT_CODE", rc)
                intent.putExtra("DATA", data)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish()
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

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        binding.btnRequestOverlay.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        binding.btnRequestAccessibility.visibility = if (hasAccessibility) View.GONE else View.VISIBLE

        val statusText = buildString {
            append("Permissions:\n")
            append(if (hasOverlay) "✅ Overlay" else "❌ Overlay")
            append("\n")
            append(if (hasAccessibility) "✅ Accessibility" else "❌ Accessibility")
            append("\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append(if (hasNotification) "✅ Notifications" else "❌ Notifications")
                append("\n")
            }
            append(if (captureResultCode != null) "✅ Screen Capture" else "❌ Screen Capture")
        }
        binding.tvPermissionStatus.text = statusText

        val apiKeyOk = configManager.getApiKey().isNotEmpty()
        binding.btnStartService.isEnabled = hasOverlay && hasAccessibility && hasNotification && apiKeyOk && captureResultCode != null
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

    fun requestScreenCapture(view: View) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            overlayRequestCode, accessibilityRequestCode -> {
                updatePermissionStatus()
            }
            captureRequestCode -> {
                if (resultCode == RESULT_OK && data != null) {
                    captureResultCode = resultCode
                    captureData = data

                    // Set up projection immediately so it's ready
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val metrics = resources.displayMetrics
                    ScreenCaptureService.setupProjection(
                        resultCode = resultCode,
                        data = data,
                        manager = manager,
                        width = metrics.widthPixels,
                        height = metrics.heightPixels,
                        density = metrics.densityDpi
                    )

                    updatePermissionStatus()
                }
            }
        }
    }
}
