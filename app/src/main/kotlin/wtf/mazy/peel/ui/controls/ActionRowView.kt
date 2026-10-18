package wtf.mazy.peel.ui.controls

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import wtf.mazy.peel.R

abstract class ActionRowView(
    protected val root: ActionRowLayout,
) : BrowserControls {

    protected val context: Context = root.context
    protected val inflater: LayoutInflater = LayoutInflater.from(context)
    protected var destroyed = false
    private val defaultBackgroundTint = root.backgroundTintList
    private val actionViews = ActionViews(root)

    override fun setTranslateActive(active: Boolean) = actionViews.setTranslateActive(active)

    override fun setForwardEnabled(enabled: Boolean) = actionViews.setForwardEnabled(enabled)

    override fun setIncognito(active: Boolean) {
        root.backgroundTintList = if (active) {
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.incognito_fab))
        } else {
            defaultBackgroundTint
        }
    }

    protected fun populate(actions: List<ControlAction>) {
        actionViews.forget()
        root.setItems(
            actions.map { action ->
                ActionRowLayout.Item(
                    slot = actionViews.create(action),
                    divider = if (action.dividerAfter) {
                        inflater.inflate(R.layout.view_controls_divider_vertical, root, false)
                    } else {
                        null
                    },
                )
            },
        )
    }
}
