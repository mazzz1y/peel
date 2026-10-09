package wtf.mazy.peel.ui.common

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import wtf.mazy.peel.R
import kotlin.math.roundToInt

/**
 * Backs a line floating over a list with a fade of the page colour: transparent at the line's
 * top, the page colour [R.dimen.bottom_line_fade] lower, and held there to the line's bottom. The fade is the line's top padding, so the controls stand on the page colour and
 * rows dissolve on their way under them; by the controls nothing of a row is left. The fade
 * shows only with the bar, so its alpha is animatable.
 *
 * The ramp is a smoothstep, not linear: a linear ramp arrives at each end at full slope and
 * stops dead, and the eye sharpens that kink into a line (a Mach band) wherever the ramp is
 * short enough for the slope to be steep. Easing in and out lands at both ends with no slope,
 * so there is nothing for the eye to find.
 */
fun View.applyBottomLineFade() {
    background = BottomLineFadeDrawable(
        color = MaterialColors.getColor(this, MaterialR.attr.colorSurfaceContainerLow),
        length = resources.getDimension(R.dimen.bottom_line_fade),
    )
}

private class BottomLineFadeDrawable(color: Int, private val length: Float) : Drawable() {

    private val paint = Paint()
    private val stops = FloatArray(STEPS + 1) { it.toFloat() / STEPS }
    private val colors = IntArray(STEPS + 1) { i ->
        val t = stops[i]
        val eased = t * t * (3f - 2f * t)
        ColorUtils.setAlphaComponent(color, (eased * 255f).roundToInt())
    }

    override fun onBoundsChange(bounds: Rect) {
        val top = bounds.top.toFloat()
        paint.shader = LinearGradient(0f, top, 0f, top + length, colors, stops, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) = canvas.drawRect(bounds, paint)

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val STEPS = 16
    }
}
