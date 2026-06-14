package com.kyomu53n.aixodia.executor

import com.kyomu53n.aixodia.ShizukuCommandExecutor
import com.kyomu53n.aixodia.executor.CommandParser.AiAction
import com.kyomu53n.aixodia.vision.ScreenEyes
import java.io.File

/**
 * ActionExecutor — executes a list of AiActions one by one.
 * Pauses at Screencap actions and returns the captured file for AI to process.
 */
object ActionExecutor {

    sealed class ExecuteResult {
        /** All actions completed, no screencap encountered */
        object Completed : ExecuteResult()
        /** Paused at a Screencap action — caller should send image back to AI */
        data class NeedsVision(
            val captureResult: ScreenEyes.CaptureResult,
            val remainingActions: List<AiAction>
        ) : ExecuteResult()
        /** A Done action was reached */
        data class Done(val summary: String, val saveMemory: Boolean) : ExecuteResult()
        /** An action failed */
        data class Error(val action: AiAction, val message: String) : ExecuteResult()
    }

    fun interface LogCallback {
        fun log(message: String)
    }

    /**
     * Execute actions in order.
     * Stops and returns NeedsVision when a Screencap action is encountered.
     */
    fun execute(
        actions: List<AiAction>,
        log: LogCallback = LogCallback {}
    ): ExecuteResult {
        for ((idx, action) in actions.withIndex()) {
            when (action) {

                is AiAction.Tap -> {
                    log.log("→ tap(${action.x}, ${action.y})")
                    val r = ShizukuCommandExecutor.tap(action.x, action.y)
                    if (!r.success) return ExecuteResult.Error(action, r.stderr)
                }

                is AiAction.LongPress -> {
                    log.log("→ long_press(${action.x}, ${action.y}, ${action.ms}ms)")
                    val r = ShizukuCommandExecutor.longPress(action.x, action.y, action.ms)
                    if (!r.success) return ExecuteResult.Error(action, r.stderr)
                }

                is AiAction.Swipe -> {
                    log.log("→ swipe(${action.x1},${action.y1} → ${action.x2},${action.y2})")
                    val r = ShizukuCommandExecutor.swipe(
                        action.x1, action.y1, action.x2, action.y2, action.ms
                    )
                    if (!r.success) return ExecuteResult.Error(action, r.stderr)
                }

                is AiAction.TypeText -> {
                    log.log("→ type(\"${action.text}\")")
                    val r = ShizukuCommandExecutor.typeText(action.text)
                    if (!r.success) return ExecuteResult.Error(action, r.stderr)
                }

                is AiAction.KeyEvent -> {
                    log.log("→ keyevent(${action.key})")
                    val r = ShizukuCommandExecutor.keyEvent(action.key)
                    if (!r.success) return ExecuteResult.Error(action, r.stderr)
                }

                is AiAction.Sleep -> {
                    log.log("→ sleep(${action.ms}ms)")
                    Thread.sleep(action.ms.toLong().coerceIn(0, 30000))
                }

                is AiAction.Launch -> {
                    log.log("→ launch(${action.pkg})")
                    val r = ShizukuCommandExecutor.launchApp(action.pkg)
                    if (!r.success) return ExecuteResult.Error(action, r.stderr)
                }

                is AiAction.Screencap -> {
                    log.log("→ screencap(scale=${action.scale}, crop=${action.crop})")
                    val config = ScreenEyes.CaptureConfig(
                        scale    = action.scale,
                        crop     = action.crop,
                        gridStep = 100
                    )
                    val result = ScreenEyes.capture(config)
                        ?: return ExecuteResult.Error(action, "screencap failed")

                    log.log("  captured: ${result.outputWidth}x${result.outputHeight}px")

                    // Pause — return remaining actions after this screencap
                    val remaining = actions.subList(idx + 1, actions.size)
                    return ExecuteResult.NeedsVision(result, remaining)
                }

                is AiAction.Done -> {
                    log.log("✓ done: ${action.summary}")
                    return ExecuteResult.Done(action.summary, action.saveMemory)
                }

                is AiAction.Unknown -> {
                    log.log("⚠ unknown action: ${action.raw}")
                    // skip unknown, don't abort
                }
            }
        }
        return ExecuteResult.Completed
    }
}