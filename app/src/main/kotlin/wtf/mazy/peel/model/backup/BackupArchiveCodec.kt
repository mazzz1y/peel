package wtf.mazy.peel.model.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.json.Json
import wtf.mazy.peel.model.BackupData
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.isCanonicalUuid
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupArchiveCodec {

    private val prettyJson = Json { prettyPrint = true }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun readBackupSource(uri: Uri): BackupSource {
        return try {
            App.appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip -> parseArchive(zip) }
            } ?: BackupSource.NotABackup
        } catch (_: Exception) {
            BackupSource.NotABackup
        } catch (_: OutOfMemoryError) {
            BackupSource.NotABackup
        }
    }

    /**
     * Throws [javax.crypto.AEADBadTagException] when the password is wrong or the archive was
     * altered, and returns null when the payload decrypted but holds no usable backup.
     */
    fun decryptBackup(source: BackupSource.Protected, password: CharArray): ParsedBackup? {
        val inner = BackupCrypto.decrypt(
            source.payload,
            password,
            source.params,
            source.associatedData,
        )
        return try {
            ZipInputStream(ByteArrayInputStream(inner)).use { zip -> parseZipEntries(zip) }
        } finally {
            inner.fill(0)
        }
    }

    fun writeBackupToUri(
        backupData: BackupData,
        iconOwners: List<IconOwner>,
        uri: Uri,
        password: CharArray? = null,
    ): Boolean {
        return try {
            val stream = App.appContext.contentResolver.openOutputStream(uri) ?: return false
            stream.use {
                if (password == null) writeArchive(it, backupData, iconOwners)
                else writeEncryptedArchive(it, backupData, iconOwners, password)
            }
            true
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
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
            writeMarker(zip, BackupPolicy.BACKUP_VERSION)
            writeDataJson(zip, backupData)
            var iconByteBudget = MAX_TOTAL_ENCODED_ICON_BYTES
            iconOwners.forEach { iconByteBudget -= writeIconEntry(zip, it, iconByteBudget) }
        }
    }

    private fun writeEncryptedArchive(
        outputStream: OutputStream,
        backupData: BackupData,
        iconOwners: List<IconOwner>,
        password: CharArray,
    ) {
        val buffer = BoundedBackupBuffer(MAX_PAYLOAD_BYTES - BackupCrypto.TAG_BYTES)
        val inner = try {
            writeArchive(buffer, backupData, iconOwners)
            buffer.toByteArray()
        } finally {
            buffer.clear()
        }
        try {
            val params = BackupCryptoParams.of(BackupCrypto.newSalt(), BackupCrypto.newIv())
            val paramsBytes = prettyJson.encodeToString(params).toByteArray()
            val payload = BackupCrypto.encrypt(inner, password, params, paramsBytes)

            ZipOutputStream(outputStream).use { zip ->
                writeMarker(zip, BackupPolicy.ENCRYPTED_BACKUP_VERSION)
                zip.putNextEntry(ZipEntry(BackupPolicy.CRYPTO_ENTRY))
                zip.write(paramsBytes)
                zip.closeEntry()
                zip.putNextEntry(storedEntry(BackupPolicy.PAYLOAD_ENTRY, payload))
                zip.write(payload)
                zip.closeEntry()
            }
        } finally {
            inner.fill(0)
        }
    }

    private fun storedEntry(name: String, bytes: ByteArray): ZipEntry =
        ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }

    private fun writeMarker(zip: ZipOutputStream, version: String) {
        zip.putNextEntry(ZipEntry(BackupPolicy.MARKER_ENTRY))
        zip.write(version.toByteArray())
        zip.closeEntry()
    }

    private fun writeDataJson(zip: ZipOutputStream, backupData: BackupData) {
        zip.putNextEntry(ZipEntry(BackupPolicy.DATA_ENTRY))
        zip.write(prettyJson.encodeToString(backupData).toByteArray())
        zip.closeEntry()
    }

    // Icons are encoded to the same aggregate budget the reader enforces, so an export
    // never contains more icon bytes than an import would accept.
    private fun writeIconEntry(zip: ZipOutputStream, iconOwner: IconOwner, budget: Int): Int {
        if (!iconOwner.hasCustomIcon) return 0
        val bitmap = BitmapFactory.decodeFile(iconOwner.iconFile.absolutePath) ?: return 0
        val encoded = BoundedBackupBuffer(minOf(MAX_ICON_BYTES, budget))
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)) return 0
            val bytes = encoded.toByteArray()
            zip.putNextEntry(ZipEntry("${BackupPolicy.ICONS_PREFIX}${iconOwner.uuid}.png"))
            zip.write(bytes)
            zip.closeEntry()
            return bytes.size
        } catch (_: IOException) {
            return 0
        } finally {
            encoded.clear()
            bitmap.recycle()
        }
    }

    private fun parseArchive(zip: ZipInputStream): BackupSource {
        var markerVersion: String? = null
        var cryptoBytes: ByteArray? = null
        var payload: ByteArray? = null
        var jsonString: String? = null
        val iconBytes = mutableMapOf<String, ByteArray>()
        var iconByteBudget = MAX_TOTAL_ENCODED_ICON_BYTES

        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            when {
                entry.name == BackupPolicy.MARKER_ENTRY -> {
                    markerVersion = zip.readBounded(MAX_MARKER_BYTES)
                        ?.toString(Charsets.UTF_8)?.trim()
                }

                entry.name == BackupPolicy.CRYPTO_ENTRY -> {
                    cryptoBytes = zip.readBounded(MAX_CRYPTO_BYTES)
                }

                entry.name == BackupPolicy.PAYLOAD_ENTRY -> {
                    payload = zip.readBounded(MAX_PAYLOAD_BYTES)
                }

                entry.name == BackupPolicy.DATA_ENTRY -> {
                    jsonString = zip.readBounded(MAX_DATA_BYTES)?.toString(Charsets.UTF_8)
                }

                entry.name.startsWith(BackupPolicy.ICONS_PREFIX) && entry.name.endsWith(".png") -> {
                    val appUuid =
                        entry.name.removePrefix(BackupPolicy.ICONS_PREFIX).removeSuffix(".png")
                    if (appUuid.isCanonicalUuid() && appUuid !in iconBytes &&
                        iconBytes.size < MAX_ICON_COUNT
                    ) {
                        zip.readBounded(minOf(MAX_ICON_BYTES, iconByteBudget))?.let {
                            iconByteBudget -= it.size
                            iconBytes[appUuid] = it
                        }
                    }
                }
            }
            entry = zip.nextEntry
        }

        if (markerVersion == BackupPolicy.ENCRYPTED_BACKUP_VERSION) {
            if (cryptoBytes == null || payload == null) return BackupSource.Damaged
            val params = try {
                lenientJson.decodeFromString<BackupCryptoParams>(
                    cryptoBytes.toString(Charsets.UTF_8)
                )
            } catch (_: Exception) {
                return BackupSource.Damaged
            }
            if (!params.isSupported()) return BackupSource.Damaged
            return BackupSource.Protected(params, cryptoBytes, payload)
        }

        val parsed = buildParsedBackup(jsonString, decodeIcons(iconBytes), markerVersion)
        return if (parsed == null) BackupSource.NotABackup else BackupSource.Plain(parsed)
    }

    private fun decodeIcons(encoded: Map<String, ByteArray>): Map<String, Bitmap> {
        var budget = MAX_TOTAL_ICON_MEMORY_BYTES
        val icons = mutableMapOf<String, Bitmap>()
        encoded.forEach { (appUuid, bytes) ->
            decodeIconBitmap(bytes, budget)?.let {
                budget -= it.byteCount
                icons[appUuid] = it
            }
        }
        return icons
    }

    // The inner archive carries no crypto entries, so it always classifies as Plain.
    private fun parseZipEntries(zip: ZipInputStream): ParsedBackup? =
        (parseArchive(zip) as? BackupSource.Plain)?.parsed

    private fun buildParsedBackup(
        jsonString: String?,
        icons: Map<String, Bitmap>,
        markerVersion: String?,
    ): ParsedBackup? {
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
        val out = BoundedBackupBuffer(maxBytes)
        val buffer = ByteArray(8 * 1024)
        return try {
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } catch (_: IOException) {
            null
        } finally {
            out.clear()
        }
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
    private const val MAX_CRYPTO_BYTES = 4 * 1024
    private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
    private const val MAX_TOTAL_ENCODED_ICON_BYTES = 32 * 1024 * 1024
    private const val MAX_DATA_BYTES = 32 * 1024 * 1024
    private const val MAX_ICON_BYTES = 4 * 1024 * 1024
    private const val MAX_ICON_COUNT = 1024
    private const val MAX_ICON_DIMENSION_PX = 2048
    private const val MAX_TOTAL_ICON_MEMORY_BYTES = 128 * 1024 * 1024
}
