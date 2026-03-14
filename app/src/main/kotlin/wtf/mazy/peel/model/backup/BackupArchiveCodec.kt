package wtf.mazy.peel.model.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.json.Json
import wtf.mazy.peel.model.BackupData
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.util.App
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupArchiveCodec {

    private val prettyJson = Json { prettyPrint = true }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun readBackup(uri: Uri): ParsedBackup? {
        return try {
            App.appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip -> parseZipEntries(zip) }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun writeBackupToUri(
        backupData: BackupData,
        websites: List<WebApp>,
        uri: Uri,
    ): Boolean {
        return try {
            val stream = App.appContext.contentResolver.openOutputStream(uri) ?: return false
            stream.use { outputStream ->
                ZipOutputStream(outputStream).use { zip ->
                    writeDataJson(zip, backupData)
                    websites.forEach { writeIconEntry(zip, it) }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun buildBackupFile(
        backupData: BackupData,
        websites: List<WebApp>,
        prefix: String,
        extraIconOwners: List<IconOwner> = emptyList(),
    ): File? {
        return try {
            val filename = buildFilename(prefix)
            val file = File(App.appContext.cacheDir, "${BackupPolicy.SHARE_DIR}/$filename")
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            FileOutputStream(file).use { outputStream ->
                ZipOutputStream(outputStream).use { zip ->
                    writeDataJson(zip, backupData)
                    websites.forEach { writeIconEntry(zip, it) }
                    extraIconOwners.forEach { writeIconEntry(zip, it) }
                }
            }
            file
        } catch (_: Exception) {
            null
        }
    }

    fun saveIcon(uuid: String, bitmap: Bitmap) {
        try {
            val iconFile = File(App.appContext.filesDir, "icons/${uuid}.png")
            iconFile.parentFile?.mkdirs()
            FileOutputStream(iconFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } catch (_: Exception) {
        }
    }

    private fun writeDataJson(zip: ZipOutputStream, backupData: BackupData) {
        zip.putNextEntry(ZipEntry(BackupPolicy.DATA_ENTRY))
        zip.write(prettyJson.encodeToString(BackupData.serializer(), backupData).toByteArray())
        zip.closeEntry()
    }

    private fun writeIconEntry(zip: ZipOutputStream, iconOwner: IconOwner) {
        if (!iconOwner.hasCustomIcon) return
        try {
            val bitmap = BitmapFactory.decodeFile(iconOwner.iconFile.absolutePath) ?: return
            zip.putNextEntry(ZipEntry("${BackupPolicy.ICONS_PREFIX}${iconOwner.uuid}.png"))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
            zip.closeEntry()
            bitmap.recycle()
        } catch (_: Exception) {
        }
    }

    private fun parseZipEntries(zip: ZipInputStream): ParsedBackup? {
        var jsonString: String? = null
        val icons = mutableMapOf<String, Bitmap>()

        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            when {
                entry.name == BackupPolicy.DATA_ENTRY -> {
                    jsonString = zip.readBytes().toString(Charsets.UTF_8)
                }

                entry.name.startsWith(BackupPolicy.ICONS_PREFIX) && entry.name.endsWith(".png") -> {
                    val appUuid = entry.name.removePrefix(BackupPolicy.ICONS_PREFIX).removeSuffix(".png")
                    val bytes = zip.readBytes()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) icons[appUuid] = bitmap
                }
            }
            entry = zip.nextEntry
        }

        if (jsonString == null) return null

        val backupData =
            try {
                lenientJson.decodeFromString(BackupData.serializer(), jsonString)
            } catch (_: Exception) {
                return null
            }
        if (backupData.version != BackupPolicy.BACKUP_VERSION) return null
        return ParsedBackup(backupData, icons)
    }

    private fun buildFilename(prefix: String): String {
        return BackupPolicy.buildFilename(prefix)
    }
}
