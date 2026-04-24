package wtf.mazy.peel.ui.extensions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.R
import wtf.mazy.peel.util.withBoldSpan
import kotlin.coroutines.resume

object ExtensionPermissionPrompt {

    suspend fun confirm(
        activity: AppCompatActivity,
        title: String,
        @StringRes summaryRes: Int,
        ext: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        showEvenIfEmpty: Boolean,
        @StringRes positiveRes: Int,
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        if (!showEvenIfEmpty && permissions.isEmpty() && origins.isEmpty()) {
            return true
        }
        val name = ext.metaData.name ?: ext.id
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.dialog_extension_install, null)
        view.findViewById<TextView>(R.id.extensionInstallSummary).text =
            activity.getString(summaryRes, name).withBoldSpan(name)

        populateSection(
            inflater = inflater,
            header = view.findViewById(R.id.permissionsHeader),
            container = view.findViewById(R.id.permissionsList),
            items = permissions,
        )
        populateSection(
            inflater = inflater,
            header = view.findViewById(R.id.originsHeader),
            container = view.findViewById(R.id.originsList),
            items = origins,
        )
        if (permissions.isEmpty() && origins.isEmpty()) {
            view.findViewById<TextView>(R.id.noPermissionsText).visibility = View.VISIBLE
        }

        return suspendCancellableCoroutine { cont ->
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(positiveRes) { _, _ ->
                    if (!cont.isCompleted) cont.resume(true)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    if (!cont.isCompleted) cont.resume(false)
                }
                .setOnCancelListener {
                    if (!cont.isCompleted) cont.resume(false)
                }
                .create()
            dialog.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
    }

    private fun populateSection(
        inflater: LayoutInflater,
        header: TextView,
        container: LinearLayout,
        items: Array<String>,
    ) {
        if (items.isEmpty()) return
        header.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
        for (raw in items) {
            val row = inflater.inflate(
                R.layout.item_extension_permission, container, false,
            ) as ViewGroup
            row.findViewById<TextView>(R.id.permissionLabel).text = raw
            container.addView(row)
        }
    }
}
