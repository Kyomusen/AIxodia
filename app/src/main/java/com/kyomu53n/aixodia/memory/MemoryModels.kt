package com.kyomu53n.aixodia.memory

/**
 * MemoryModels — data classes for per-app AI memory.
 * Stored as JSON files: /files/memory/<package>.json
 */

data class AppKnowledge(
    val pkg: String,
    val appName: String = "",
    val updated: String = "",                       // ISO 8601
    val knowledge: List<KnownElement> = emptyList(),
    val scripts: List<SavedScript> = emptyList()
)

/** A UI element the AI has learned about */
data class KnownElement(
    val label: String,          // e.g. "search_button"
    val x: Int,
    val y: Int,
    val note: String = "",      // e.g. "top right corner, magnifier icon"
    val screenWidth: Int = 0,   // original screen size when recorded
    val screenHeight: Int = 0
)

/** A reusable script the AI generated */
data class SavedScript(
    val name: String,           // e.g. "search_video"
    val description: String = "",
    val steps: List<ScriptStep> = emptyList()
)

data class ScriptStep(
    val type: String,           // tap / swipe / type / sleep / keyevent / launch
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val ms: Int = 0,
    val text: String = "",
    val key: String = "",
    val pkg: String = ""
)