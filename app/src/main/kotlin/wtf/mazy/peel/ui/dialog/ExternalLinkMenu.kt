package wtf.mazy.peel.ui.dialog

import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.ExternalLinkResult
import wtf.mazy.peel.browser.MenuDialogHelper
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.HostIdentity
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.normalizedHost
import wtf.mazy.peel.util.shortLabel

object ExternalLinkMenu {

    fun show(
        activity: AppCompatActivity,
        url: String,
        excludeUuid: String?,
        peelApps: List<WebApp>,
        includeLoadHere: Boolean,
        onResult: (ExternalLinkResult) -> Unit,
    ) {
        var dialog: AlertDialog? = null
        fun dismiss(result: ExternalLinkResult) {
            dialog?.dismiss()
            onResult(result)
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                MenuDialogHelper.buildHeader(
                    activity,
                    null,
                    MenuDialogHelper.displayUrl(url),
                )
            )
            addView(MenuDialogHelper.buildDivider(activity))

            val match = bestPeelMatch(peelApps, url, excludeUuid)
            val icon = match?.resolveIcon()
            val iconClick = if (icon != null) {
                {
                    dismiss(ExternalLinkResult.OpenInPeelApp {
                        BrowserLauncher.launch(
                            match,
                            activity,
                            url
                        )
                    })
                }
            } else null

            addView(
                MenuDialogHelper.buildActionRow(
                    activity,
                    activity.getString(R.string.open_in_peel),
                    icon,
                    iconClick,
                ) {
                    dismiss(ExternalLinkResult.OpenInPeelApp {
                        openInPeelPicker(activity, url, excludeUuid)
                    })
                }
            )
            if (includeLoadHere) {
                addView(
                    MenuDialogHelper.buildActionRow(
                        activity,
                        activity.getString(R.string.open_in_current_session),
                    ) {
                        dismiss(ExternalLinkResult.LOAD_HERE)
                    }
                )
            }
            addView(
                MenuDialogHelper.buildActionRow(
                    activity,
                    activity.getString(R.string.open_in_system),
                ) {
                    dismiss(ExternalLinkResult.OPEN_IN_SYSTEM)
                }
            )
        }

        dialog = MaterialAlertDialogBuilder(activity)
            .setView(content)
            .setOnCancelListener { onResult(ExternalLinkResult.DISMISSED) }
            .show()
    }

    fun findPeelAppMatches(
        peelApps: List<WebApp>,
        url: String,
        excludeUuid: String?,
    ): List<WebApp> {
        val targetHost = url.normalizedHost() ?: return emptyList()
        return peelApps.filter { app ->
            app.uuid != excludeUuid && app.baseUrl.normalizedHost() == targetHost
        }
    }

    fun bestPeelMatch(
        peelApps: List<WebApp>,
        url: String,
        excludeUuid: String?,
    ): WebApp? {
        val scores = peelApps
            .filter { it.uuid != excludeUuid }
            .associateWith { HostIdentity.affinity(it.baseUrl, url) }
        val topScore = scores.values.maxOrNull() ?: return null
        if (topScore <= HostIdentity.TLD_ONLY) return null
        val topMatches = scores.filterValues { it == topScore }
        if (topMatches.size != 1) return null
        return topMatches.keys.first()
    }

    fun openInPeelPicker(
        activity: AppCompatActivity,
        url: String,
        excludeUuid: String?,
    ) {
        activity.lifecycleScope.launch {
            val apps = DataManager.instance.queryAllWebApps()
                .filter { it.uuid != excludeUuid }
                .sortedWith(
                    compareByDescending<WebApp> { HostIdentity.affinity(it.baseUrl, url) }
                        .thenBy { it.title }
                )
            if (apps.isEmpty()) {
                NotificationUtils.showToast(
                    activity,
                    activity.getString(R.string.no_web_apps_available),
                )
                return@launch
            }

            val hasGroups = apps.any { it.groupUuid != null }
            val groupTitles = if (hasGroups) {
                apps.mapNotNull { it.groupUuid }.distinct()
                    .associateWith { DataManager.instance.queryGroup(it)?.title }
            } else emptyMap()

            PickerDialog.show(
                activity = activity,
                title = activity.getString(R.string.open_in_peel),
                items = apps,
                onPick = { webapp -> BrowserLauncher.launch(webapp, activity, url) },
            ) { webapp, icon, name, detail ->
                name.text = webapp.title
                icon.setImageBitmap(webapp.resolveIcon())
                if (hasGroups) {
                    detail.text = webapp.groupUuid?.let { groupTitles[it] }
                        ?.let { shortLabel(it) }
                        ?: activity.getString(R.string.ungrouped)
                    detail.visibility = View.VISIBLE
                }
            }
        }
    }

}
