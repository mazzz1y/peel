package wtf.mazy.peel.ui.dialog

import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.ui.entitylist.PendingDeletes
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.HostIdentity
import wtf.mazy.peel.util.linkAffinity
import wtf.mazy.peel.util.normalizedHost
import wtf.mazy.peel.util.shortLabel
import wtf.mazy.peel.util.shouldOfferOpenInSystem
import wtf.mazy.peel.util.sortedByAffinity
import wtf.mazy.peel.util.toast

sealed interface ExternalLinkResult {
    data object LoadHere : ExternalLinkResult
    data object OpenInSystem : ExternalLinkResult
    data object OpenIncognito : ExternalLinkResult
    data object Share : ExternalLinkResult
    data object CopyLink : ExternalLinkResult
    data object Dismissed : ExternalLinkResult

    // the launcher reports back when it is finished, which for the picker means dismissed:
    // a standalone host activity must outlive the dialog it opens
    data class OpenInPeelApp(val launcher: (onDone: () -> Unit) -> Unit) : ExternalLinkResult
}

object ExternalLinkMenu {

    fun show(
        activity: AppCompatActivity,
        url: String,
        excludeUuid: String?,
        peelApps: List<WebApp>,
        includeLoadHere: Boolean,
        includeOpenInSystem: Boolean = true,
        includeShareAndCopy: Boolean = true,
        onResult: (ExternalLinkResult) -> Unit,
    ) {
        var dialog: AlertDialog? = null
        fun dismiss(result: ExternalLinkResult) {
            dialog?.dismiss()
            onResult(result)
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(MenuDialogViews.buildDivider(activity))

            val match = bestPeelMatch(peelApps, url, excludeUuid)
            val icon = match?.resolveIcon()
            val iconClick = if (icon != null) {
                {
                    dismiss(ExternalLinkResult.OpenInPeelApp { onLaunched ->
                        BrowserLauncher.launch(match, activity, url)
                        onLaunched()
                    })
                }
            } else null

            addView(
                MenuDialogViews.buildActionRow(
                    activity,
                    activity.getString(R.string.open_in_peel),
                    icon,
                    iconClick,
                ) {
                    dismiss(ExternalLinkResult.OpenInPeelApp { onPickerDismiss ->
                        openInPeelPicker(activity, url, excludeUuid, onPickerDismiss)
                    })
                }
            )
            if (includeLoadHere) {
                addView(
                    MenuDialogViews.buildActionRow(
                        activity,
                        activity.getString(R.string.open_in_current_session),
                    ) {
                        dismiss(ExternalLinkResult.LoadHere)
                    }
                )
            }
            if (includeOpenInSystem && activity.shouldOfferOpenInSystem(url)) {
                addView(
                    MenuDialogViews.buildActionRow(
                        activity,
                        activity.getString(R.string.open_in_system),
                    ) {
                        dismiss(ExternalLinkResult.OpenInSystem)
                    }
                )
            }
            addView(
                MenuDialogViews.buildActionRow(
                    activity,
                    activity.getString(R.string.open_incognito_action),
                ) {
                    dismiss(ExternalLinkResult.OpenIncognito)
                }
            )
            if (includeShareAndCopy) {
                addView(
                    MenuDialogViews.buildActionRow(
                        activity,
                        activity.getString(R.string.context_menu_share_link),
                    ) {
                        dismiss(ExternalLinkResult.Share)
                    }
                )
                addView(
                    MenuDialogViews.buildActionRow(
                        activity,
                        activity.getString(R.string.context_menu_copy_link),
                    ) {
                        dismiss(ExternalLinkResult.CopyLink)
                    }
                )
            }
        }

        dialog = MaterialAlertDialogBuilder(activity)
            .setCustomTitle(
                MenuDialogViews.buildHeader(
                    activity,
                    null,
                    MenuDialogViews.prettyDataUrl(url)
                )
            )
            .setView(content)
            .setOnCancelListener { onResult(ExternalLinkResult.Dismissed) }
            .show()
    }

    fun findPeelAppMatches(
        peelApps: List<WebApp>,
        url: String,
        excludeUuid: String?,
    ): List<WebApp> {
        val targetHost = url.normalizedHost() ?: return emptyList()
        val pending = PendingDeletes.webApps
        return peelApps.filter { app ->
            app.uuid != excludeUuid &&
                    app.uuid !in pending &&
                    app.baseUrl.normalizedHost() == targetHost
        }
    }

    fun bestPeelMatch(
        peelApps: List<WebApp>,
        url: String,
        excludeUuid: String?,
    ): WebApp? {
        val scores = peelApps
            .filter { it.uuid != excludeUuid }
            .associateWith { it.linkAffinity(url) }
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
        onDismiss: () -> Unit = {},
    ) {
        activity.lifecycleScope.launch {
            val apps = DataManager.queryAllWebApps()
                .filter { it.uuid != excludeUuid }
                .sortedByAffinity(url)
            if (apps.isEmpty()) {
                activity.toast(R.string.no_web_apps_available, long = true)
                onDismiss()
                return@launch
            }

            val hasGroups = apps.any { it.groupUuid != null }
            val groupTitles = if (hasGroups) {
                apps.mapNotNull { it.groupUuid }.distinct()
                    .associateWith { DataManager.queryGroup(it)?.title }
            } else emptyMap()

            PickerDialog.show(
                activity = activity,
                title = activity.getString(R.string.open_in_peel),
                items = apps,
                onPick = { webApp -> BrowserLauncher.launch(webApp, activity, url) },
                configure = { setOnDismissListener { onDismiss() } },
            ) { webApp, icon, name, label, _ ->
                name.text = webApp.title
                icon.setImageBitmap(webApp.resolveIcon())
                if (hasGroups) {
                    label.text = webApp.groupUuid?.let { groupTitles[it] }
                        ?.let { shortLabel(it) }
                        ?: activity.getString(R.string.ungrouped)
                    label.visibility = View.VISIBLE
                }
            }
        }
    }

}
