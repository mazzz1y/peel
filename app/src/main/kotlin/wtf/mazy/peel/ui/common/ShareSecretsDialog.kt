package wtf.mazy.peel.ui.common

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.ShareSecretsPolicy
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.ProtectionGate

object ShareSecretsDialog {
    fun confirmForWebApps(
        activity: AppCompatActivity,
        webApps: List<WebApp>,
        onDecision: (includeSecrets: Boolean) -> Unit,
    ) {
        if (!ShareSecretsPolicy.hasSecretsInWebApps(webApps)) {
            onDecision(true)
            return
        }
        val guarded = guardSecrets(activity, emptyList(), webApps, onDecision)
        showSecretsPrompt(activity, guarded)
    }

    fun confirmForGroupsAndApps(
        activity: AppCompatActivity,
        groups: List<WebAppGroup>,
        webApps: List<WebApp>,
        onDecision: (includeSecrets: Boolean) -> Unit,
    ) {
        if (!ShareSecretsPolicy.hasSecrets(groups, webApps)) {
            onDecision(true)
            return
        }
        val guarded = guardSecrets(activity, groups, webApps, onDecision)
        showSecretsPrompt(activity, guarded)
    }

    fun requireForSecrets(
        activity: AppCompatActivity,
        groups: List<WebAppGroup>,
        webApps: List<WebApp>,
        globalSettings: WebAppSettings? = null,
        onPass: () -> Unit,
    ) {
        val needed = groups.any { ProtectionGate.isProtected(it) && ShareSecretsPolicy.hasSecrets(it.settings) } ||
                webApps.any { ProtectionGate.isProtected(it) && ShareSecretsPolicy.hasSecrets(it.settings) } ||
                globalSettings != null && ProtectionGate.isProtectedGlobally &&
                ShareSecretsPolicy.hasSecrets(globalSettings)
        ProtectionGate.require(activity, needed, onPass = onPass)
    }

    private fun guardSecrets(
        activity: AppCompatActivity,
        groups: List<WebAppGroup>,
        webApps: List<WebApp>,
        onDecision: (includeSecrets: Boolean) -> Unit,
    ): (Boolean) -> Unit = { includeSecrets ->
        if (includeSecrets) {
            requireForSecrets(activity, groups, webApps) { onDecision(true) }
        } else {
            onDecision(false)
        }
    }

    private fun showSecretsPrompt(
        activity: AppCompatActivity,
        onDecision: (includeSecrets: Boolean) -> Unit,
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.share_secrets_title)
            .setMessage(R.string.share_secrets_description)
            .setPositiveButton(R.string.share_secrets_exclude) { _, _ -> onDecision(false) }
            .setNegativeButton(R.string.share_secrets_include) { _, _ -> onDecision(true) }
            .show()
    }
}
