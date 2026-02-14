package wtf.mazy.peel.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import wtf.mazy.peel.model.db.toDomain
import wtf.mazy.peel.model.db.toSurrogate
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.util.App

object BackupManager {

    private const val BACKUP_VERSION = "1"
    private const val DATA_ENTRY = "data.json"
    private const val ICONS_PREFIX = "icons/"

    private val prettyJson = Json { prettyPrint = true }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun exportToZip(uri: Uri): Boolean {
        val dataManager = DataManager.instance
        return try {
            App.appContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zip ->
                    writeDataJson(zip, dataManager)
                    dataManager.getWebsites().forEach { writeIconEntry(zip, it) }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun importFromZip(uri: Uri): Boolean {
        return try {
            App.appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip -> processZipImport(zip) }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun writeDataJson(zip: ZipOutputStream, dataManager: DataManager) {
        val backupData =
            BackupData(
                version = BACKUP_VERSION,
                websites = dataManager.getWebsites().map { it.toSurrogate() },
                globalSettings = dataManager.defaultSettings.settings,
            )
        zip.putNextEntry(ZipEntry(DATA_ENTRY))
        zip.write(prettyJson.encodeToString(BackupData.serializer(), backupData).toByteArray())
        zip.closeEntry()
    }

    private fun writeIconEntry(zip: ZipOutputStream, webApp: WebApp) {
        if (!webApp.hasCustomIcon) return
        try {
            val bitmap = BitmapFactory.decodeFile(webApp.iconFile.absolutePath) ?: return
            zip.putNextEntry(ZipEntry("$ICONS_PREFIX${webApp.uuid}.png"))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
            zip.closeEntry()
            bitmap.recycle()
        } catch (_: Exception) {}
    }

    private fun processZipImport(zip: ZipInputStream): Boolean {
        var jsonString: String? = null
        val icons = mutableMapOf<String, Bitmap>()

        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            when {
                entry.name == DATA_ENTRY -> {
                    jsonString = zip.readBytes().toString(Charsets.UTF_8)
                }
                entry.name.startsWith(ICONS_PREFIX) && entry.name.endsWith(".png") -> {
                    val appUuid = entry.name.removePrefix(ICONS_PREFIX).removeSuffix(".png")
                    val bitmap = BitmapFactory.decodeStream(zip)
                    if (bitmap != null) icons[appUuid] = bitmap
                }
            }
            entry = zip.nextEntry
        }

        if (jsonString == null) return false

        val backupData =
            try {
                lenientJson.decodeFromString(BackupData.serializer(), jsonString)
            } catch (_: Exception) {
                return false
            }
        if (backupData.version != BACKUP_VERSION) return false

        val dataManager = DataManager.instance
        val importedWebApps = backupData.websites.map { it.toDomain() }

        icons.forEach { (uuid, bitmap) -> saveIcon(uuid, bitmap) }

        dataManager.importData(importedWebApps, backupData.globalSettings)
        val context = App.appContext
        dataManager.getWebsites().forEach { ShortcutHelper.updatePinnedShortcut(it, context) }
        return true
    }

    private fun saveIcon(uuid: String, bitmap: Bitmap) {
        try {
            val iconFile = File(App.appContext.filesDir, "icons/${uuid}.png")
            iconFile.parentFile?.mkdirs()
            FileOutputStream(iconFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } catch (_: Exception) {}
    }
}
