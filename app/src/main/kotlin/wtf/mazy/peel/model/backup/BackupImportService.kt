package wtf.mazy.peel.model.backup

import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ImportMode
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.model.db.toDomain
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.util.App
import java.util.UUID

object BackupImportService {

    suspend fun importFullBackup(parsed: ParsedBackup, mode: ImportMode): BackupResult {
        if (parsed.backupData.payloadType != BackupPolicy.PAYLOAD_FULL) return BackupResult.InvalidPayload
        val globalSettings =
            parsed.backupData.globalSettings ?: return BackupResult.MissingGlobalSettings
        return try {
            applyFullImport(parsed, globalSettings, mode)
            BackupResult.Success
        } catch (_: Exception) {
            BackupResult.Failure
        }
    }

    suspend fun importShared(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        destinationGroupUuid: String?,
    ): Int {
        val dataManager = DataManager.instance
        val existingUuids = dataManager.getWebsites().mapTo(mutableSetOf()) { it.uuid }
        var importedCount = 0
        var nextOrder = dataManager.incrementedOrder

        parsed.backupData.websites.forEach { surrogate ->
            if (surrogate.uuid !in selectedUuids) return@forEach

            val targetUuid =
                if (surrogate.uuid in existingUuids) UUID.randomUUID().toString()
                else surrogate.uuid
            existingUuids.add(targetUuid)

            val webApp = surrogate.toDomain(targetUuid)
            webApp.groupUuid = destinationGroupUuid
            webApp.order = nextOrder++
            webApp.isActiveEntry = true

            dataManager.addWebsite(webApp)
            parsed.icons[surrogate.uuid]?.let { BackupArchiveCodec.saveIcon(webApp.uuid, it) }
            ShortcutHelper.updatePinnedShortcut(webApp, App.appContext)
            importedCount++
        }

        return importedCount
    }

    suspend fun importGroupShared(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        selectedGroupUuids: Set<String>,
    ): Int {
        if (parsed.backupData.payloadType != BackupPolicy.PAYLOAD_GROUP_SHARE) return 0
        if (selectedGroupUuids.isEmpty()) return 0

        val dataManager = DataManager.instance
        val existingUuids = dataManager.getWebsites().mapTo(mutableSetOf()) { it.uuid }
        val groupUuidMap = mutableMapOf<String, String>()
        var nextGroupOrder = dataManager.getGroups().size

        parsed.backupData.groups.forEach { groupSurrogate ->
            if (groupSurrogate.uuid !in selectedGroupUuids) return@forEach
            val originalGroupUuid = groupSurrogate.uuid
            val importedGroup = groupSurrogate.toDomain().copy(
                uuid = UUID.randomUUID().toString(),
                order = nextGroupOrder++,
            )
            dataManager.addGroup(importedGroup)
            groupUuidMap[originalGroupUuid] = importedGroup.uuid
            parsed.icons[originalGroupUuid]?.let {
                BackupArchiveCodec.saveIcon(
                    importedGroup.uuid,
                    it
                )
            }
        }

        if (groupUuidMap.isEmpty()) return 0

        val defaultGroupUuid = groupUuidMap.values.first()
        var importedCount = 0
        var nextOrder = dataManager.incrementedOrder

        parsed.backupData.websites.forEach { surrogate ->
            if (surrogate.uuid !in selectedUuids) return@forEach

            val targetUuid =
                if (surrogate.uuid in existingUuids) UUID.randomUUID().toString()
                else surrogate.uuid
            existingUuids.add(targetUuid)

            val webApp = surrogate.toDomain(targetUuid)
            webApp.groupUuid = surrogate.groupUuid?.let(groupUuidMap::get) ?: defaultGroupUuid
            webApp.order = nextOrder++
            webApp.isActiveEntry = true

            dataManager.addWebsite(webApp)
            parsed.icons[surrogate.uuid]?.let { BackupArchiveCodec.saveIcon(webApp.uuid, it) }
            ShortcutHelper.updatePinnedShortcut(webApp, App.appContext)
            importedCount++
        }

        return importedCount
    }

    private suspend fun applyFullImport(
        parsed: ParsedBackup,
        globalSettings: WebAppSettings,
        mode: ImportMode,
    ) {
        val dataManager = DataManager.instance
        val importedWebApps = parsed.backupData.websites.map { it.toDomain() }
        val importedGroups = parsed.backupData.groups.map { it.toDomain() }

        parsed.icons.forEach { (uuid, bitmap) -> BackupArchiveCodec.saveIcon(uuid, bitmap) }

        when (mode) {
            ImportMode.REPLACE ->
                dataManager.importData(importedWebApps, globalSettings, importedGroups)

            ImportMode.MERGE ->
                dataManager.mergeData(importedWebApps, globalSettings, importedGroups)
        }
        val context = App.appContext
        dataManager.getWebsites().forEach { ShortcutHelper.updatePinnedShortcut(it, context) }
    }
}
