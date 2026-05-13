package wtf.mazy.peel.ui.extensions

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.ui.common.Theming

object ExtensionPickerDialog {
    fun show(activity: AppCompatActivity, sessionActions: SessionExtensionActions) {
        val entries = sessionActions.snapshot()
        if (entries.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.extensions)
                .setMessage(R.string.no_extensions_installed)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val defaultBadgeBg = Theming.colorPrimary(activity)
        val defaultBadgeFg = Theming.colorOnPrimary(activity)

        PickerDialog.show(
            activity = activity,
            title = activity.getString(R.string.extensions),
            items = entries,
            onPick = { it.clickable.click() },
            configure = { setNegativeButton(R.string.cancel, null) },
        ) { entry, icon, name, badge, _ ->
            val actionTitle = entry.display.title?.takeIf { it.isNotBlank() }
            val extName = entry.extension.metaData.name ?: entry.extension.id
            name.text = actionTitle ?: extName
            ExtensionIconCache.bind(icon, activity, entry.extension.id, extName)
            bindBadge(badge, entry.display, defaultBadgeBg, defaultBadgeFg)
        }
    }

    private fun bindBadge(
        view: TextView,
        action: WebExtension.Action,
        defaultBg: Int,
        defaultFg: Int,
    ) {
        val text = action.badgeText?.takeIf { it.isNotBlank() }
        if (text == null) {
            view.background = null
            view.setPadding(0, 0, 0, 0)
            view.visibility = View.GONE
            return
        }
        view.text = text
        view.setTextColor(action.badgeTextColor ?: defaultFg)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Float.MAX_VALUE
            setColor(action.badgeBackgroundColor ?: defaultBg)
        }
        val padH = view.dp(6f).toInt()
        val padV = view.dp(2f).toInt()
        view.setPadding(padH, padV, padH, padV)
        view.visibility = View.VISIBLE
    }

    private fun View.dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
