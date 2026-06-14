package com.kyomu53n.aixodia

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

object ShizukuCommandExecutor {

    private var appContext: Context? = null

    fun setup(context: Context) {
        appContext = context.applicationContext
    }

    data class Result(
        val success: Boolean,
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int = -1
    )

    // ─────────────────────────────────────────────
    // 1. CORE
    // ─────────────────────────────────────────────

    fun exec(cmd: String): Result {
        if (!isReady()) return Result(false, stderr = "Shizuku not ready")
        return try {
            val process = newProcess(arrayOf("sh", "-c", cmd))
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            process.destroy()
            Result(exit == 0, stdout, stderr, exit)
        } catch (e: Exception) {
            Result(false, stderr = e.message ?: "unknown error")
        }
    }

    fun execAll(vararg cmds: String): Result {
        for (cmd in cmds) {
            val r = exec(cmd)
            if (!r.success) return r
        }
        return Result(true)
    }

    // ─────────────────────────────────────────────
    // 2. TOUCH / GESTURE
    // ─────────────────────────────────────────────

    fun tap(x: Int, y: Int) = exec("input tap $x $y")
    fun longPress(x: Int, y: Int, durationMs: Int = 1000) = exec("input swipe $x $y $x $y $durationMs")
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) = exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    fun scrollUp(cx: Int, cy: Int, distance: Int = 500, durationMs: Int = 300) = swipe(cx, cy + distance / 2, cx, cy - distance / 2, durationMs)
    fun scrollDown(cx: Int, cy: Int, distance: Int = 500, durationMs: Int = 300) = swipe(cx, cy - distance / 2, cx, cy + distance / 2, durationMs)
    fun scrollLeft(cx: Int, cy: Int, distance: Int = 400, durationMs: Int = 300) = swipe(cx + distance / 2, cy, cx - distance / 2, cy, durationMs)
    fun scrollRight(cx: Int, cy: Int, distance: Int = 400, durationMs: Int = 300) = swipe(cx - distance / 2, cy, cx + distance / 2, cy, durationMs)

    // ─────────────────────────────────────────────
    // 3. KEYBOARD / TEXT
    // ─────────────────────────────────────────────

    fun typeText(text: String): Result {
        val hasUnicode = text.any { it.code > 127 }
        return if (hasUnicode) typeViaClipboard(text)
        else {
            val escaped = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace(" ", "%s")
                .replace("&", "\\&")
            exec("input text \"$escaped\"")
        }
    }

    private fun typeViaClipboard(text: String): Result {
        val ctx = appContext ?: return Result(false, stderr = "No context — call setup() first")
        var done = false
        Handler(Looper.getMainLooper()).post {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("aixodia", text))
            done = true
        }
        val deadline = System.currentTimeMillis() + 1000
        while (!done && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (!done) return Result(false, stderr = "Clipboard set timed out")
        Thread.sleep(150)
        // Try CTRL+V first, fallback to KEYCODE_PASTE
        val r = exec("input keyevent KEYCODE_CTRL_LEFT KEYCODE_V")
        return if (r.success) r else exec("input keyevent 279")
    }

    fun keyEvent(keycode: String) = exec("input keyevent KEYCODE_$keycode")
    fun keyEvent(code: Int) = exec("input keyevent $code")
    fun pressBack() = keyEvent("BACK")
    fun pressHome() = keyEvent("HOME")
    fun pressEnter() = keyEvent("ENTER")
    fun pressRecents() = keyEvent("APP_SWITCH")
    fun pressDelete() = keyEvent("DEL")
    fun pressPower() = keyEvent("POWER")
    fun pressVolumeUp() = keyEvent("VOLUME_UP")
    fun pressVolumeDown() = keyEvent("VOLUME_DOWN")
    fun clearField() = execAll("input keyevent KEYCODE_CTRL_A", "input keyevent KEYCODE_DEL")

    // ─────────────────────────────────────────────
    // 4. APP CONTROL
    // ─────────────────────────────────────────────

    fun launchApp(pkg: String): Result {
        // am start with MAIN/LAUNCHER intent — works for all apps
        val r1 = exec("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg")
        if (r1.success) return r1
        // Fallback: monkey
        val r2 = exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        if (r2.success) return r2
        // Last resort: query main activity and launch directly
        return exec("am start \$(cmd package resolve-activity --brief $pkg | tail -1)")
    }
    fun launchActivity(pkg: String, activity: String) = exec("am start -n $pkg/$activity")
    fun forceStop(pkg: String) = exec("am force-stop $pkg")
    fun clearAppData(pkg: String) = exec("pm clear $pkg")
    fun getForegroundApp(): String = exec("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'").stdout
    fun listPackages(): List<String> = exec("pm list packages").stdout.lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }
    fun isInstalled(pkg: String): Boolean = exec("pm list packages $pkg").stdout.contains(pkg)

    // ─────────────────────────────────────────────
    // 5. FILE OPERATIONS
    // ─────────────────────────────────────────────

    fun readFile(path: String) = exec("cat \"$path\"")
    fun writeFile(path: String, content: String): Result {
        val escaped = content.replace("'", "'\\''")
        return exec("printf '%s' '$escaped' > \"$path\"")
    }
    fun appendFile(path: String, content: String): Result {
        val escaped = content.replace("'", "'\\''")
        return exec("echo '$escaped' >> \"$path\"")
    }
    fun copyFile(src: String, dst: String) = exec("cp \"$src\" \"$dst\"")
    fun moveFile(src: String, dst: String) = exec("mv \"$src\" \"$dst\"")
    fun deleteFile(path: String) = exec("rm \"$path\"")
    fun deleteDir(path: String) = exec("rm -rf \"$path\"")
    fun mkdir(path: String) = exec("mkdir -p \"$path\"")
    fun listDir(path: String): List<String> = exec("ls -1 \"$path\"").stdout.lines().filter { it.isNotBlank() }
    fun exists(path: String): Boolean = exec("test -e \"$path\" && echo yes || echo no").stdout.trim() == "yes"
    fun fileSize(path: String): Long = exec("wc -c < \"$path\"").stdout.trim().toLongOrNull() ?: -1L
    fun chmod(path: String, mode: String) = exec("chmod $mode \"$path\"")

    // ─────────────────────────────────────────────
    // 6. SYSTEM INFO
    // ─────────────────────────────────────────────

    fun getScreenSize(): String = exec("wm size").stdout.substringAfter("Physical size:").trim()
    fun screencap(path: String = "/sdcard/screen.png") = exec("screencap -p \"$path\"")
    fun uiDump(path: String = "/sdcard/ui.xml") = exec("uiautomator dump \"$path\"")
    fun getBattery(): String = exec("dumpsys battery | grep level").stdout.trim()
    fun getModel(): String = exec("getprop ro.product.model").stdout.trim()
    fun getAndroidVersion(): String = exec("getprop ro.build.version.release").stdout.trim()

    // ─────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────

    fun isReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    private fun newProcess(cmd: Array<String>): ShizukuRemoteProcess {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as ShizukuRemoteProcess
    }
}