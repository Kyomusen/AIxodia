package com.kyomu53n.aixodia.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * AppMemory — load/save per-app knowledge as JSON files.
 * Location: <app filesDir>/memory/<package>.json
 * Accessible via Files app or adb pull.
 */
object AppMemory {

    private lateinit var memoryDir: File

    fun init(context: Context) {
        memoryDir = File(context.filesDir, "memory")
        memoryDir.mkdirs()
    }

    // ─────────────────────────────────────────────
    // Load / Save
    // ─────────────────────────────────────────────

    fun load(pkg: String): AppKnowledge? {
        val file = fileFor(pkg)
        if (!file.exists()) return null
        return try {
            fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            null
        }
    }

    fun save(knowledge: AppKnowledge) {
        val file = fileFor(knowledge.pkg)
        val updated = knowledge.copy(
            updated = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(Date())
        )
        file.writeText(toJson(updated).toString(2))
    }

    fun delete(pkg: String) {
        fileFor(pkg).delete()
    }

    fun listAll(): List<AppKnowledge> {
        return memoryDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try { fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
            } ?: emptyList()
    }

    fun memoryFileFor(pkg: String): File = fileFor(pkg)

    // ─────────────────────────────────────────────
    // Merge — update knowledge, add/replace elements and scripts
    // ─────────────────────────────────────────────

    fun merge(pkg: String, newElements: List<KnownElement>, newScripts: List<SavedScript>) {
        val existing = load(pkg) ?: AppKnowledge(pkg)
        val mergedElements = existing.knowledge.toMutableList()
        for (el in newElements) {
            val idx = mergedElements.indexOfFirst { it.label == el.label }
            if (idx >= 0) mergedElements[idx] = el else mergedElements.add(el)
        }
        val mergedScripts = existing.scripts.toMutableList()
        for (sc in newScripts) {
            val idx = mergedScripts.indexOfFirst { it.name == sc.name }
            if (idx >= 0) mergedScripts[idx] = sc else mergedScripts.add(sc)
        }
        save(existing.copy(knowledge = mergedElements, scripts = mergedScripts))
    }

    // ─────────────────────────────────────────────
    // Build context string to inject into AI prompt
    // ─────────────────────────────────────────────

    fun buildContext(pkg: String): String {
        val k = load(pkg) ?: return ""
        return buildString {
            appendLine("App: ${k.appName.ifEmpty { pkg }} ($pkg)")
            appendLine("Last updated: ${k.updated}")
            if (k.knowledge.isNotEmpty()) {
                appendLine("Known elements:")
                k.knowledge.forEach { el ->
                    appendLine("  ${el.label}: (${el.x}, ${el.y})  — ${el.note}")
                }
            }
            if (k.scripts.isNotEmpty()) {
                appendLine("Saved scripts:")
                k.scripts.forEach { sc ->
                    appendLine("  ${sc.name}: ${sc.description} (${sc.steps.size} steps)")
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // JSON serialization
    // ─────────────────────────────────────────────

    private fun toJson(k: AppKnowledge): JSONObject = JSONObject().apply {
        put("pkg", k.pkg)
        put("appName", k.appName)
        put("updated", k.updated)
        put("knowledge", JSONArray().also { arr ->
            k.knowledge.forEach { el ->
                arr.put(JSONObject().apply {
                    put("label", el.label)
                    put("x", el.x)
                    put("y", el.y)
                    put("note", el.note)
                    put("screenWidth", el.screenWidth)
                    put("screenHeight", el.screenHeight)
                })
            }
        })
        put("scripts", JSONArray().also { arr ->
            k.scripts.forEach { sc ->
                arr.put(JSONObject().apply {
                    put("name", sc.name)
                    put("description", sc.description)
                    put("steps", JSONArray().also { steps ->
                        sc.steps.forEach { step ->
                            steps.put(JSONObject().apply {
                                put("type", step.type)
                                if (step.x != 0)    put("x", step.x)
                                if (step.y != 0)    put("y", step.y)
                                if (step.x2 != 0)   put("x2", step.x2)
                                if (step.y2 != 0)   put("y2", step.y2)
                                if (step.ms != 0)   put("ms", step.ms)
                                if (step.text.isNotEmpty()) put("text", step.text)
                                if (step.key.isNotEmpty())  put("key", step.key)
                                if (step.pkg.isNotEmpty())  put("package", step.pkg)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun fromJson(j: JSONObject): AppKnowledge {
        val knowledge = mutableListOf<KnownElement>()
        val kArr = j.optJSONArray("knowledge")
        if (kArr != null) {
            for (i in 0 until kArr.length()) {
                val o = kArr.getJSONObject(i)
                knowledge.add(KnownElement(
                    label = o.getString("label"),
                    x = o.getInt("x"),
                    y = o.getInt("y"),
                    note = o.optString("note"),
                    screenWidth = o.optInt("screenWidth"),
                    screenHeight = o.optInt("screenHeight")
                ))
            }
        }
        val scripts = mutableListOf<SavedScript>()
        val sArr = j.optJSONArray("scripts")
        if (sArr != null) {
            for (i in 0 until sArr.length()) {
                val o = sArr.getJSONObject(i)
                val steps = mutableListOf<ScriptStep>()
                val stArr = o.optJSONArray("steps")
                if (stArr != null) {
                    for (si in 0 until stArr.length()) {
                        val s = stArr.getJSONObject(si)
                        steps.add(ScriptStep(
                            type = s.getString("type"),
                            x    = s.optInt("x"),
                            y    = s.optInt("y"),
                            x2   = s.optInt("x2"),
                            y2   = s.optInt("y2"),
                            ms   = s.optInt("ms"),
                            text = s.optString("text"),
                            key  = s.optString("key"),
                            pkg  = s.optString("package")
                        ))
                    }
                }
                scripts.add(SavedScript(o.getString("name"), o.optString("description"), steps))
            }
        }
        return AppKnowledge(
            pkg = j.getString("pkg"),
            appName = j.optString("appName"),
            updated = j.optString("updated"),
            knowledge = knowledge,
            scripts = scripts
        )
    }

    private fun fileFor(pkg: String) = File(memoryDir, "$pkg.json")
}