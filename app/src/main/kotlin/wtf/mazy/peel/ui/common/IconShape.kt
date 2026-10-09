package wtf.mazy.peel.ui.common

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import kotlin.math.max
import kotlin.math.roundToInt

// The iOS app-icon proportion, as measured on the Zed editor logo.
private const val CORNER_RATIO = 0.225f

// Sampled just inside the edge so a baked-in border of a pixel or two does not read as
// transparent corners; anything rounded at over ~14% of its side still does.
private const val CORNER_INSET = 0.04f
private const val OPAQUE_ALPHA = 240

/**
 * Shows a resolved icon, rounding the corners of an opaque square tile so it sits beside the
 * circular letter icons and pre-rounded icons as one family. Any other icon is drawn as it is.
 * The radius is in bitmap pixels, so it scales with the image whatever size the view draws it.
 */
fun ImageView.showIcon(bitmap: Bitmap) {
    if (bitmap.isOpaqueSquare) {
        setImageDrawable(
            RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                cornerRadius = bitmap.width * CORNER_RATIO
            },
        )
    } else {
        setImageBitmap(bitmap)
    }
}

private val Bitmap.isOpaqueSquare: Boolean
    get() {
        if (width != height) return false
        val inset = max(1, (width * CORNER_INSET).roundToInt())
        val far = width - 1 - inset
        if (far <= inset) return false
        return listOf(inset to inset, far to inset, inset to far, far to far).all { (x, y) ->
            Color.alpha(getPixel(x, y)) >= OPAQUE_ALPHA
        }
    }
