package wtf.mazy.peel.ui.webapplist

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.common.fadeVisibility

/** An action search offers for a URL-shaped query, ahead of the results. */
enum class UrlSuggestion { ADD, OPEN_PRIVATE }

/**
 * The rows for the [UrlSuggestion]s on offer. Which are offered is the search controller's
 * call; where the strip sits is the layout's.
 */
class UrlSuggestionStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onSuggestion: (UrlSuggestion) -> Unit = {}

    var onOfferingChanged: () -> Unit = {}

    var animated = true

    /** The target state; unlike visibility, already false while the strip fades out. */
    var isOffering = false
        private set

    /** The height of the rows alone, without the rest the strip pads around them. */
    val rowsHeight: Int get() = height - paddingTop - paddingBottom

    private val rows: Map<UrlSuggestion, View>

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_url_suggestions, this, true)
        rows = mapOf(
            UrlSuggestion.ADD to findViewById(R.id.suggestionAdd),
            UrlSuggestion.OPEN_PRIVATE to findViewById(R.id.suggestionPrivate),
        )
        rows.forEach { (suggestion, row) -> row.setOnClickListener { onSuggestion(suggestion) } }
        isVisible = false
    }

    // Rows are left as they are while the strip fades out, so they do not thin under its fade.
    fun render(offered: Set<UrlSuggestion>) {
        val showing = offered.isNotEmpty()
        if (showing) {
            rows.forEach { (suggestion, row) ->
                row.fadeVisibility(visible = suggestion in offered, animated = animated && isOffering)
            }
        }
        if (showing == isOffering) return
        isOffering = showing
        fadeVisibility(visible = showing, animated = animated)
        onOfferingChanged()
    }
}
