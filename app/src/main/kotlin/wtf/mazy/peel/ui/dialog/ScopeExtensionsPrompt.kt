package wtf.mazy.peel.ui.dialog

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import wtf.mazy.peel.R
import wtf.mazy.peel.util.withBoldSpan
import kotlin.coroutines.resume

object ScopeExtensionsPrompt {

    suspend fun confirm(
        activity: AppCompatActivity,
        appName: String,
        domains: List<String>,
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.dialog_scope_extensions, null)
        view.findViewById<TextView>(R.id.scopeExtensionsSummary).text =
            activity.getString(R.string.scope_extensions_message, appName).withBoldSpan(appName)

        val container = view.findViewById<LinearLayout>(R.id.scopeExtensionsList)
        for (domain in domains) {
            val row = inflater.inflate(
                R.layout.item_extension_permission, container, false,
            ) as ViewGroup
            row.findViewById<TextView>(R.id.permissionLabel).text = domain
            container.addView(row)
        }

        return suspendCancellableCoroutine { cont ->
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.scope_extensions_title)
                .setView(view)
                .setPositiveButton(R.string.add) { _, _ ->
                    if (!cont.isCompleted) cont.resume(true)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    if (!cont.isCompleted) cont.resume(false)
                }
                .setOnCancelListener {
                    if (!cont.isCompleted) cont.resume(false)
                }
                .create()
                .dismissOnDestroyOf(activity)
            dialog.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
    }
}
