package wtf.mazy.peel.ui.browser

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.max
import kotlin.math.min
import wtf.mazy.peel.util.isAutomotiveHost

object AutomotiveDisplayLayout {
    private const val WINDOW_ULTRAWIDE_ASPECT = 21f / 9f
    private const val PHYSICAL_ULTRAWIDE_ASPECT = 20f / 9f

    enum class Layout {
        PORTRAIT,
        ULTRAWIDE,
        HORIZONTAL,
    }

    fun detect(context: Context): Layout {
        val (windowW, windowH) = windowBoundsPx(context)
        if (windowH > windowW) return Layout.PORTRAIT

        val windowAspect = windowW.toFloat() / windowH.toFloat()
        if (windowAspect >= WINDOW_ULTRAWIDE_ASPECT) return Layout.ULTRAWIDE

        if (context.isAutomotiveHost() && physicalAspectRatio(context) >= PHYSICAL_ULTRAWIDE_ASPECT) {
            return Layout.ULTRAWIDE
        }

        return Layout.HORIZONTAL
    }

    private fun windowBoundsPx(context: Context): Pair<Int, Int> {
        val activity = context as? Activity
        if (activity != null && Build.VERSION.SDK_INT >= 30) {
            val bounds = activity.windowManager.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val decor = activity?.window?.decorView
        if (decor != null && decor.width > 0 && decor.height > 0) {
            return decor.width to decor.height
        }
        val dm: DisplayMetrics = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun physicalAspectRatio(context: Context): Float {
        val (longPx, shortPx) = physicalLongShortPx(context)
        return longPx.toFloat() / shortPx.coerceAtLeast(1).toFloat()
    }

    private fun physicalLongShortPx(context: Context): Pair<Int, Int> {
        val candidates = mutableListOf<Pair<Int, Int>>()
        if (context.isAutomotiveHost()) {
            candidates += allConnectedDisplayModeSizes(context)
            defaultDisplayModeSize(context)?.let { candidates += it }
        }
        val activity = context as? Activity
        val display: Display? = activity?.display
            ?: if (Build.VERSION.SDK_INT >= 30) context.display else null
        if (display != null && Build.VERSION.SDK_INT >= 23) {
            val mode = display.mode
            if (mode.physicalWidth > 0 && mode.physicalHeight > 0) {
                candidates += mode.physicalWidth to mode.physicalHeight
            }
        }
        if (activity != null && Build.VERSION.SDK_INT >= 30) {
            val bounds = activity.windowManager.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                candidates += bounds.width() to bounds.height()
            }
        }
        val dm = context.resources.displayMetrics
        candidates += dm.widthPixels to dm.heightPixels

        return candidates
            .map { (w, h) -> max(w, h) to min(w, h) }
            .maxByOrNull { (long, short) -> long.toFloat() / short.toFloat() }
            ?: run {
                max(dm.widthPixels, dm.heightPixels) to min(dm.widthPixels, dm.heightPixels)
            }
    }

    private fun allConnectedDisplayModeSizes(context: Context): List<Pair<Int, Int>> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return emptyList()
        if (Build.VERSION.SDK_INT < 23) return emptyList()

        val out = mutableListOf<Pair<Int, Int>>()
        for (display in displayManager.displays) {
            appendDisplayModes(display, out)
        }
        return out
    }

    private fun defaultDisplayModeSize(context: Context): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < 23) return null
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return null
        val primary = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val scratch = mutableListOf<Pair<Int, Int>>()
        appendDisplayModes(primary, scratch)
        return scratch.maxByOrNull { (w, h) -> max(w, h).toFloat() / min(w, h).toFloat() }
    }

    private fun appendDisplayModes(display: Display, out: MutableList<Pair<Int, Int>>) {
        if (Build.VERSION.SDK_INT < 23) return
        val modes = display.supportedModes
        if (modes != null && modes.isNotEmpty()) {
            for (mode in modes) {
                if (mode.physicalWidth > 0 && mode.physicalHeight > 0) {
                    out += mode.physicalWidth to mode.physicalHeight
                }
            }
        } else {
            val mode = display.mode
            if (mode.physicalWidth > 0 && mode.physicalHeight > 0) {
                out += mode.physicalWidth to mode.physicalHeight
            }
        }
    }
}
