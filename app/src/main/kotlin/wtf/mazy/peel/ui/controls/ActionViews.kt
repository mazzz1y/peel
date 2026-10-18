package wtf.mazy.peel.ui.controls

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import wtf.mazy.peel.R

/** Builds action buttons and keeps the state shown on them in sync across rebuilds. */
class ActionViews(
    private val parent: ViewGroup,
    private val beforeAction: () -> Unit = {},
) {
    private val context = parent.context
    private val inflater = LayoutInflater.from(context)

    private var translateDot: View? = null
    private var forwardButton: ImageButton? = null
    private var translateActive = false
    private var forwardEnabled = false

    fun create(action: ControlAction): View {
        val button = createButton(action)
        if (action.tag == ControlAction.TAG_FORWARD) {
            button.isEnabled = forwardEnabled
            forwardButton = button
        }
        val wrapper = FrameLayout(context)
        wrapper.addView(
            button,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        if (action.tag == ControlAction.TAG_TRANSLATE) {
            val dot = inflater.inflate(R.layout.view_indicator_dot, wrapper, false)
            dot.visibility = if (translateActive) View.VISIBLE else View.GONE
            wrapper.addView(dot)
            translateDot = dot
        }
        return wrapper
    }

    fun forget() {
        translateDot = null
        forwardButton = null
    }

    fun setTranslateActive(active: Boolean) {
        translateActive = active
        translateDot?.visibility = if (active) View.VISIBLE else View.GONE
    }

    fun setForwardEnabled(enabled: Boolean) {
        forwardEnabled = enabled
        forwardButton?.isEnabled = enabled
    }

    private fun createButton(action: ControlAction): ImageButton {
        val button = inflater.inflate(R.layout.view_floating_action, parent, false) as ImageButton
        button.setImageResource(action.iconRes)
        button.contentDescription = context.getString(action.labelRes)
        button.setOnClickListener {
            beforeAction()
            action.onClick()
        }
        action.onLongClick?.let { longClick ->
            button.setOnLongClickListener {
                button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                beforeAction()
                longClick()
                true
            }
        }
        return button
    }
}
