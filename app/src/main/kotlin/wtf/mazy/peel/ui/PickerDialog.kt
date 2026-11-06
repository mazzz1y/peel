package wtf.mazy.peel.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.common.showIcon
import wtf.mazy.peel.ui.dialog.dismissOnDestroyOf
import wtf.mazy.peel.util.shortLabel

object PickerDialog {
    private const val SEARCH_THRESHOLD = 8

    fun <T> show(
        activity: AppCompatActivity,
        title: CharSequence,
        items: List<T>,
        onPick: (T) -> Unit,
        configure: MaterialAlertDialogBuilder.() -> Unit = {},
        bind: (T, ImageView, TextView, TextView, TextView) -> Unit,
    ): AlertDialog {
        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            setPadding(
                0, resources.getDimensionPixelSize(R.dimen.dialog_list_top_padding), 0, 0,
            )
        }
        var dialog: AlertDialog? = null
        recycler.adapter = PickerRecyclerAdapter(
            items = items,
            onClick = { item ->
                dialog?.dismiss()
                onPick(item)
            },
            bind = bind,
        )
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(recycler)
            .apply(configure)
            .show()
            .dismissOnDestroyOf(activity)
        return dialog
    }

    /**
     * Lets the user choose one of [apps]: a dialog for a few, the full-screen searchable list
     * beyond [SEARCH_THRESHOLD]. [groupTitles] maps group ids to titles for the labels and the
     * search; the dialog labels its rows only when [labelGroupsInDialog] is set.
     */
    fun showWebApps(
        activity: AppCompatActivity,
        title: CharSequence,
        apps: List<WebApp>,
        groupTitles: Map<String, String>,
        labelGroupsInDialog: Boolean,
        onPick: (WebApp) -> Unit,
        onDismiss: () -> Unit,
        onIncognito: (() -> Unit)? = null,
    ) {
        if (apps.size > SEARCH_THRESHOLD) {
            WebAppPickerSurface(activity, apps, groupTitles, onPick, onIncognito).run {
                setOnDismissListener { onDismiss() }
                show()
                dismissOnDestroyOf(activity)
            }
            return
        }
        val ungrouped = activity.getString(R.string.ungrouped)
        val dialog = show(
            activity = activity,
            title = title,
            items = apps,
            onPick = onPick,
            configure = {
                onIncognito?.let { setNeutralButton(R.string.incognito) { _, _ -> it() } }
                setOnDismissListener { onDismiss() }
            },
        ) { webApp, icon, name, label, _ ->
            name.text = webApp.title
            icon.showIcon(webApp.resolveIcon())
            if (labelGroupsInDialog) {
                label.text = webApp.groupUuid?.let(groupTitles::get)?.let(::shortLabel) ?: ungrouped
                label.visibility = View.VISIBLE
            }
        }
        if (onIncognito != null) {
            (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) as? MaterialButton)
                ?.setIconResource(R.drawable.ic_symbols_visibility_off_24)
        }
    }
}
