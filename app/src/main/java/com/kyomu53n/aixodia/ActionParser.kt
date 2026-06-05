package com.kyomu53n.aixodia

object ActionParser {

	fun executeCommand(actionCommand: String): String {
		val trimmed = actionCommand.trim()
		return when {
			trimmed.startsWith("click") -> {
				val x = extractParam(trimmed, "x:")?.toIntOrNull()
				val y = extractParam(trimmed, "y:")?.toIntOrNull()
				if (x != null && y != null) {
					val result = ScreenControllerService.performClick(x, y)
					if (result) "SUCCESS: clicked x:$x y:$y" else "FAILED: click failed"
				} else {
					"FAILED: invalid click parameters"
				}
			}
			trimmed.startsWith("swipe") -> {
				val x1 = extractParam(trimmed, "x1:")?.toIntOrNull()
				val y1 = extractParam(trimmed, "y1:")?.toIntOrNull()
				val x2 = extractParam(trimmed, "x2:")?.toIntOrNull()
				val y2 = extractParam(trimmed, "y2:")?.toIntOrNull()
				val duration = extractParam(trimmed, "duration:")?.toLongOrNull() ?: 300L
				if (x1 != null && y1 != null && x2 != null && y2 != null) {
					val result = ScreenControllerService.performSwipe(x1, y1, x2, y2, duration)
					if (result) "SUCCESS: swiped from $x1,$y1 to $x2,$y2" else "FAILED: swipe failed"
				} else {
					"FAILED: invalid swipe parameters"
				}
			}
			trimmed.startsWith("long_press") -> {
				val x = extractParam(trimmed, "x:")?.toIntOrNull()
				val y = extractParam(trimmed, "y:")?.toIntOrNull()
				if (x != null && y != null) {
					val result = ScreenControllerService.performLongPress(x, y)
					if (result) "SUCCESS: long pressed x:$x y:$y" else "FAILED: long press failed"
				} else {
					"FAILED: invalid long_press parameters"
				}
			}
			trimmed.startsWith("input_text") -> {
				val text = extractParam(trimmed, "text:")
				if (text != null) {
					val result = ScreenControllerService.performInputText(text)
					if (result) "SUCCESS: input text: $text" else "FAILED: input text failed"
				} else {
					"FAILED: invalid input_text parameters"
				}
			}
			trimmed == "press_back" -> {
				val result = ScreenControllerService.performPressBack()
				if (result) "SUCCESS: back pressed" else "FAILED: back press failed"
			}
			trimmed == "GOAL_ACHIEVED" -> {
				"GOAL_ACHIEVED"
			}
			trimmed.startsWith("GOAL_FAILED") -> {
				trimmed
			}
			else -> {
				"FAILED: unknown command pattern"
			}
		}
	}

	private fun extractParam(command: String, key: String): String? {
		val index = command.indexOf(key)
		if (index == -1) return null
		val start = index + key.length
		val substring = command.substring(start).trim()
		val spaceIndex = substring.indexOf(" ")
		return if (spaceIndex == -1) substring else substring.substring(0, spaceIndex)
	}
}
