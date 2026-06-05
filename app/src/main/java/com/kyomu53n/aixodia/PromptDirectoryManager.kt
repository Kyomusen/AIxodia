package com.kyomu53n.aixodia

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class PromptDirectoryManager(private val context: Context) {

	private val baseDir: File = context.getExternalFilesDir(null) ?: context.filesDir

	fun getPromptFile(fileName: String): File {
		return File(baseDir, fileName)
	}

	fun readPrompt(fileName: String): String {
		val file = getPromptFile(fileName)
		if (file.exists()) {
			try {
				return file.readText()
			} catch (e: IOException) {
				e.printStackTrace()
			}
		}
		return readFromAssets(fileName)
	}

	fun writePrompt(fileName: String, content: String): Boolean {
		val file = getPromptFile(fileName)
		return try {
			FileOutputStream(file).use { output ->
				output.write(content.toByteArray())
			}
			true
		} catch (e: IOException) {
			e.printStackTrace()
			false
		}
	}

	fun isFileUsingDefault(fileName: String): Boolean {
		val file = getPromptFile(fileName)
		return !file.exists()
	}

	private fun readFromAssets(fileName: String): String {
		return try {
			context.assets.open(fileName).bufferedReader().use { it.readText() }
		} catch (e: IOException) {
			""
		}
	}
}
