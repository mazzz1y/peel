package wtf.mazy.peel.ui.common

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.HostIdentity
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.shortLabel

fun AppCompatActivity.showOpenInPeelPicker(
    apps: List<WebApp>,
    url: String,
    onDismiss: () -> Unit = {},
) {
    val sorted = apps.sortedWith(
        compareByDescending<WebApp> { HostIdentity.affinity(it.baseUrl, url) }.thenBy { it.title }
    )
    if (sorted.isEmpty()) {
        NotificationUtils.showToast(this, getString(R.string.no_web_apps_available))
        onDismiss()
        return
    }
    val hasGroups = DataManager.instance.getGroups().isNotEmpty()
    PickerDialog.show(
        activity = this,
        title = getString(R.string.open_in_peel),
        items = sorted,
        onPick = { webapp -> BrowserLauncher.launch(webapp, this, url) },
        configure = { setOnDismissListener { onDismiss() } },
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
