package com.kyomu53n.aixodia.ai

import java.io.File

/**
 * AiClient — common interface for all AI providers.
 * Each provider implements this to send messages and receive AiResponse.
 */
interface AiClient {

    /** Provider name for display e.g. "Gemini", "OpenAI" */
    val providerName: String

    /** Whether this provider supports sending image as raw file (not base64) */
    val supportsFileUpload: Boolean

    /**
     * Send a conversation turn to the AI.
     * @param messages full conversation history
     * @param imageFile optional screenshot to attach (null = text only)
     * @return raw JSON string from AI (to be parsed by CommandParser)
     */
    suspend fun send(messages: List<AiMessage>, imageFile: File? = null): String

    /** Test connectivity / API key validity */
    suspend fun ping(): Boolean
}

// ─────────────────────────────────────────────────────
// Data models shared across all providers
// ─────────────────────────────────────────────────────

enum class AiRole { SYSTEM, USER, ASSISTANT }

data class AiMessage(
    val role: AiRole,
    val content: String,
    val imageFile: File? = null   // only for USER messages
)

// ─────────────────────────────────────────────────────
// Factory
// ─────────────────────────────────────────────────────

object AiClientFactory {

    fun create(config: AiConfig): AiClient = when (config.provider) {
        AiProvider.GEMINI    -> GeminiClient(config)
        AiProvider.OPENAI    -> OpenAiClient(config)
        AiProvider.OPENROUTER -> OpenAiClient(config) // same API, different base URL
    }
}

enum class AiProvider {
    GEMINI,
    OPENAI,
    OPENROUTER
}

data class AiConfig(
    val provider: AiProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String = defaultBaseUrl(provider),
    val maxTokens: Int = 4096,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {    companion object {
        fun defaultBaseUrl(provider: AiProvider) = when (provider) {
            AiProvider.GEMINI     -> "https://generativelanguage.googleapis.com/v1beta"
            AiProvider.OPENAI     -> "https://api.openai.com/v1"
            AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1"
        }
    }
}

// ─────────────────────────────────────────────────────
// System prompt
// ─────────────────────────────────────────────────────

const val DEFAULT_SYSTEM_PROMPT = """
You are AIXODIA, an Android automation AI agent.
Your job is to control the device screen to complete tasks given by the user.

## Output format
Always respond with a single JSON object. No markdown, no explanation outside JSON.

{
  "thoughts": "your reasoning about what you see and what to do next",
  "confidence": 0.0-1.0,
  "actions": [
    { "type": "screencap", "crop": [x, y, w, h], "scale": 0.5 },
    { "type": "tap", "x": 500, "y": 700 },
    { "type": "long_press", "x": 500, "y": 700, "ms": 1000 },
    { "type": "swipe", "x1": 100, "y1": 800, "x2": 100, "y2": 200, "ms": 300 },
    { "type": "type", "text": "hello world" },
    { "type": "keyevent", "key": "ENTER" },
    { "type": "sleep", "ms": 1000 },
    { "type": "launch", "package": "com.google.android.youtube" },
    { "type": "done", "summary": "task completed", "save_memory": true }
  ]
}

## Rules
- ALWAYS start with a screencap if you are unsure of the current screen state.
- screencap in actions = PAUSE. You will receive the screenshot and respond with next actions.
- If you are confident about positions (from memory or previous screencap), omit screencap and run actions directly.
- done.save_memory = true → I will save your knowledge for this app for future use.
- Screen coordinates are absolute pixels based on the device screen size provided.
- Grid lines are drawn every 100px to help you locate elements precisely.
"""