package com.kyomu53n.aixodia.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GeminiClient — implements AiClient for Google Gemini API.
 * Supports multimodal (text + image) via inline base64.
 * Gemini does not support raw file upload in REST API, so supportsFileUpload = false.
 */
class GeminiClient(private val config: AiConfig) : AiClient {

    override val providerName = "Gemini"
    override val supportsFileUpload = false  // uses base64 inline

    override suspend fun send(messages: List<AiMessage>, imageFile: File?): String {
        val url = "${config.baseUrl}/models/${config.model}:generateContent?key=${config.apiKey}"
        val body = buildRequestBody(messages, imageFile)
        return post(url, body)
    }

    override suspend fun ping(): Boolean {
        return try {
            val url = "${config.baseUrl}/models?key=${config.apiKey}"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            false
        }
    }

    // ─────────────────────────────────────────────
    // Build Gemini request body
    // ─────────────────────────────────────────────

    private fun buildRequestBody(messages: List<AiMessage>, imageFile: File?): String {
        val root = JSONObject()

        // System instruction
        root.put("system_instruction", JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().put("text", config.systemPrompt))
            })
        })

        // Generation config — force JSON output
        root.put("generationConfig", JSONObject().apply {
            put("responseMimeType", "application/json")
            put("maxOutputTokens", config.maxTokens)
            put("temperature", 0.2)
        })

        // Contents (conversation history)
        val contents = JSONArray()
        for (msg in messages) {
            if (msg.role == AiRole.SYSTEM) continue // handled above
            val content = JSONObject()
            content.put("role", if (msg.role == AiRole.USER) "user" else "model")
            val parts = JSONArray()

            // Text part
            if (msg.content.isNotBlank()) {
                parts.put(JSONObject().put("text", msg.content))
            }

            // Image part (on USER messages only)
            val img = msg.imageFile ?: if (msg.role == AiRole.USER) imageFile else null
            if (img != null && img.exists()) {
                parts.put(buildImagePart(img))
            }

            content.put("parts", parts)
            contents.put(content)
        }
        root.put("contents", contents)
        return root.toString()
    }

    private fun buildImagePart(file: File): JSONObject {
        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", "image/png")
                put("data", b64)
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
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000

            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val stream = if (code == 200) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader().readText()

            if (code != 200) throw Exception("HTTP $code: $response")

            // Extract text from Gemini response
            val json = JSONObject(response)
            return json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } finally {
            conn.disconnect()
        }
    }
}