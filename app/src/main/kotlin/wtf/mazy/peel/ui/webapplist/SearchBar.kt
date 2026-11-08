package wtf.mazy.peel.ui.webapplist

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.floatingtoolbar.FloatingToolbarLayout
import wtf.mazy.peel.R

/**
 * The persistent search field on a bottom line: the list screen's, and the picker's in the
 * Bottom search position.
 *
 * The field is the real one: tapping the bar puts the caret where the user tapped rather than
 * opening a second surface. The session starts when focus enters the bar and lasts until
 * [close]; focus and the keyboard may come and go within it, so lowering the keyboard to look
 * at the results, or leaving the screen and returning, keeps the query. The leading icon is a
 * magnifier while idle and a back control while active, and ending the session discards the
 * query.
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
    var onKeyboardRequested: () -> Unit = {}
    var onSearch: () -> Unit = {}

    val query: String get() = queryField.text.toString()

    var isActive: Boolean = false
        private set

    private val queryField: EditText
    private val leadingButton: ImageButton
    private val clearButton: ImageButton

    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        if (hasFocus()) setActive(true)
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
        // The results are live as the query is typed, so the action key submits nothing of its
        // own; consuming it keeps the keyboard where it is.
        queryField.setOnEditorActionListener { _, actionId, event ->
            val submitted = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_NULL && event?.action == KeyEvent.ACTION_DOWN
            submitted.also { if (it) onSearch() }
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
        onKeyboardRequested()
    }

    fun setQuery(query: String) {
        queryField.setText(query)
        queryField.setSelection(query.length)
    }

    fun dropFocus() {
        if (hasFocus()) clearFocus()
    }

    fun close() {
        dropFocus()
        setActive(false)
    }

    /**
     * Drops a session the view hierarchy brought back with it. Both the query and the focus are
     * restored by id, and neither is suppressible from the layout, so a recreated screen has to
     * be told that the session before it did not survive.
     */
    fun reset() {
        dropFocus()
        isActive = false
        queryField.setText("")
        renderControls()
    }

    private fun setActive(active: Boolean) {
        if (active == isActive) return
        isActive = active
        if (!active) queryField.setText("")
        renderControls()
        onActiveChanged(active)
    }

    private fun renderControls() {
        leadingButton.setImageResource(
            if (isActive) R.drawable.ic_symbols_arrow_back_24 else R.drawable.ic_symbols_search_24,
        )
        leadingButton.contentDescription =
            context.getString(if (isActive) R.string.back else R.string.search)
        leadingButton.isClickable = isActive
        leadingButton.isFocusable = isActive
        leadingButton.importantForAccessibility =
            if (isActive) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO
        clearButton.visibility = if (query.isEmpty()) GONE else VISIBLE
    }
}
