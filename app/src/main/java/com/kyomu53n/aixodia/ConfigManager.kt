package com.kyomu53n.aixodia

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ConfigManager(context: Context) {

	private val masterKey = MasterKey.Builder(context)
		.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
		.build()

	private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
		context,
		"aixodia_secure_prefs",
		masterKey,
		EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
		EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
	)

	fun saveApiKey(apiKey: String) {
		sharedPreferences.edit().putString("api_key", apiKey).apply()
	}

	fun getApiKey(): String {
		return sharedPreferences.getString("api_key", "") ?: ""
	}

	fun saveAi1Model(modelName: String) {
		sharedPreferences.edit().putString("ai1_model", modelName).apply()
	}

	fun getAi1Model(): String {
		return sharedPreferences.getString("ai1_model", "gemini-1.5-pro") ?: "gemini-1.5-pro"
	}

	fun saveAi2Model(modelName: String) {
		sharedPreferences.edit().putString("ai2_model", modelName).apply()
	}

	fun getAi2Model(): String {
		return sharedPreferences.getString("ai2_model", "gemini-1.5-flash") ?: "gemini-1.5-flash"
	}
}
