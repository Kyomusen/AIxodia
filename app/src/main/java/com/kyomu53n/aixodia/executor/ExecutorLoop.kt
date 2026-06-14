package com.kyomu53n.aixodia.executor

import com.kyomu53n.aixodia.ai.AiClient
import com.kyomu53n.aixodia.ai.AiMessage
import com.kyomu53n.aixodia.ai.AiRole
import com.kyomu53n.aixodia.executor.ActionExecutor.ExecuteResult
import com.kyomu53n.aixodia.memory.AppMemory
import com.kyomu53n.aixodia.vision.ScreenEyes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExecutorLoop(
    private val client: AiClient,
    private val onLog: (String) -> Unit,
    private val onDone: (summary: String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val history = mutableListOf<AiMessage>()
    private var running = false
    private val MAX_TURNS = 20

    suspend fun start(
        task: String,
        targetPackage: String? = null
    ) = withContext(Dispatchers.IO) {
        if (running) { onError("Loop already running"); return@withContext }
        running = true
        history.clear()

        // Inject app memory
        val memCtx = targetPackage?.let { AppMemory.buildContext(it) } ?: ""
        val userMsg = buildString {
            if (memCtx.isNotEmpty()) { appendLine("[APP MEMORY]"); appendLine(memCtx); appendLine() }
            appendLine("[TASK]"); append(task)
        }
        history.add(AiMessage(AiRole.USER, userMsg))
        log("Task: $task")

        // Initial screencap
        log("Taking initial screenshot...")
        val screen = ScreenEyes.getScreenSize()
        val screenInfo = if (screen != null) "${screen.width}x${screen.height}px" else "unknown"
        log("Device screen: $screenInfo")

        val initCap = ScreenEyes.capture(ScreenEyes.CaptureConfig(scale = 0.5f, gridStep = 100))
        if (initCap != null) {
            val ctx = ScreenEyes.buildScreenContext(initCap)
            log("Screenshot: ${initCap.outputWidth}x${initCap.outputHeight}px (grid every 100px on original coords)")
            history.add(AiMessage(AiRole.USER, ctx, initCap.file))
        } else {
            log("⚠ Initial screenshot failed — AI will proceed without visual")
            history.add(AiMessage(AiRole.USER, "Screenshot unavailable. Device screen: $screenInfo"))
        }

        loop()
    }

    private suspend fun loop() {
        var turns = 0
        while (running && turns < MAX_TURNS) {
            turns++
            log("── Turn $turns ──────────────────")

            // Call AI
            log("Calling ${client.providerName}...")
            val rawJson = try {
                client.send(history)
            } catch (e: Exception) {
                onError("AI error: ${e.message}")
                running = false
                return
            }

            val parsed = CommandParser.parse(rawJson)
            log("AI: ${parsed.thoughts}")
            log("Confidence: ${(parsed.confidence * 100).toInt()}%")
            history.add(AiMessage(AiRole.ASSISTANT, rawJson))

            if (parsed.actions.isEmpty()) {
                // AI returned no actions — treat as done
                log("AI returned no actions, stopping")
                running = false
                onDone("Completed (no further actions)")
                return
            }

            val result = ActionExecutor.execute(parsed.actions) { msg -> log(msg) }

            when (result) {
                is ExecuteResult.Completed -> {
                    // All actions done, no screencap — check if done action was in list
                    // If not, ask AI what's next (max 1 more turn to avoid infinite loop)
                    if (turns < MAX_TURNS) {
                        history.add(AiMessage(AiRole.USER,
                            "All actions executed successfully. If the task is complete, respond with done. Otherwise provide next actions."))
                    } else {
                        running = false
                        onDone("Max turns reached")
                    }
                }

                is ExecuteResult.NeedsVision -> {
                    log("Screencap received, sending to AI...")
                    val ctx = ScreenEyes.buildScreenContext(result.captureResult)
                    val pendingNote = if (result.remainingActions.isNotEmpty())
                        "\n[${result.remainingActions.size} actions pending after your review]"
                    else "\nWhat should I do next?"
                    history.add(AiMessage(AiRole.USER, ctx + pendingNote, result.captureResult.file))
                }

                is ExecuteResult.Done -> {
                    // Force a final screencap to confirm task before accepting done
                    log("Verifying task completion...")
                    val verifyCap = ScreenEyes.capture(
                        ScreenEyes.CaptureConfig(scale = 0.5f, gridStep = 100)
                    )
                    if (verifyCap != null) {
                        val ctx = ScreenEyes.buildScreenContext(verifyCap)
                        history.add(AiMessage(AiRole.USER,
                            "$ctx\nThis is the current screen. Does it confirm the task '${result.summary}' was completed successfully? If yes, respond with done again. If not, continue with corrective actions.",
                            verifyCap.file
                        ))
                        // Give AI one more turn to confirm or correct
                        val confirmJson = try { client.send(history) }
                        catch (e: Exception) { null }
                        if (confirmJson != null) {
                            val confirmParsed = CommandParser.parse(confirmJson)
                            history.add(AiMessage(AiRole.ASSISTANT, confirmJson))
                            if (confirmParsed.actions.any { it is CommandParser.AiAction.Done }) {
                                log("✓ Task confirmed: ${result.summary}")
                                running = false
                                onDone(result.summary)
                                return
                            } else if (confirmParsed.actions.isNotEmpty()) {
                                // AI wants to correct — continue loop with remaining actions
                                log("AI correcting after verification...")
                                val fixResult = ActionExecutor.execute(confirmParsed.actions) { msg -> log(msg) }
                                if (fixResult is ExecuteResult.Done) {
                                    log("✓ Done after correction: ${(fixResult as ExecuteResult.Done).summary}")
                                }
                                running = false
                                onDone((fixResult as? ExecuteResult.Done)?.summary ?: result.summary)
                                return
                            }
                        }
                    }
                    log("✓ Done: ${result.summary}")
                    running = false
                    onDone(result.summary)
                    return
                }

                is ExecuteResult.Error -> {
                    val msg = "Action failed [${result.action}]: ${result.message}"
                    log("✗ $msg")
                    history.add(AiMessage(AiRole.USER, "Error: $msg. Please adjust and retry."))
                }
            }
        }

        if (turns >= MAX_TURNS) onError("Max turns ($MAX_TURNS) reached")
        running = false
    }

    fun stop() { running = false; log("Loop stopped by user") }
    fun isRunning() = running
    private fun log(msg: String) = onLog(msg)
}