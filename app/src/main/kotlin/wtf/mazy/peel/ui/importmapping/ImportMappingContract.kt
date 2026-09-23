package wtf.mazy.peel.ui.importmapping

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import wtf.mazy.peel.model.ParsedBackup

class ImportMappingContract(
    private val target: Class<out Activity>,
) : ActivityResultContract<ImportMappingContract.Request, ImportMappingContract.Selection?>() {

    data class Request(val parsed: ParsedBackup, val groupShare: Boolean)

    sealed interface Selection {
        val parsed: ParsedBackup
        val appUuids: Set<String>

        data class Apps(
            override val parsed: ParsedBackup,
            override val appUuids: Set<String>,
            val destinationGroupUuid: String?,
        ) : Selection

        data class Groups(
            override val parsed: ParsedBackup,
            override val appUuids: Set<String>,
            val groupUuids: Set<String>,
        ) : Selection
    }

    override fun createIntent(context: Context, input: Request): Intent {
        pendingBackup = input.parsed
        return Intent(context, target).putExtra(EXTRA_GROUP_SHARE, input.groupShare)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Selection? {
        val parsed = pendingBackup ?: return null
        pendingBackup = null
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val appUuids = intent.getStringArrayExtra(RESULT_SELECTED_UUIDS)?.toSet() ?: return null
        val groupUuids = intent.getStringArrayExtra(RESULT_SELECTED_GROUP_UUIDS)?.toSet()
        return if (groupUuids != null) {
            Selection.Groups(parsed, appUuids, groupUuids)
        } else {
            Selection.Apps(parsed, appUuids, intent.getStringExtra(RESULT_GROUP_UUID))
        }
    }

    companion object {
        const val EXTRA_GROUP_SHARE = "group_share"
        const val RESULT_SELECTED_UUIDS = "selected_uuids"
        const val RESULT_SELECTED_GROUP_UUIDS = "selected_group_uuids"
        const val RESULT_GROUP_UUID = "group_uuid"

        // The backup carries decoded icons, so it stays in-process instead of riding the intent.
        @Volatile
        var pendingBackup: ParsedBackup? = null
            private set
    }
}
