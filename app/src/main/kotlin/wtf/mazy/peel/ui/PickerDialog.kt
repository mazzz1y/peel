package wtf.mazy.peel.ui

import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R

object PickerDialog {
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
        return dialog
    }
}
