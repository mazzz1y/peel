package wtf.mazy.peel.model.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.json.Json
import wtf.mazy.peel.model.BackupData
import wtf.mazy.peel.model.IconCache
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.isCanonicalUuid
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
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
        iconOwners: List<IconOwner>,
        uri: Uri,
    ): Boolean {
        return try {
            val stream = App.appContext.contentResolver.openOutputStream(uri) ?: return false
            stream.use { writeArchive(it, backupData, iconOwners) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun buildBackupFile(
        backupData: BackupData,
        iconOwners: List<IconOwner>,
        prefix: String,
    ): File? {
        return try {
            val filename = BackupPolicy.buildFilename(prefix)
            val file = File(App.appContext.cacheDir, "${BackupPolicy.SHARE_DIR}/$filename")
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            FileOutputStream(file).use { writeArchive(it, backupData, iconOwners) }
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun writeArchive(
        outputStream: OutputStream,
        backupData: BackupData,
        iconOwners: List<IconOwner>,
    ) {
        ZipOutputStream(outputStream).use { zip ->
            writeMarker(zip)
            writeDataJson(zip, backupData)
            iconOwners.forEach { writeIconEntry(zip, it) }
        }
    }

    fun saveIcon(uuid: String, bitmap: Bitmap) {
        try {
            val iconFile = File(App.appContext.filesDir, "icons/${uuid}.png")
            iconFile.parentFile?.mkdirs()
            FileOutputStream(iconFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            IconCache.evict(uuid)
        } catch (_: Exception) {
        }
    }

    private fun writeMarker(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry(BackupPolicy.MARKER_ENTRY))
        zip.write(BackupPolicy.BACKUP_VERSION.toByteArray())
        zip.closeEntry()
    }

    private fun writeDataJson(zip: ZipOutputStream, backupData: BackupData) {
        zip.putNextEntry(ZipEntry(BackupPolicy.DATA_ENTRY))
        zip.write(prettyJson.encodeToString(backupData).toByteArray())
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
        var markerVersion: String? = null
        val icons = mutableMapOf<String, Bitmap>()
        var iconBudget = MAX_TOTAL_ICON_MEMORY_BYTES

        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            when {
                entry.name == BackupPolicy.MARKER_ENTRY -> {
                    markerVersion = zip.readBounded(MAX_MARKER_BYTES)
                        ?.toString(Charsets.UTF_8)?.trim()
                }

                entry.name == BackupPolicy.DATA_ENTRY -> {
                    jsonString = zip.readBounded(MAX_DATA_BYTES)?.toString(Charsets.UTF_8)
                }

                entry.name.startsWith(BackupPolicy.ICONS_PREFIX) && entry.name.endsWith(".png") -> {
                    val appUuid =
                        entry.name.removePrefix(BackupPolicy.ICONS_PREFIX).removeSuffix(".png")
                    if (appUuid.isCanonicalUuid() && icons.size < MAX_ICON_COUNT) {
                        zip.readBounded(MAX_ICON_BYTES)?.let { bytes ->
                            decodeIconBitmap(bytes, iconBudget)?.let {
                                iconBudget -= it.byteCount
                                icons[appUuid] = it
                            }
                        }
                    }
                }
            }
            entry = zip.nextEntry
        }

        if (jsonString == null) return null

        val backupData =
            try {
                lenientJson.decodeFromString<BackupData>(jsonString)
            } catch (_: Exception) {
                return null
            }
        if (backupData.version != BackupPolicy.BACKUP_VERSION) return null
        if (!hasValidUuids(backupData)) return null
        return ParsedBackup(backupData, icons, markerVersion)
    }

    // Uuids from a backup end up in filesystem paths (IconOwner.iconFile), so a
    // malformed uuid is a path traversal vector, not just bad data.
    private fun hasValidUuids(backupData: BackupData): Boolean =
        backupData.websites.all { it.uuid.isCanonicalUuid() } &&
                backupData.groups.all { it.uuid.isCanonicalUuid() } &&
                backupData.proxies.all { it.uuid.isCanonicalUuid() }

    // Zip entries report their own size, which an attacker controls; read with a
    // hard cap instead of trusting it.
    private fun ZipInputStream.readBounded(maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (out.size() + n > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeIconBitmap(bytes: ByteArray, remainingBudget: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ICON_DIMENSION_PX) return null
        if (bounds.outHeight !in 1..MAX_ICON_DIMENSION_PX) return null
        if (bounds.outWidth.toLong() * bounds.outHeight * 4 > remainingBudget) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private const val MAX_MARKER_BYTES = 64
    private const val MAX_DATA_BYTES = 32 * 1024 * 1024
    private const val MAX_ICON_BYTES = 4 * 1024 * 1024
    private const val MAX_ICON_COUNT = 1024
    private const val MAX_ICON_DIMENSION_PX = 2048
    private const val MAX_TOTAL_ICON_MEMORY_BYTES = 128 * 1024 * 1024
}
