package wtf.mazy.peel.ui.common

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.HostIdentity
import wtf.mazy.peel.util.shortLabel

/**
 * Shared "Open in Peel" picker used by the link/share trampolines. Sorts apps by host
 * affinity to [url], shows their icon/title/group, and launches the chosen app on [url].
 * Extra dialog buttons (and cancel handling) are supplied via [configure].
 */
fun AppCompatActivity.showOpenInPeelPicker(
    apps: List<WebApp>,
    url: String,
    configure: MaterialAlertDialogBuilder.() -> Unit = {},
) {
    val sorted = apps.sortedWith(
        compareByDescending<WebApp> { HostIdentity.affinity(it.baseUrl, url) }.thenBy { it.title }
    )
    val hasGroups = DataManager.instance.getGroups().isNotEmpty()
    PickerDialog.show(
        activity = this,
        title = getString(R.string.open_in_peel),
        items = sorted,
        onPick = { webapp -> BrowserLauncher.launch(webapp, this, url) },
        configure = configure,
    ) { webapp, icon, name, label, _ ->
        name.text = webapp.title
        icon.setImageBitmap(webapp.resolveIcon())
        if (hasGroups) {
            label.text = webapp.groupUuid?.let { DataManager.instance.getGroup(it)?.title }
                ?.let { shortLabel(it) } ?: getString(R.string.ungrouped)
            label.visibility = View.VISIBLE
        }
    }
}
