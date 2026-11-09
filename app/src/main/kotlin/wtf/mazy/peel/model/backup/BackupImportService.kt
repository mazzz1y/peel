package wtf.mazy.peel.model.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.IconCache
import wtf.mazy.peel.model.ImportMode
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.model.WebAppSurrogate
import wtf.mazy.peel.model.db.toDomain
import java.io.File
import java.util.UUID

object BackupImportService {

    suspend fun importFullBackup(parsed: ParsedBackup, mode: ImportMode): BackupResult {
        if (parsed.backupData.payloadType != BackupPolicy.PAYLOAD_FULL) return BackupResult.InvalidPayload
        val globalSettings =
            parsed.backupData.globalSettings ?: return BackupResult.MissingGlobalSettings
        return if (applyFullImport(parsed, globalSettings, mode)) {
            BackupResult.Success
        } else {
            BackupResult.Failure
        }
    }

    suspend fun importShared(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        destinationGroupUuid: String?,
    ): Int = importWebsites(parsed, selectedUuids) { destinationGroupUuid }

    suspend fun importGroupShared(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        selectedGroupUuids: Set<String>,
    ): Int {
        if (parsed.backupData.payloadType != BackupPolicy.PAYLOAD_GROUP_SHARE) return 0
        if (selectedGroupUuids.isEmpty()) return 0

        val groupUuidMap = mutableMapOf<String, String>()

        parsed.backupData.groups.forEach { groupSurrogate ->
            if (groupSurrogate.uuid !in selectedGroupUuids) return@forEach
            val originalGroupUuid = groupSurrogate.uuid
            val importedGroup = groupSurrogate.toDomain().copy(
                uuid = UUID.randomUUID().toString(),
            )
            if (!DataManager.addGroup(importedGroup, appendOrder = true)) return@forEach
            groupUuidMap[originalGroupUuid] = importedGroup.uuid
            parsed.icons[originalGroupUuid]?.let { IconCache.save(importedGroup.uuid, it) }
        }

        if (groupUuidMap.isEmpty()) return 0

        val defaultGroupUuid = groupUuidMap.values.first()
        return importWebsites(parsed, selectedUuids) { surrogate ->
            surrogate.groupUuid?.let(groupUuidMap::get) ?: defaultGroupUuid
        }
    }

    private suspend fun importWebsites(
        parsed: ParsedBackup,
        selectedUuids: Set<String>,
        resolveGroup: (WebAppSurrogate) -> String?,
    ): Int {
        val existingUuids = DataManager.webApps.mapTo(mutableSetOf()) { it.uuid }
        var importedCount = 0

        parsed.backupData.websites.forEach { surrogate ->
            if (surrogate.uuid !in selectedUuids) return@forEach

            val targetUuid =
                if (surrogate.uuid in existingUuids) UUID.randomUUID().toString()
                else surrogate.uuid
            existingUuids.add(targetUuid)

            val webApp = surrogate.toDomain(targetUuid).copy(groupUuid = resolveGroup(surrogate))

            if (!DataManager.addWebApp(webApp, appendOrder = true)) return@forEach
            parsed.icons[surrogate.uuid]?.let { IconCache.save(webApp.uuid, it) }
            DataManager.sideEffects.onShortcutOwnerChanged(webApp)
            importedCount++
        }

        return importedCount
    }

    private suspend fun applyFullImport(
        parsed: ParsedBackup,
        globalSettings: WebAppSettings,
        mode: ImportMode,
    ): Boolean {
        val importedWebApps = parsed.backupData.websites.map { it.toDomain() }
        val importedGroups = parsed.backupData.groups.map { it.toDomain() }
        val importedProxies = parsed.backupData.proxies

        // Icons go in first so the list draws them as soon as the import is published; the
        // imported uuids may be existing apps', so a failed import puts the previous files back.
        // Not cancellable: the actor finishes the import even if the screen goes away.
        return withContext(NonCancellable) {
            val previousIcons = parsed.icons.keys.associateWith { uuid ->
                runCatching { IconCache.sourceFile(uuid).takeIf(File::exists)?.readBytes() }
                    .getOrNull()
            }
            parsed.icons.forEach { (uuid, bitmap) -> IconCache.save(uuid, bitmap) }
            val stored = DataManager.importData(
                mode,
                importedWebApps,
                globalSettings,
                importedGroups,
                importedProxies
            )
            if (!stored) {
                previousIcons.forEach { (uuid, bytes) -> restoreIcon(uuid, bytes) }
                return@withContext false
            }
            DataManager.webApps.forEach(DataManager.sideEffects::onShortcutOwnerChanged)
            true
        }
    }

    private fun restoreIcon(uuid: String, bytes: ByteArray?) {
        runCatching {
            val file = IconCache.sourceFile(uuid)
            if (bytes != null) file.writeBytes(bytes) else file.delete()
        }
        IconCache.evict(uuid)
    }
}
