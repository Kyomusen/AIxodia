package com.kyomu53n.aixodia.executor

import android.graphics.Rect
import com.kyomu53n.aixodia.vision.ScreenEyes
import org.json.JSONObject

/**
 * CommandParser — parse AI JSON response into a list of AiAction.
 */
object CommandParser {

    sealed class AiAction {
        data class Tap(val x: Int, val y: Int) : AiAction()
        data class LongPress(val x: Int, val y: Int, val ms: Int = 1000) : AiAction()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Int = 300) : AiAction()
        data class TypeText(val text: String) : AiAction()
        data class KeyEvent(val key: String) : AiAction()
        data class Sleep(val ms: Int) : AiAction()
        data class Launch(val pkg: String) : AiAction()
        data class Screencap(
            val scale: Float = 0.5f,
            val crop: Rect? = null      // null = full screen
        ) : AiAction()
        data class Done(
            val summary: String,
            val saveMemory: Boolean = false
        ) : AiAction()
        data class Unknown(val raw: String) : AiAction()
    }

    data class ParseResult(
        val thoughts: String,
        val confidence: Float,
        val actions: List<AiAction>
    )

    fun parse(json: String): ParseResult {
        return try {
            val root = JSONObject(json.trim().removePrefix("```json").removeSuffix("```").trim())
            val thoughts    = root.optString("thoughts", "")
            val confidence  = root.optDouble("confidence", 0.5).toFloat()
            val actionsArr  = root.optJSONArray("actions")
            val actions     = mutableListOf<AiAction>()

            if (actionsArr != null) {
                for (i in 0 until actionsArr.length()) {
                    val obj = actionsArr.getJSONObject(i)
                    actions.add(parseAction(obj))
                }
            }

            ParseResult(thoughts, confidence, actions)
        } catch (e: Exception) {
            ParseResult("Parse error: ${e.message}", 0f, listOf(AiAction.Unknown(json)))
        }
    }

    private fun parseAction(obj: JSONObject): AiAction {
        return when (obj.optString("type")) {
            "tap"        -> AiAction.Tap(obj.getInt("x"), obj.getInt("y"))
            "long_press" -> AiAction.LongPress(
                obj.getInt("x"), obj.getInt("y"),
                obj.optInt("ms", 1000)
            )
            "swipe"      -> AiAction.Swipe(
                obj.getInt("x1"), obj.getInt("y1"),
                obj.getInt("x2"), obj.getInt("y2"),
                obj.optInt("ms", 300)
            )
            "type"       -> AiAction.TypeText(obj.getString("text"))
            "keyevent"   -> AiAction.KeyEvent(obj.getString("key"))
            "sleep"      -> AiAction.Sleep(obj.optInt("ms", 500))
            "launch"     -> AiAction.Launch(obj.getString("package"))
            "screencap"  -> {
                val cropArr = obj.optJSONArray("crop")
                val crop = if (cropArr != null && cropArr.length() == 4) {
                    Rect(
                        cropArr.getInt(0), cropArr.getInt(1),
                        cropArr.getInt(0) + cropArr.getInt(2),  // x + w
                        cropArr.getInt(1) + cropArr.getInt(3)   // y + h
                    )
                } else null
                AiAction.Screencap(
                    scale = obj.optDouble("scale", 0.5).toFloat(),
                    crop  = crop
                )
            }
            "done"       -> AiAction.Done(
                summary    = obj.optString("summary", ""),
                saveMemory = obj.optBoolean("save_memory", false)
            )
            else -> AiAction.Unknown(obj.toString())
        }
    }
}