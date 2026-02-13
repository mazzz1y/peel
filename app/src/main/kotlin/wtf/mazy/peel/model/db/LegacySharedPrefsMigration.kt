@file:Suppress("DEPRECATION")

package wtf.mazy.peel.model.db

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSurrogate
import wtf.mazy.peel.util.Const

@Deprecated(
    "Remove after a few releases once all users have migrated from SharedPreferences to Room")
object LegacySharedPrefsMigration {

    private const val OLD_PREF_FILE = "WEBSITEDATA"
    private const val KEY_WEBAPPS = "WEBSITEDATA"
    private const val KEY_GLOBAL_SETTINGS = "GLOBALSETTINGS"
    private const val SANDBOX_UUID_PREFIX = "sandbox_uuid_"

    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun migrate(context: Context, webAppDao: WebAppDao, sandboxSlotDao: SandboxSlotDao) {
        val oldPrefs = context.getSharedPreferences(OLD_PREF_FILE, Context.MODE_PRIVATE)
        if (oldPrefs.all.isEmpty()) return

        migrateWebApps(oldPrefs.getString(KEY_WEBAPPS, null), webAppDao)
        migrateGlobalSettings(oldPrefs.getString(KEY_GLOBAL_SETTINGS, null), webAppDao)
        migrateSandboxSlots(oldPrefs.all, sandboxSlotDao)

        oldPrefs.edit { clear() }
    }

    private fun migrateWebApps(json: String?, dao: WebAppDao) {
        if (json == null) return
        val surrogates =
            try {
                lenientJson.decodeFromString<List<WebAppSurrogate>>(json)
            } catch (_: Exception) {
                return
            }
        if (surrogates.isNotEmpty()) {
            dao.insertAll(surrogates.map { it.toDomain().toEntity() })
        }
    }

    private fun migrateGlobalSettings(json: String?, dao: WebAppDao) {
        if (json == null) return
        val surrogate =
            try {
                lenientJson.decodeFromString<WebAppSurrogate>(json)
            } catch (_: Exception) {
                return
            }
        val globalWebApp =
            WebApp("", Const.GLOBAL_WEBAPP_UUID).apply { settings = surrogate.toDomain().settings }
        dao.upsert(globalWebApp.toEntity())
    }

    private fun migrateSandboxSlots(oldEntries: Map<String, *>, dao: SandboxSlotDao) {
        for ((key, value) in oldEntries) {
            if (!key.startsWith(SANDBOX_UUID_PREFIX)) continue
            val slotId = key.removePrefix(SANDBOX_UUID_PREFIX).toIntOrNull() ?: continue
            val uuid = value as? String ?: continue
            dao.assign(SandboxSlotEntity(slotId = slotId, webappUuid = uuid))
        }
    }
}
