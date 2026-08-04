package wtf.mazy.peel.ui.controls

import android.view.MotionEvent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import wtf.mazy.peel.R

data class ControlAction(
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val labelRes: Int,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val tag: String? = null,
) {
    companion object {
        const val TAG_TRANSLATE = "translate"
    }
}

class ControlActions(
    private val onHome: (() -> Unit)? = null,
    private val onReload: () -> Unit,
    private val onReloadLongPress: (() -> Unit)? = null,
    private val onShare: () -> Unit,
    private val onShareLongPress: (() -> Unit)? = null,
    private val onFind: (() -> Unit)? = null,
    private val onTranslate: (() -> Unit)? = null,
    private val onTranslateLongPress: (() -> Unit)? = null,
    private val onExtensions: (() -> Unit)? = null,
) {
    fun toList(): List<ControlAction> = buildList {
        onHome?.let {
            add(
                ControlAction(
                    R.drawable.ic_symbols_home_wght300_24,
                    R.string.browser_controls_home,
                    it
                )
            )
        }
        add(
            ControlAction(
                R.drawable.ic_symbols_share_wght300_24,
                R.string.share,
                onShare,
                onShareLongPress,
            ),
        )
        onFind?.let {
            add(
                ControlAction(
                    R.drawable.ic_symbols_search_wght300_24,
                    R.string.find_in_page_hint,
                    it
                )
            )
        }
        add(
            ControlAction(
                R.drawable.ic_symbols_refresh_wght300_24,
                R.string.browser_controls_reload,
                onReload,
                onReloadLongPress,
            ),
        )
        onTranslate?.let {
            add(
                ControlAction(
                    R.drawable.ic_symbols_translate_wght300_24,
                    R.string.translate_action,
                    it,
                    onLongClick = onTranslateLongPress,
                    tag = ControlAction.TAG_TRANSLATE,
                ),
            )
        }
        onExtensions?.let {
            add(ControlAction(R.drawable.ic_symbols_extension_wght300_24, R.string.extensions, it))
        }
    }
}

interface BrowserControls {
    fun remove()
    fun setHidden(hidden: Boolean)
    fun setTranslateActive(active: Boolean)
    fun setIncognito(active: Boolean)
    fun onContentScrolled(scrollY: Int) = Unit
    fun onHostTouchEvent(event: MotionEvent): Boolean = false
    fun reservedBottomHeight(): Int = 0
}
