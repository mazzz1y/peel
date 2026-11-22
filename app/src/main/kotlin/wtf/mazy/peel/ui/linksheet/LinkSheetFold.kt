package wtf.mazy.peel.ui.linksheet

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import kotlin.math.roundToInt
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.webapplist.SearchBar

/**
 * The link sheet's height and its two views of one list. Resting, it is the link: its target and
 * actions over the rows that relate to it, with half the next row showing. Pulled up or
 * searching, it is the web apps alone under their search field, and the drag between them
 * crossfades the two. Without rows to fold, it is as tall as its content; on TV and car screens,
 * which are driven without touch, it [fills] the screen and only searching changes the view.
 */
internal class LinkSheetFold(
    private val behavior: BottomSheetBehavior<FrameLayout>,
    private val root: View,
    private val fills: Boolean,
    private val bar: SearchBar?,
    private val onWebAppsShown: (Boolean) -> Unit = {},
) {

    private val sheet = root.parent as View
    private val header: View = root.requireViewById(R.id.linkSheetHeader)
    private val top: View = root.requireViewById(R.id.linkSheetTop)
    private val apps: View = root.requireViewById(R.id.linkSheetApps)
    private val list: RecyclerView = root.requireViewById(R.id.linkSheetList)

    private var folds = false

    private var resting = 0

    /** The target and actions' height as a drag towards the web apps starts. */
    private var headerHeight = 0

    private val searching: Boolean get() = bar?.isActive == true

    init {
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == STATE_EXPANDED || newState == STATE_COLLAPSED) render()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if (folds && !searching) showWebApps(slideOffset.coerceIn(0f, 1f))
            }
        })
        // A web app's window rotates without being recreated, and the peek follows its rows;
        // changing it relayouts the sheet, so it waits for this pass.
        (sheet.parent as View).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!folds || behavior.state != STATE_COLLAPSED || searching) return@addOnLayoutChangeListener
            val peek = peekHeight() ?: return@addOnLayoutChangeListener
            if (peek != behavior.peekHeight) sheet.post { behavior.peekHeight = peek }
        }
    }

    fun update(resting: Int, rows: Int) {
        this.resting = resting
        folds = !fills && resting < rows
        val height =
            if (folds || fills) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        sheet.updateLayoutParams { this.height = height }
        root.updateLayoutParams { this.height = height }
        behavior.skipCollapsed = !folds
        behavior.state = if (folds) STATE_COLLAPSED else STATE_EXPANDED
        render()
    }

    fun render() {
        behavior.isDraggable = !fills && !searching
        val webApps = searching || (folds && behavior.state == STATE_EXPANDED)
        if (!webApps) list.scrollToPosition(0)
        showWebApps(if (webApps) 1f else 0f)
        onWebAppsShown(webApps)
    }

    private fun peekHeight(): Int? {
        // The list's bottom padding carries the navigation bar's inset, which the actions must clear.
        if (resting == 0) return apps.top + list.paddingBottom
        val next = list.findViewHolderForAdapterPosition(resting)?.itemView ?: return null
        return apps.top + next.top + next.height / 2
    }

    /**
     * Following the drag, the target and actions fade out, then the search field fades in, while
     * the room they share shrinks to the field's and the list rises into it.
     */
    private fun showWebApps(progress: Float) {
        val bar = bar ?: return
        val between = progress > 0f && progress < 1f
        if (between && top.layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            header.measure(
                View.MeasureSpec.makeMeasureSpec(top.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            headerHeight = header.measuredHeight
        }
        header.isVisible = progress < 1f
        header.alpha = (1f - 2f * progress).coerceIn(0f, 1f)
        // The collapsed sheet runs on below the screen, and the first card would show above the
        // navigation bar.
        list.isInvisible = folds && resting == 0 && progress == 0f
        bar.isVisible = progress > 0f
        bar.alpha = (2f * progress - 1f).coerceIn(0f, 1f)
        val barHeight = (bar.layoutParams as ViewGroup.MarginLayoutParams).let { it.height + it.bottomMargin }
        val height =
            if (between) (headerHeight + (barHeight - headerHeight) * progress).roundToInt()
            else ViewGroup.LayoutParams.WRAP_CONTENT
        if (top.layoutParams.height != height) top.updateLayoutParams { this.height = height }
    }
}
