package wtf.mazy.peel.model

import android.app.Activity
import android.net.Uri
import wtf.mazy.peel.model.db.toSurrogate
import wtf.mazy.peel.model.backup.BackupArchiveCodec
import wtf.mazy.peel.model.backup.BackupImportService
import wtf.mazy.peel.model.backup.BackupPolicy
import wtf.mazy.peel.model.backup.BackupResult
import wtf.mazy.peel.model.backup.BackupShareLauncher
import java.io.File

enum class ImportMode {
    REPLACE,
    MERGE,
}

data class ParsedBackup(
    val backupData: BackupData,
    val icons: Map<String, android.graphics.Bitmap>,
)

object BackupManager {

    const val PAYLOAD_FULL = BackupPolicy.PAYLOAD_FULL
    const val PAYLOAD_APP_SHARE = BackupPolicy.PAYLOAD_APP_SHARE
    const val PAYLOAD_GROUP_SHARE = BackupPolicy.PAYLOAD_GROUP_SHARE
    const val MIME_TYPE = BackupPolicy.MIME_TYPE
    const val LOADER_THRESHOLD = BackupPolicy.LOADER_THRESHOLD

    fun readBackup(uri: Uri): ParsedBackup? {
        return BackupArchiveCodec.readBackup(uri)
    }

    suspend fun exportFullBackup(uri: Uri): Boolean {
        val dataManager = DataManager.instance
        dataManager.persistDefaultSettings()
        val websites = dataManager.getWebsites()
        return BackupArchiveCodec.writeBackupToUri(buildFullBackupData(dataManager, websites), websites, uri)
    }

    fun buildExportFilename(): String {
        return BackupPolicy.buildFilename("backup")
    }

    fun buildShareFile(webApps: List<WebApp>, includeSecrets: Boolean): File? {
        val backupData = BackupData(
            version = BackupPolicy.BACKUP_VERSION,
            payloadType = PAYLOAD_APP_SHARE,
            websites = webApps.map { webApp ->
                val surrogate = webApp.toSurrogate().copy(groupUuid = null)
                if (includeSecrets) surrogate else surrogate.copy(settings = surrogate.settings.withoutSecrets())
            },
        )
        return BackupArchiveCodec.buildBackupFile(backupData, webApps, "share")
    }

    fun buildGroupShareFile(groups: List<WebAppGroup>, webApps: List<WebApp>, includeSecrets: Boolean): File? {
        val safeGroups = groups.map { group ->
            val surrogate = group.toSurrogate()
            if (includeSecrets) surrogate else surrogate.copy(settings = surrogate.settings.withoutSecrets())
        }
        val backupData = BackupData(
            version = BackupPolicy.BACKUP_VERSION,
            payloadType = PAYLOAD_GROUP_SHARE,
            websites = webApps.map { webApp ->
                val surrogate = webApp.toSurrogate()
                if (includeSecrets) surrogate else surrogate.copy(settings = surrogate.settings.withoutSecrets())
            },
            groups = safeGroups,
        )
        return BackupArchiveCodec.buildBackupFile(
            backupData = backupData,
            websites = webApps,
            prefix = "group_share",
            extraIconOwners = groups,
        )
    }

    fun launchShareChooser(activity: Activity, file: File): Boolean {
        return BackupShareLauncher.launchShareChooser(activity, file)
    }

    suspend fun importFullBackup(parsed: ParsedBackup, mode: ImportMode): Boolean {
        return when (BackupImportService.importFullBackup(parsed, mode)) {
            BackupResult.Success -> true
            else -> false
        }
    }

    suspend fun importShared(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        destinationGroupUuid: String?,
    ): Int {
        return BackupImportService.importShared(parsed, selectedUuids, destinationGroupUuid)
    }

    suspend fun importGroupShared(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        selectedGroupUuids: Set<String>,
    ): Int {
        return BackupImportService.importGroupShared(parsed, selectedUuids, selectedGroupUuids)
    }

    private fun buildFullBackupData(
        dataManager: DataManager,
        websites: List<WebApp>,
    ) = BackupData(
        version = BackupPolicy.BACKUP_VERSION,
        payloadType = PAYLOAD_FULL,
        websites = websites.map { it.toSurrogate() },
        globalSettings = dataManager.defaultSettings.settings,
        groups = dataManager.getGroups().map { it.toSurrogate() },
    )

    private fun WebAppSettings.withoutSecrets(): WebAppSettings {
        return deepCopy().apply {
            customHeaders = null
            isUseBasicAuth = false
            basicAuthUsername = null
            basicAuthPassword = null
        }
    }
}
