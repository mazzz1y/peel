package wtf.mazy.peel.ui.browser

import android.content.Context
import wtf.mazy.peel.R
import wtf.mazy.peel.util.isAutomotiveHost

/**
 * Fixed safe-zone fallback insets for AAOS shell UI when system horizontal insets are zero.
 * PWA fullscreen (immersive) uses edge-to-edge content with no safe-zone margins.
 */
object AutomotiveSafeZoneInsets {
    data class Px(
        val start: Int,
        val top: Int,
        val end: Int,
        val bottom: Int,
        val layout: AutomotiveDisplayLayout.Layout,
    )

    fun toPx(context: Context): Px {
        val res = context.resources
        val layout = AutomotiveDisplayLayout.detect(context)
        val endRes = when (layout) {
            AutomotiveDisplayLayout.Layout.PORTRAIT ->
                R.dimen.automotive_safe_zone_end_portrait
            AutomotiveDisplayLayout.Layout.ULTRAWIDE ->
                R.dimen.automotive_safe_zone_end_ultrawide
            AutomotiveDisplayLayout.Layout.HORIZONTAL ->
                R.dimen.automotive_safe_zone_end_horizontal
        }
        return Px(
            start = res.getDimensionPixelSize(R.dimen.automotive_safe_zone_start),
            top = res.getDimensionPixelSize(R.dimen.automotive_safe_zone_top),
            end = res.getDimensionPixelSize(endRes),
            bottom = res.getDimensionPixelSize(R.dimen.automotive_safe_zone_bottom),
            layout = layout,
        )
    }
}
