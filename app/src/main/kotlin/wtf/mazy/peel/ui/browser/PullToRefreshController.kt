package wtf.mazy.peel.ui.browser

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors
import wtf.mazy.peel.model.WebAppSettings

class PullToRefreshController(
    private val layout: SwipeRefreshLayout?,
    private val onRefresh: () -> Unit,
    private val canOverscrollTop: () -> Boolean,
) {
    private var enabledBySettings = false
    private var suspended = false
    private var refreshRequested = false

    init {
        layout?.let { view ->
            view.setOnChildScrollUpCallback { _, _ -> !canOverscrollTop() }
            view.setOnRefreshListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                refreshRequested = true
                onRefresh()
            }
            view.setColorSchemeColors(
                MaterialColors.getColor(
                    view,
                    androidx.appcompat.R.attr.colorPrimary,
                    0,
                )
            )
            view.setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(
                    view,
                    com.google.android.material.R.attr.colorSurface,
                    0,
                )
            )
        }
    }

    fun update(settings: WebAppSettings) {
        enabledBySettings = settings.isPullToRefresh == true
        syncEnabled()
    }

    fun setSuspended(value: Boolean) {
        suspended = value
        syncEnabled()
    }

    fun stopRefreshing() {
        // A page stop unrelated to this gesture must not retire the indicator: the refresh
        // listener only runs after ANIMATE_TO_TRIGGER_DURATION and only while still refreshing,
        // so an early isRefreshing = false cancels the reload outright.
        if (!refreshRequested) return
        refreshRequested = false
        layout?.isRefreshing = false
    }

    private fun syncEnabled() {
        val view = layout ?: return
        refreshRequested = false
        // Disabling only calls reset(), which hides the spinner without clearing the refreshing
        // flag; a later setRefreshing(true) would then be a no-op.
        view.isRefreshing = false
        view.isEnabled = enabledBySettings && !suspended
    }
}
