package wtf.mazy.peel.ui.webapplist

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.floatingtoolbar.FloatingToolbarLayout
import wtf.mazy.peel.R

/**
 * The persistent search field on the list screen's bottom line.
 *
 * The field is the real one: tapping the bar puts the caret where the user tapped rather than
 * opening a second surface. Search is active exactly while focus is somewhere in the bar or in
 * its [sessionExtent] — the session belongs to the bar rather than to the field, because the
 * leading and clear controls are D-pad targets of their own, and to the results it fills, so
 * travelling into a result row does not end what the row is there for. Everything else follows
 * from that one predicate: the leading icon is a magnifier while idle and a back control while
 * active, and leaving the session discards the query.
 *
 * The keyboard is the host's: raising and hiding it needs the window, which a view has no
 * business reaching for.
 */
class SearchBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FloatingToolbarLayout(context, attrs, defStyleAttr) {

    var onActiveChanged: (Boolean) -> Unit = {}
    var onQueryChanged: (String) -> Unit = {}

    val query: String get() = queryField.text.toString()

    /** A view outside the bar whose focus still counts as the bar's session, such as its results. */
    var sessionExtent: View? = null

    val isActive: Boolean get() = hasFocus() || sessionExtent?.hasFocus() == true

    private val queryField: EditText
    private val leadingButton: ImageButton
    private val clearButton: ImageButton

    private var lastActive = false
    private var keyboardSeen = false

    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        val active = isActive
        if (active != lastActive) {
            lastActive = active
            if (active) keyboardSeen = false else queryField.setText("")
            renderControls()
            onActiveChanged(active)
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_search_bar, this, true)
        queryField = findViewById(R.id.searchBarField)
        leadingButton = findViewById(R.id.searchBarLeading)
        clearButton = findViewById(R.id.searchBarClear)

        queryField.doAfterTextChanged {
            renderControls()
            onQueryChanged(query)
        }
        // The results are live as the query is typed; the action key has nothing to submit, and
        // consuming it keeps the keyboard where it is.
        queryField.setOnEditorActionListener { _, actionId, _ ->
            actionId == EditorInfo.IME_ACTION_SEARCH
        }
        // The bar is the field's touch surface, not a control of its own: a tap anywhere on it
        // lands in the field. It stays out of the accessibility tree so a screen reader reaches
        // one labelled target instead of a button wrapping a field.
        setOnClickListener { focusQuery() }
        leadingButton.setOnClickListener { close() }
        // The caret moves back to the field first: emptying the query hides this button, and a
        // focused view going away hands focus wherever the hierarchy pleases.
        clearButton.setOnClickListener {
            focusQuery()
            queryField.setText("")
        }
        // The keyboard swallows a system back before the activity sees it, so a dismissed
        // keyboard is the end of the session however it was dismissed — one press, one exit.
        // Hidden-keyboard insets before the keyboard has shown once (a closing dialog's, say)
        // are the old state, not a dismissal.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val imeShown = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeShown) keyboardSeen = true
            if (!imeShown && keyboardSeen) close()
            insets
        }
        renderControls()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        super.onDetachedFromWindow()
    }

    fun focusQuery() {
        queryField.requestFocus()
        queryField.setSelection(queryField.text.length)
    }

    fun setQuery(query: String) {
        queryField.setText(query)
        queryField.setSelection(query.length)
    }

    fun close() {
        if (hasFocus()) clearFocus()
        sessionExtent?.takeIf { it.hasFocus() }?.clearFocus()
    }

    /**
     * Drops a session the view hierarchy brought back with it. Both the query and the focus are
     * restored by id, and neither is suppressible from the layout, so a recreated screen has to
     * be told that the session before it did not survive.
     */
    fun reset() {
        close()
        queryField.setText("")
        // The focus watcher is not listening before the bar is attached, so the baseline it
        // compares against is set here, or the first focus event would report a stale change.
        lastActive = isActive
        renderControls()
    }

    private fun renderControls() {
        val active = isActive
        leadingButton.setImageResource(
            if (active) R.drawable.ic_symbols_arrow_back_24 else R.drawable.ic_symbols_search_24,
        )
        leadingButton.contentDescription =
            context.getString(if (active) R.string.back else R.string.search)
        leadingButton.isClickable = active
        leadingButton.isFocusable = active
        leadingButton.importantForAccessibility =
            if (active) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        clearButton.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
    }
}
