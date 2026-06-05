package com.kyomu53n.aixodia

import android.graphics.BitmapFactory
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Frame(
    val image: Image,
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
    val width: Int,
    val height: Int
)

class ExecutorAgent(
    private val context: Context,
    private val metrics: DisplayMetrics,
    private val onLog: (String) -> Unit
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var isRunning = false
        private set
    var isProjectionReady = false
        private set

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    fun setupProjection(projection: MediaProjection) {
        mediaProjection = projection
        val real = getPhysicalSize()
        imageReader = ImageReader.newInstance(real.x, real.y, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "AIxodia_Capture",
            real.x,
            real.y,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            null
        )
        isProjectionReady = true
        onLog("Capture ready: ${real.x}x${real.y}")
    }

    fun destroy() {
        scope.cancel()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        isProjectionReady = false
    }

    private fun getPhysicalSize(): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = Point()
        wm.defaultDisplay.getRealSize(size)
        return size
    }

    private fun captureFrame(): Frame? {
        if (!isProjectionReady) return null
        val image = try { imageReader?.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        val plane = image.planes[0]
        val buffer = plane.buffer.also { it.order(ByteOrder.LITTLE_ENDIAN) }
        return Frame(image, buffer, plane.rowStride, plane.pixelStride, image.width, image.height)
    }

    private fun closeFrame(frame: Frame) = frame.image.close()

    private fun frameToBitmap(frame: Frame): Bitmap? {
        return try {
            val buffer = frame.buffer
            buffer.rewind()
            val bitmap = Bitmap.createBitmap(
                frame.width,
                frame.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun start(command: String) {
        if (isRunning) return
        isRunning = true
        job = scope.launch {
            try {
                val configManager = ConfigManager(context)
                val apiKey = configManager.getApiKey()
                if (apiKey.isEmpty()) {
                    onLog("Error: API Key is empty")
                    isRunning = false
                    return@launch
                }

                val promptDirectoryManager = PromptDirectoryManager(context)
                val ai1ModelName = configManager.getAi1Model()
                val ai2ModelName = configManager.getAi2Model()

                val ai1SystemPrompt = promptDirectoryManager.readPrompt("ai1_system.md")
                val ai2SystemPrompt = promptDirectoryManager.readPrompt("ai2_system.md")

                onLog("Initializing AI Agent 1...")
                val ai1Model = GenerativeModel(
                    modelName = ai1ModelName,
                    apiKey = apiKey,
                    generationConfig = generationConfig { temperature = 0.2f },
                    systemInstruction = content { text(ai1SystemPrompt) }
                )

                onLog("Sending command to AI Agent 1...")
                val ai1Response = ai1Model.generateContent(
                    content { text(command) }
                )
                val goalJson = ai1Response.text ?: ""
                onLog("AI Agent 1 Plan: $goalJson")

                onLog("Initializing AI Agent 2 Loop...")
                val ai2Model = GenerativeModel(
                    modelName = ai2ModelName,
                    apiKey = apiKey,
                    generationConfig = generationConfig { temperature = 0.2f },
                    systemInstruction = content { text(ai2SystemPrompt) }
                )

                val history = mutableListOf<String>()
                history.add("Goal and Plan: $goalJson")

                while (isRunning) {
                    onLog("Capturing screen and UI hierarchy...")
                    val frame = captureFrame()
                    val uiTreeJson = ScreenControllerService.getUiHierarchyJson()

                    if (frame == null) {
                        onLog("Warning: Failed to capture screen. Retrying...")
                        delay(2000)
                        continue
                    }

                    val bitmap = frameToBitmap(frame)
                    closeFrame(frame)

                    if (bitmap == null) {
                        onLog("Warning: Failed to process bitmap. Retrying...")
                        delay(2000)
                        continue
                    }

                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val imageBytes = stream.toByteArray()
                    val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    bitmap.recycle()

                    onLog("Sending state to AI Agent 2...")
                    val promptContent = content {
                        image(decoded)
                        text("Current UI Hierarchy JSON:\n$uiTreeJson\n\nHistory:\n${history.joinToString("\n")}\n\nDetermine the next action command.")
                    }

                    val ai2Response = ai2Model.generateContent(promptContent)
                    val actionCommand = ai2Response.text?.trim() ?: ""
                    onLog("AI Agent 2 Action: $actionCommand")

                    decoded.recycle()
                    history.add("Executed Action: $actionCommand")

                    val executionResult = ActionParser.executeCommand(actionCommand)
                    onLog("Execution Result: $executionResult")

                    if (executionResult == "GOAL_ACHIEVED") {
                        onLog("Success: Mission accomplished.")
                        break
                    } else if (executionResult.startsWith("GOAL_FAILED")) {
                        onLog("Failure: $executionResult")
                        break
                    }

                    delay(3000)
                }
            } catch (e: Exception) {
                onLog("Execution Error: ${e.message}")
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
    }
}
