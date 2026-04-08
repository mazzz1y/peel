package wtf.mazy.peel.ui.extensions

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.ListPickerAdapter

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

        val pickerAdapter = ListPickerAdapter(entries) { entry, icon, name, detail ->
            val actionTitle = entry.display.title?.takeIf { it.isNotBlank() }
            val extName = entry.extension.metaData.name ?: entry.extension.id
            name.text = actionTitle ?: extName
            detail.visibility = View.GONE
            ExtensionIconCache.bind(icon, activity, entry.extension.id, extName)
        }

        var dialog: androidx.appcompat.app.AlertDialog? = null
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.extensions)
            .setAdapter(pickerAdapter) { _, position ->
                dialog?.dismiss()
                entries[position].clickable.click()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
