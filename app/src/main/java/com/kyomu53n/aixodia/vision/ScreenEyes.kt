package com.kyomu53n.aixodia.vision

import android.content.Context
import android.graphics.*
import com.kyomu53n.aixodia.ShizukuCommandExecutor
import java.io.File
import java.io.FileOutputStream

/**
 * ScreenEyes — capture, crop, scale, and annotate screenshots for AI vision.
 * Must call init(context) before use.
 */
object ScreenEyes {

    private lateinit var capsDir: File
    private lateinit var shellCapPath: String  // path shell can write + app can read

    fun init(context: Context) {
        capsDir = File(context.getExternalFilesDir(null), "caps")
        capsDir.mkdirs()
        shellCapPath = "${capsDir.absolutePath}/tmp_cap.png"
    }

    data class CaptureConfig(
        val scale: Float = 1.0f,
        val crop: Rect? = null,
        val gridStep: Int = 100,
        val gridColor: Int = 0x88FF0000.toInt(),
        val labelColor: Int = 0xFFFF4444.toInt()
    )

    data class CaptureResult(
        val file: File,
        val originalWidth: Int,
        val originalHeight: Int,
        val capturedRect: Rect,
        val outputWidth: Int,
        val outputHeight: Int,
        val scale: Float
    )

    // ─────────────────────────────────────────────
    // Main capture
    // ─────────────────────────────────────────────

    fun capture(
        config: CaptureConfig = CaptureConfig(),
        outFile: File = newOutFile()
    ): CaptureResult? {
        // 1. screencap to external files dir (shell-writable + app-readable)
        val r = ShizukuCommandExecutor.screencap(shellCapPath)
        if (!r.success) return null

        // 2. wait briefly for file to flush
        Thread.sleep(300)

        val srcFile = File(shellCapPath)
        if (!srcFile.exists() || srcFile.length() == 0L) return null

        // 3. decode bitmap
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = BitmapFactory.decodeFile(srcFile.absolutePath, opts) ?: return null
        val origW = bmp.width
        val origH = bmp.height

        // 5. crop
        val cropRect = config.crop ?: Rect(0, 0, origW, origH)
        val safeRect = Rect(
            cropRect.left.coerceIn(0, origW - 1),
            cropRect.top.coerceIn(0, origH - 1),
            cropRect.right.coerceIn(1, origW),
            cropRect.bottom.coerceIn(1, origH)
        )
        if (safeRect.width() > 0 && safeRect.height() > 0 &&
            (safeRect.left != 0 || safeRect.top != 0 ||
             safeRect.right != origW || safeRect.bottom != origH)) {
            bmp = Bitmap.createBitmap(
                bmp, safeRect.left, safeRect.top,
                safeRect.width(), safeRect.height()
            )
        }

        // 6. scale
        val scale = config.scale.coerceIn(0.1f, 1.0f)
        if (scale < 1.0f) {
            val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
            val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
            bmp = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        }

        // 7. draw grid
        if (config.gridStep > 0) {
            bmp = drawGrid(bmp, config, safeRect, scale)
        }

        // 8. save processed result
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 85, it)
        }

        return CaptureResult(
            file          = outFile,
            originalWidth = origW,
            originalHeight= origH,
            capturedRect  = safeRect,
            outputWidth   = bmp.width,
            outputHeight  = bmp.height,
            scale         = scale
        )
    }

    // ─────────────────────────────────────────────
    // Grid overlay
    // ─────────────────────────────────────────────

    private fun drawGrid(
        bmp: Bitmap,
        config: CaptureConfig,
        capturedRect: Rect,
        scale: Float
    ): Bitmap {
        val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val linePaint = Paint().apply {
            color = 0x55FF0000.toInt()
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }
        val textPaint = Paint().apply {
            color = 0xFFFFFF00.toInt()   // yellow — readable on any bg
            textSize = 22f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val bgPaint = Paint().apply {
            color = 0xCC000000.toInt()   // dark bg under text
            style = Paint.Style.FILL
        }

        val step = (config.gridStep * scale).toInt().coerceAtLeast(1)

        fun drawLabel(text: String, px: Float, py: Float) {
            val tw = textPaint.measureText(text)
            val th = textPaint.textSize
            canvas.drawRect(px, py - th, px + tw + 4f, py + 4f, bgPaint)
            canvas.drawText(text, px + 2f, py, textPaint)
        }

        // vertical lines + X labels
        var x = 0
        while (x <= mutable.width) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), mutable.height.toFloat(), linePaint)
            val origX = capturedRect.left + (x / scale).toInt()
            drawLabel("$origX", x.toFloat(), 24f)
            x += step
        }

        // horizontal lines + Y labels
        var y = 0
        while (y <= mutable.height) {
            canvas.drawLine(0f, y.toFloat(), mutable.width.toFloat(), y.toFloat(), linePaint)
            val origY = capturedRect.top + (y / scale).toInt()
            if (y > 0) drawLabel("$origY", 2f, y.toFloat() - 3f)
            y += step
        }

        return mutable
    }

    // ─────────────────────────────────────────────
    // Screen size
    // ─────────────────────────────────────────────

    data class ScreenSize(val width: Int, val height: Int)

    fun getScreenSize(): ScreenSize? {
        val raw = ShizukuCommandExecutor.getScreenSize()
        val match = Regex("""(\d+)x(\d+)""").find(raw) ?: return null
        return ScreenSize(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt()
        )
    }

    fun buildScreenContext(result: CaptureResult): String = """
[SCREEN INFO]
Device screen size: ${result.originalWidth}x${result.originalHeight}px (USE THESE for all tap/swipe coordinates)
Captured region: (${result.capturedRect.left}, ${result.capturedRect.top}) to (${result.capturedRect.right}, ${result.capturedRect.bottom})
Image resolution: ${result.outputWidth}x${result.outputHeight}px (scaled ${result.scale}x for token efficiency)

IMPORTANT — How to read coordinates from this image:
- The image is scaled to ${(result.scale * 100).toInt()}% of real size
- Grid lines are drawn every ${(100 * result.scale).toInt()}px in the IMAGE = every 100px on the REAL screen
- Each grid label shows the REAL screen coordinate at that line
- To tap something: read the grid label nearest to the element → use that value directly
- Example: element appears between grid lines labeled 500 and 600 → tap x=550 on real screen
- DO NOT multiply or divide coordinates — grid labels are already in real screen pixels
""".trimIndent()

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun newOutFile(): File {
        if (!::capsDir.isInitialized) {
            return File("/sdcard/aixodia_cap_${System.currentTimeMillis()}.png")
        }
        return File(capsDir, "cap_${System.currentTimeMillis()}.png")
    }
}