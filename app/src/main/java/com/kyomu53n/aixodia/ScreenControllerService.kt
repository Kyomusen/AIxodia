package com.kyomu53n.aixodia

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class ScreenControllerService : AccessibilityService() {

    companion object {
        var sharedInstance: ScreenControllerService? = null
            private set

        var onVolumeUpPressed: (() -> Unit)? = null

        fun getUiHierarchyJson(): String {
            return sharedInstance?.getUiHierarchyJsonInternal() ?: "{}"
        }

        fun performClick(x: Int, y: Int): Boolean {
            return sharedInstance?.performTap(x.toFloat(), y.toFloat()) ?: false
        }

        fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): Boolean {
            return sharedInstance?.performSwipeInternal(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration) ?: false
        }

        fun performLongPress(x: Int, y: Int): Boolean {
            return sharedInstance?.performLongPressInternal(x.toFloat(), y.toFloat()) ?: false
        }

        fun performInputText(text: String): Boolean {
            return sharedInstance?.performInputTextInternal(text) ?: false
        }

        fun performPressBack(): Boolean {
            return sharedInstance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        }

        fun tap(x: Float, y: Float) {
            sharedInstance?.performTap(x, y)
        }

        fun tapTimed(x: Float, y: Float, duration: Long) {
            sharedInstance?.tapTimedInternal(x, y, duration)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        sharedInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (sharedInstance == this) {
            sharedInstance = null
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            onVolumeUpPressed?.invoke()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun getUiHierarchyJsonInternal(): String {
        val root = rootInActiveWindow ?: return "{}"
        val json = JSONObject()
        try {
            parseNode(root, json)
        } finally {
            root.recycle()
        }
        return json.toString()
    }

    private fun parseNode(node: AccessibilityNodeInfo, json: JSONObject) {
        json.put("class", node.className?.toString() ?: "")
        json.put("text", node.text?.toString() ?: "")
        json.put("clickable", node.isClickable)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        json.put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
        val childrenArray = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childJson = JSONObject()
            parseNode(child, childJson)
            childrenArray.put(childJson)
        }
        if (childrenArray.length() > 0) {
            json.put("children", childrenArray)
        }
    }

    private fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performSwipeInternal(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performLongPressInternal(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1000L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun tapTimedInternal(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, maxOf(duration, 1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performInputTextInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = findFocusedNode(root) ?: return false
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        root.recycle()
        return result
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedNode(child)
            if (found != null) return found
        }
        return null
    }
}
