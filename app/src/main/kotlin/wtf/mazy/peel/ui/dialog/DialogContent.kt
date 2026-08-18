package wtf.mazy.peel.ui.dialog

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.textview.MaterialTextView
import wtf.mazy.peel.R

class DialogContent private constructor(
    private val context: Context,
    private val contentColumn: LinearLayout,
    private val root: View,
) {
    val view: View get() = root

    val column: LinearLayout get() = contentColumn

    fun message(text: CharSequence): DialogContent = apply {
        val message = MaterialTextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setTextAppearance(bodyTextAppearance())
            setTextColor(bodyTextColor())
            this.text = text
        }
        add(message)
    }

    fun add(child: View): DialogContent = apply {
        if (contentColumn.childCount > 0) {
            (child.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )).let { lp ->
                lp.topMargin = gap
                child.layoutParams = lp
            }
        }
        contentColumn.addView(child)
    }

    private val gap: Int
        get() = context.resources.getDimensionPixelSize(R.dimen.dialog_content_gap)

    private fun bodyTextAppearance(): Int = resolveAttr(
        com.google.android.material.R.attr.textAppearanceBodyMedium
    )

    private fun bodyTextColor(): Int {
        val ta = context.obtainStyledAttributes(
            intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    private fun resolveAttr(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val res = ta.getResourceId(0, 0)
        ta.recycle()
        return res
    }

    companion object {
        fun of(context: Context): DialogContent {
            val horizontalPadding = run {
                val ta = context.obtainStyledAttributes(
                    intArrayOf(android.R.attr.dialogPreferredPadding)
                )
                val padding = ta.getDimensionPixelSize(0, 0)
                ta.recycle()
                padding
            }
            val topPadding =
                context.resources.getDimensionPixelSize(R.dimen.dialog_content_top_padding)

            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val scroll = ScrollView(context).apply {
                setPadding(horizontalPadding, topPadding, horizontalPadding, 0)
                clipToPadding = false
                addView(column)
            }
            return DialogContent(context, column, scroll)
        }
    }
}
