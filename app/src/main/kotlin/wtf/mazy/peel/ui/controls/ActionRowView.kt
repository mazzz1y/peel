package wtf.mazy.peel.ui.controls

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import wtf.mazy.peel.R

abstract class ActionRowView(
    protected val root: LinearLayout,
) : BrowserControls {

    protected val context: Context = root.context
    protected val inflater: LayoutInflater = LayoutInflater.from(context)
    protected var destroyed = false
    private val defaultBackgroundTint = root.backgroundTintList

    private var translateActiveDot: View? = null
    private var translateActive = false

    override fun setTranslateActive(active: Boolean) {
        if (translateActive == active) return
        translateActive = active
        translateActiveDot?.visibility = if (active) View.VISIBLE else View.GONE
    }

    override fun setIncognito(active: Boolean) {
        root.backgroundTintList = if (active) {
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.incognito_fab))
        } else {
            defaultBackgroundTint
        }
    }

    protected fun populate(
        actions: List<ControlAction>,
        layoutParams: (index: Int) -> LinearLayout.LayoutParams,
    ) {
        root.removeAllViews()
        translateActiveDot = null
        actions.forEachIndexed { index, action ->
            root.addView(createActionView(action), layoutParams(index))
        }
    }

    private fun createActionView(action: ControlAction): View {
        val btn = createActionButton(action)
        val wrapper = FrameLayout(context)
        wrapper.addView(
            btn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        if (action.tag == ControlAction.TAG_TRANSLATE) {
            val dot = inflater.inflate(R.layout.view_indicator_dot, wrapper, false)
            dot.visibility = if (translateActive) View.VISIBLE else View.GONE
            wrapper.addView(dot)
            translateActiveDot = dot
        }
        return wrapper
    }

    private fun createActionButton(action: ControlAction): ImageButton {
        val btn = inflater.inflate(R.layout.view_floating_action, root, false) as ImageButton
        btn.setImageResource(action.iconRes)
        btn.contentDescription = context.getString(action.labelRes)
        btn.setOnClickListener { action.onClick() }
        action.onLongClick?.let { longClick ->
            btn.setOnLongClickListener {
                btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                longClick()
                true
            }
        }
        return btn
    }
}
