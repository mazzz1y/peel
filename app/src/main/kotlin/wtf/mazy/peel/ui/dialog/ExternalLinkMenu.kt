package wtf.mazy.peel.ui.dialog

import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.UnwrappedLink
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.ui.entitylist.PendingDeletes
import wtf.mazy.peel.ui.webapplist.UrlSuggestion
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.bestBy
import wtf.mazy.peel.util.linkMatch
import wtf.mazy.peel.util.normalizedHost
import wtf.mazy.peel.util.servesHost
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
                        openInPeelPicker(activity, UnwrappedLink(url), excludeUuid, onDismiss = onPickerDismiss)
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
            if (activity.shouldOfferOpenInSystem(url)) {
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
            app.uuid != excludeUuid && app.uuid !in pending && app.servesHost(targetHost)
        }
    }

    fun bestPeelMatch(
        peelApps: List<WebApp>,
        url: String,
        excludeUuid: String?,
    ): WebApp? = peelApps.filter { it.uuid != excludeUuid }.bestBy { it.linkMatch(url) }

    fun openInPeelPicker(
        activity: AppCompatActivity,
        link: UnwrappedLink,
        excludeUuid: String?,
        actions: Map<UrlSuggestion, () -> Unit> = emptyMap(),
        include: (WebApp) -> Boolean = { true },
        onDismiss: () -> Unit = {},
    ) {
        activity.lifecycleScope.launch {
            val all = DataManager.queryAllWebApps().filter { it.uuid != excludeUuid }
            val apps = all.filter(include).ifEmpty { all }.sortedByAffinity(link.destination)
            if (apps.isEmpty()) {
                activity.toast(R.string.no_web_apps_available, long = true)
                onDismiss()
                return@launch
            }

            val groupUuids = apps.mapNotNullTo(mutableSetOf()) { it.groupUuid }
            val groupTitles = groupUuids
                .mapNotNull { uuid -> DataManager.queryGroup(uuid)?.let { uuid to it.title } }
                .toMap()

            PickerDialog.showWebApps(
                activity = activity,
                apps = apps,
                groupTitles = groupTitles,
                onPick = { webApp -> BrowserLauncher.launch(webApp, activity, link.launchUrl) },
                onDismiss = onDismiss,
                actions = actions,
            )
        }
    }

}
