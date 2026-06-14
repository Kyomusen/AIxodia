package com.kyomu53n.aixodia.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAiClient — implements AiClient for OpenAI API and compatible endpoints.
 * Also used for OpenRouter (same API shape, different base URL + model string).
 */
class OpenAiClient(private val config: AiConfig) : AiClient {

    override val providerName = when (config.provider) {
        AiProvider.OPENROUTER -> "OpenRouter"
        else -> "OpenAI"
    }
    override val supportsFileUpload = false  // vision via base64

    override suspend fun send(messages: List<AiMessage>, imageFile: File?): String {
        val url = "${config.baseUrl}/chat/completions"
        val body = buildRequestBody(messages, imageFile)
        return post(url, body)
    }

    override suspend fun ping(): Boolean {
        return try {
            val url = "${config.baseUrl}/models"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.connectTimeout = 5000
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            false
        }
    }

    // ─────────────────────────────────────────────
    // Build OpenAI request body
    // ─────────────────────────────────────────────

    private fun buildRequestBody(messages: List<AiMessage>, imageFile: File?): String {
        val root = JSONObject()
        root.put("model", config.model)
        root.put("max_tokens", config.maxTokens)
        root.put("temperature", 0.2)

        // Force JSON output (supported by gpt-4o, gpt-4-turbo, etc.)
        root.put("response_format", JSONObject().put("type", "json_object"))

        val jsonMessages = JSONArray()

        // System message
        jsonMessages.put(JSONObject().apply {
            put("role", "system")
            put("content", config.systemPrompt)
        })

        // Conversation history
        for (msg in messages) {
            if (msg.role == AiRole.SYSTEM) continue
            val role = if (msg.role == AiRole.USER) "user" else "assistant"
            val img = msg.imageFile ?: if (msg.role == AiRole.USER) imageFile else null

            if (img != null && img.exists() && msg.role == AiRole.USER) {
                // Multimodal content array
                val contentArr = JSONArray()
                if (msg.content.isNotBlank()) {
                    contentArr.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                contentArr.put(buildImageContent(img))
                jsonMessages.put(JSONObject().apply {
                    put("role", role)
                    put("content", contentArr)
                })
            } else {
                jsonMessages.put(JSONObject().apply {
                    put("role", role)
                    put("content", msg.content)
                })
            }
        }

        root.put("messages", jsonMessages)
        return root.toString()
    }

    private fun buildImageContent(file: File): JSONObject {
        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/png;base64,$b64")
                put("detail", "high")
            })
        }
    }

    // ─────────────────────────────────────────────
    // HTTP POST
    // ─────────────────────────────────────────────

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            if (config.provider == AiProvider.OPENROUTER) {
                conn.setRequestProperty("HTTP-Referer", "com.kyomu53n.aixodia")
                conn.setRequestProperty("X-Title", "AIXODIA")
            }
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000

            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val stream = if (code == 200) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader().readText()

            if (code != 200) throw Exception("HTTP $code: $response")

            val json = JSONObject(response)
            return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }
}