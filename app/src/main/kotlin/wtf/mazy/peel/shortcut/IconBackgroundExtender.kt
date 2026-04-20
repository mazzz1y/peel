package wtf.mazy.peel.shortcut

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.get

object IconBackgroundExtender {

    private const val ALPHA_THRESHOLD = 180
    private const val EDGE_SAMPLE_RATIO = 0.05f
    private const val SAMPLE_COUNT = 16

    fun trimTransparentEdges(source: Bitmap): Bitmap? {
        val w = source.width
        val h = source.height
        if (w == 0 || h == 0) return null

        val top = (0 until h).firstOrNull { rowHasOpaque(source, it, w) } ?: return null
        val bottom = (h - 1 downTo top).first { rowHasOpaque(source, it, w) }
        val left = (0 until w).first { colHasOpaque(source, it, top, bottom) }
        val right = (w - 1 downTo left).first { colHasOpaque(source, it, top, bottom) }

        if (top == 0 && left == 0 && right == w - 1 && bottom == h - 1) return source
        return Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
    }

    fun sampleBackgroundColor(trimmed: Bitmap): Int {
        val w = trimmed.width
        val h = trimmed.height
        val offset = (minOf(w, h) * EDGE_SAMPLE_RATIO).toInt().coerceAtLeast(1)
        val samples = buildList {
            addAll(collectAlong(w) { trimmed[it, offset] })
            addAll(collectAlong(w) { trimmed[it, h - 1 - offset] })
            addAll(collectAlong(h) { trimmed[offset, it] })
            addAll(collectAlong(h) { trimmed[w - 1 - offset, it] })
        }
        if (samples.isEmpty()) return Color.WHITE
        val r = samples.map { Color.red(it) }.sorted()[samples.size / 2]
        val g = samples.map { Color.green(it) }.sorted()[samples.size / 2]
        val b = samples.map { Color.blue(it) }.sorted()[samples.size / 2]
        return Color.rgb(r, g, b)
    }

    fun drawEdgeStretch(
        canvas: Canvas,
        scaledLogo: Bitmap,
        logoLeft: Int,
        logoTop: Int,
        logoWidth: Int,
        logoHeight: Int,
        canvasSize: Int,
    ) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val logoRight = logoLeft + logoWidth
        val logoBottom = logoTop + logoHeight
        val srcW = scaledLogo.width
        val srcH = scaledLogo.height

        fun stretch(src: Rect, dst: Rect) = canvas.drawBitmap(scaledLogo, src, dst, paint)

        if (logoTop > 0) stretch(
            Rect(0, 0, srcW, 1), Rect(logoLeft, 0, logoRight, logoTop),
        )
        if (logoBottom < canvasSize) stretch(
            Rect(0, srcH - 1, srcW, srcH), Rect(logoLeft, logoBottom, logoRight, canvasSize),
        )
        if (logoLeft > 0) {
            stretch(Rect(0, 0, 1, srcH), Rect(0, logoTop, logoLeft, logoBottom))
            stretch(Rect(0, 0, 1, 1), Rect(0, 0, logoLeft, logoTop))
            stretch(Rect(0, srcH - 1, 1, srcH), Rect(0, logoBottom, logoLeft, canvasSize))
        }
        if (logoRight < canvasSize) {
            stretch(Rect(srcW - 1, 0, srcW, srcH), Rect(logoRight, logoTop, canvasSize, logoBottom))
            stretch(Rect(srcW - 1, 0, srcW, 1), Rect(logoRight, 0, canvasSize, logoTop))
            stretch(
                Rect(srcW - 1, srcH - 1, srcW, srcH),
                Rect(logoRight, logoBottom, canvasSize, canvasSize),
            )
        }
    }

    private inline fun collectAlong(length: Int, pixelAt: (Int) -> Int): List<Int> {
        val step = (length / SAMPLE_COUNT).coerceAtLeast(1)
        return (0 until length step step)
            .map(pixelAt)
            .filter { Color.alpha(it) > ALPHA_THRESHOLD }
    }

    private fun rowHasOpaque(bitmap: Bitmap, y: Int, width: Int): Boolean =
        (0 until width).any { Color.alpha(bitmap[it, y]) > ALPHA_THRESHOLD }

    private fun colHasOpaque(bitmap: Bitmap, x: Int, top: Int, bottom: Int): Boolean =
        (top..bottom).any { Color.alpha(bitmap[x, it]) > ALPHA_THRESHOLD }
}
