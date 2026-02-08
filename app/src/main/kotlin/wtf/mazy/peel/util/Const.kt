package wtf.mazy.peel.util

object Const {
    const val DESKTOP_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"

    const val INTENT_WEBAPP_UUID: String = "webappUUID"
    const val INTENT_BACKUP_RESTORED: String = "backup_restored"
    const val INTENT_WEBAPP_CHANGED: String = "webapp_changed"
    const val INTENT_REFRESH_NEW_THEME: String = "theme_changed"
    const val INTENT_AUTO_FETCH: String = "auto_fetch"

    const val GLOBAL_WEBAPP_UUID: String = "00000000-0000-0000-0000-000000000000"

    const val PERMISSION_RC_LOCATION: Int = 123
    const val PERMISSION_RC_STORAGE: Int = 132
    const val PERMISSION_CAMERA: Int = 100
    const val PERMISSION_AUDIO: Int = 101

    const val FAVICON_MIN_WIDTH: Int = 48
}
