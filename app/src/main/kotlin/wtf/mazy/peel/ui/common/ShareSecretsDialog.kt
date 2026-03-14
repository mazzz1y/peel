package wtf.mazy.peel.ui.common

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.ShareSecretsPolicy
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup

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
        showSecretsPrompt(activity, onDecision)
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
        showSecretsPrompt(activity, onDecision)
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
