package wtf.mazy.peel.shortcut

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.text.BreakIterator
import kotlin.math.abs
import wtf.mazy.peel.util.App

object LetterIconGenerator {

    private val COLORS =
        intArrayOf(
            0xFFE57373.toInt(),
            0xFFF06292.toInt(),
            0xFFBA68C8.toInt(),
            0xFF9575CD.toInt(),
            0xFF7986CB.toInt(),
            0xFF64B5F6.toInt(),
            0xFF4FC3F7.toInt(),
            0xFF4DD0E1.toInt(),
            0xFF4DB6AC.toInt(),
            0xFF81C784.toInt(),
            0xFFAED581.toInt(),
            0xFFFF8A65.toInt(),
            0xFFA1887F.toInt(),
            0xFF90A4AE.toInt(),
        )

    fun generate(title: String, url: String, sizePx: Int, textRatio: Float = 0.45f): Bitmap {
        val glyph = extractGlyph(title, url)
        val isEmoji = glyph.length > 1 || (glyph.isNotEmpty() && !glyph[0].isLetterOrDigit())
        val color = pickColor(url.ifEmpty { title })

        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)

        val circlePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val radius = sizePx / 2f
        canvas.drawCircle(cx, cy, radius, circlePaint)

        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = sizePx * if (isEmoji) textRatio * 1.2f else textRatio
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(glyph, cx, textY, textPaint)

        return bitmap
    }

    fun generateForAdaptiveIcon(title: String, url: String): Bitmap {
        val density = App.appContext.resources.displayMetrics.density
        val iconSizePx = (108 * density).toInt()
        return generate(title, url, iconSizePx, textRatio = 0.3f)
    }

    private fun extractGlyph(title: String, url: String): String {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) {
            val first = firstGrapheme(trimmed)
            val cp = first.codePointAt(0)
            if (!Character.isLetterOrDigit(cp)) return first
            return first[0].uppercase()
        }

        val domain = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val domainLetter = domain.firstOrNull { it.isLetterOrDigit() }
        if (domainLetter != null) return domainLetter.uppercase()

        return "?"
    }

    private fun firstGrapheme(text: String): String {
        val iter = BreakIterator.getCharacterInstance()
        iter.setText(text)
        return text.substring(0, iter.next())
    }

    private fun pickColor(key: String): Int {
        val index = abs(key.hashCode()) % COLORS.size
        return COLORS[index]
    }
}
