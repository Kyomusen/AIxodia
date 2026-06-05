package com.kyomu53n.aixodia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class ExecutorAgent(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var loopJob: Job? = null
    private var isRunning = false

    fun startExecutionLoop(userCommand: String, onLog: (String) -> Unit) {
        if (isRunning) return
        isRunning = true
        loopJob = scope.launch {
            try {
                val configManager = ConfigManager(context)
                val promptDirectoryManager = PromptDirectoryManager(context)

                val apiKey = configManager.getApiKey()
                if (apiKey.isEmpty()) {
                    onLog("Error: API Key is empty")
                    isRunning = false
                    return@launch
                }

                val ai1ModelName = configManager.getAi1Model()
                val ai2ModelName = configManager.getAi2Model()

                val ai1SystemPrompt = promptDirectoryManager.readPrompt("ai1_system.md")
                val ai2SystemPrompt = promptDirectoryManager.readPrompt("ai2_system.md")

                onLog("Initializing AI Agent 1...")
                val ai1Model = GenerativeModel(
                    modelName = ai1ModelName,
                    apiKey = apiKey,
                    generationConfig = generationConfig {
                        temperature = 0.2f
                    },
                    systemInstruction = content { text(ai1SystemPrompt) }
                )

                onLog("Sending command to AI Agent 1...")
                val ai1Response = ai1Model.generateContent(
                    content { text(userCommand) }
                )
                val goalJson = ai1Response.text ?: ""
                onLog("AI Agent 1 Plan: $goalJson")

                onLog("Initializing AI Agent 2 Loop...")
                val ai2Model = GenerativeModel(
                    modelName = ai2ModelName,
                    apiKey = apiKey,
                    generationConfig = generationConfig {
                        temperature = 0.2f
                    },
                    systemInstruction = content { text(ai2SystemPrompt) }
                )

                val history = mutableListOf<String>()
                history.add("Goal and Plan: $goalJson")

                while (isRunning) {
                    onLog("Capturing screen and UI hierarchy...")
                    val bitmap = ScreenCaptureService.captureCurrentScreen()
                    val uiTreeJson = ScreenControllerService.getUiHierarchyJson()

                    if (bitmap == null) {
                        onLog("Warning: Failed to capture screen. Retrying...")
                        delay(2000)
                        continue
                    }

                    val compressedStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, compressedStream)
                    val imageBytes = compressedStream.toByteArray()
                    bitmap.recycle()

                    val decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                    onLog("Sending state to AI Agent 2...")
                    val promptContent = content {
                        image(decodedBitmap)
                        text("Current UI Hierarchy JSON:\n$uiTreeJson\n\nHistory and Context:\n${history.joinToString("\n")}\n\nDetermine the next precise action command.")
                    }

                    val ai2Response = ai2Model.generateContent(promptContent)
                    val actionCommand = ai2Response.text?.trim() ?: ""
                    onLog("AI Agent 2 Action: $actionCommand")

                    decodedBitmap.recycle()
                    history.add("Executed Action: $actionCommand")

                    val executionResult = ActionParser.executeCommand(actionCommand)
                    onLog("Execution Result: $executionResult")

                    if (executionResult == "GOAL_ACHIEVED") {
                        onLog("Success: Mission accomplished.")
                        break
                    } else if (executionResult.startsWith("GOAL_FAILED")) {
                        onLog("Failure: Loop stopped. Reason: $executionResult")
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

    fun stopExecutionLoop() {
        isRunning = false
        loopJob?.cancel()
        loopJob = null
    }
}