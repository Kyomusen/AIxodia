package com.kyomu53n.aixodia

import android.content.Context
import com.kyomu53n.aixodia.ai.AiConfig
import com.kyomu53n.aixodia.ai.AiProvider

object ConfigStore {

    private const val PREF = "aixodia_config"

    data class FullConfig(
        val aiConfig: AiConfig,
        val targetPkg: String,
        val systemPrompt: String
    )

    fun save(ctx: Context, config: AiConfig, targetPkg: String, systemPrompt: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putString("provider", config.provider.name)
            putString("apiKey", config.apiKey)
            putString("model", config.model)
            putString("baseUrl", config.baseUrl)
            putInt("maxTokens", config.maxTokens)
            putString("targetPkg", targetPkg)
            putString("systemPrompt", systemPrompt)
            apply()
        }
    }

    fun load(ctx: Context): FullConfig {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val provider = try {
            AiProvider.valueOf(p.getString("provider", AiProvider.GEMINI.name)!!)
        } catch (e: Exception) { AiProvider.GEMINI }

        val baseUrl = (p.getString("baseUrl", "") ?: "").ifEmpty {
            AiConfig.defaultBaseUrl(provider)
        }
        val savedPrompt = p.getString("systemPrompt", "") ?: ""
        val aiConfig = AiConfig(
            provider     = provider,
            apiKey       = p.getString("apiKey", "") ?: "",
            model        = p.getString("model", defaultModel(provider)) ?: defaultModel(provider),
            baseUrl      = baseUrl,
            maxTokens    = p.getInt("maxTokens", 4096),
            systemPrompt = savedPrompt.ifEmpty { com.kyomu53n.aixodia.ai.DEFAULT_SYSTEM_PROMPT }
        )
        return FullConfig(
            aiConfig     = aiConfig,
            targetPkg    = p.getString("targetPkg", "") ?: "",
            systemPrompt = p.getString("systemPrompt", "") ?: ""
        )
    }

    fun defaultModel(provider: AiProvider) = when (provider) {
        AiProvider.GEMINI     -> "gemini-2.0-flash"
        AiProvider.OPENAI     -> "gpt-4o"
        AiProvider.OPENROUTER -> "google/gemini-2.0-flash-exp:free"
    }
}