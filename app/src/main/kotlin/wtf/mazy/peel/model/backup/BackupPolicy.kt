package wtf.mazy.peel.model.backup

object BackupPolicy {
    const val PAYLOAD_FULL = "full_backup"
    const val PAYLOAD_APP_SHARE = "app_share"
    const val PAYLOAD_GROUP_SHARE = "group_share"
    const val MIME_TYPE = "application/zip"
    const val LOADER_THRESHOLD = 10
    const val BACKUP_VERSION = "1"
    const val DATA_ENTRY = "data.json"
    const val MARKER_ENTRY = ".peel"
    const val ICONS_PREFIX = "icons/"
    const val SHARE_DIR = "shared_backups"

    fun buildFilename(prefix: String): String = "$prefix.peel.zip"
}
