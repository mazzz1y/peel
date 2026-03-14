package wtf.mazy.peel.model.backup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object BackupPolicy {
    const val PAYLOAD_FULL = "full_backup"
    const val PAYLOAD_APP_SHARE = "app_share"
    const val PAYLOAD_GROUP_SHARE = "group_share"
    const val MIME_TYPE = "application/x-peel-backup"
    const val LOADER_THRESHOLD = 10
    const val BACKUP_VERSION = "1"
    const val DATA_ENTRY = "data.json"
    const val ICONS_PREFIX = "icons/"
    const val SHARE_DIR = "shared_backups"

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun buildFilename(prefix: String): String {
        val timestamp = LocalDateTime.now().format(timestampFormatter)
        return "${prefix}_$timestamp.peel"
    }
}
