package wtf.mazy.peel.model.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.util.Const

class StringMapConverter {
    @TypeConverter
    fun fromStringMap(map: Map<String, String>?): String? {
        return map?.let { Json.encodeToString(it) }
    }

    @TypeConverter
    fun toStringMap(json: String?): Map<String, String>? {
        if (json == null) return null
        return Json.decodeFromString<Map<String, String>>(json)
    }
}

class StringListConverter {
    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        return list?.let { Json.encodeToString(it) }
    }

    @TypeConverter
    fun toStringList(json: String?): List<String>? {
        if (json == null) return null
        return Json.decodeFromString<List<String>>(json)
    }
}

@Database(
    entities = [
        WebAppEntity::class,
        WebAppGroupEntity::class,
        ProxyEntity::class,
        PushSubscriptionEntity::class,
    ],
    version = 28,
    exportSchema = true,
)
@TypeConverters(StringMapConverter::class, StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun webAppDao(): WebAppDao

    abstract fun webAppGroupDao(): WebAppGroupDao

    abstract fun proxyDao(): ProxyDao

    abstract fun pushSubscriptionDao(): PushSubscriptionDao

    companion object {
        private const val DATABASE_NAME = "peel.db"

        @Volatile
        private var instance: AppDatabase? = null

        private val SETTINGS_COLUMNS = listOf(
            "isOpenUrlExternal" to "INTEGER",
            "isAllowJs" to "INTEGER",
            "isRequestDesktop" to "INTEGER",
            "isClearCache" to "INTEGER",
            "isAlwaysHttps" to "INTEGER",
            "isAllowLocationAccess" to "INTEGER",
            "isAutoReload" to "INTEGER",
            "timeAutoReload" to "INTEGER",
            "colorScheme" to "INTEGER",
            "webContentZoom" to "INTEGER",
            "isDrmAllowed" to "INTEGER",
            "isShowFullscreen" to "INTEGER",
            "isKeepAwake" to "INTEGER",
            "isCameraPermission" to "INTEGER",
            "isMicrophonePermission" to "INTEGER",
            "isBiometricProtection" to "INTEGER",
            "isAllowMediaPlaybackInBackground" to "INTEGER",
            "isLongClickShare" to "INTEGER",
            "isShowProgressbar" to "INTEGER",
            "isDisableScreenshots" to "INTEGER",
            "isPullToRefresh" to "INTEGER",
            "isSafeBrowsing" to "INTEGER",
            "isDynamicStatusBar" to "INTEGER",
            "browserControlsMode" to "INTEGER",
            "isAppLinksPermission" to "INTEGER",
            "isGlobalPrivacyControl" to "INTEGER",
            "isFingerprintingProtection" to "INTEGER",
            "isBlockLocalNetwork" to "INTEGER",
            "isBlockWebRtcIpLeak" to "INTEGER",
            "isDisableQuic" to "INTEGER",
            "isDisableEch" to "INTEGER",
            "isUseSystemCerts" to "INTEGER",
            "isUseBasicAuth" to "INTEGER",
            "basicAuthUsername" to "TEXT",
            "basicAuthPassword" to "TEXT",
            "isUseCustomUserAgent" to "INTEGER",
            "customUserAgent" to "TEXT",
            "isUseCustomLocale" to "INTEGER",
            "customLocale" to "TEXT",
            "customGeckoPrefs" to "TEXT",
            "isAllowCertBypass" to "INTEGER",
            "isTranslatorEnabled" to "INTEGER",
            "autoTranslatePairs" to "TEXT",
            "sameAppDomains" to "TEXT",
            "blockedDomains" to "TEXT",
            "skipHistoryDomains" to "TEXT",
            "trustedCertificates" to "TEXT",
        )

        private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> {
            val cursor = db.query("PRAGMA table_info($table)")
            val existing = mutableSetOf<String>()
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) existing.add(cursor.getString(nameIdx))
            cursor.close()
            return existing
        }

        private fun ensureSettingsColumns(db: SupportSQLiteDatabase) {
            for (table in listOf("webapps", "webapp_groups")) {
                val existing = tableColumns(db, table)
                for ((col, type) in SETTINGS_COLUMNS) {
                    if (col !in existing) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN $col $type DEFAULT NULL")
                    }
                }
            }
        }

        // Pre-26 clients could end up with duplicate `order` values within the same group:
        // drag-reorder wrote a dense 0..n-1 range per group, and moving an app between groups
        // never renumbered it, so it kept carrying its old value into the destination. This
        // renumbers every table into a single dense, gap-free sequence per scope (webapps within
        // their groupUuid, groups amongst themselves) so `order` is unique within its scope again.
        private fun renumberOrderColumn(
            db: SupportSQLiteDatabase,
            table: String,
            excludeUuid: String? = null,
            scopeColumn: String? = null,
        ) {
            val where = if (excludeUuid != null) "WHERE uuid != ?" else ""
            val whereArgs = if (excludeUuid != null) arrayOf<Any>(excludeUuid) else emptyArray()
            val scopeSelect = scopeColumn ?: "NULL"
            val rows = mutableListOf<Pair<String, String?>>()
            db.query(
                "SELECT uuid, $scopeSelect FROM $table $where ORDER BY $scopeSelect, `order`, uuid",
                whereArgs,
            ).use {
                while (it.moveToNext()) {
                    rows.add(it.getString(0) to if (it.isNull(1)) null else it.getString(1))
                }
            }
            var previousScope: String? = ""
            var nextOrder = 0
            for ((uuid, scope) in rows) {
                if (scope != previousScope) {
                    previousScope = scope
                    nextOrder = 0
                }
                db.execSQL(
                    "UPDATE $table SET `order` = ? WHERE uuid = ?",
                    arrayOf<Any>(nextOrder, uuid),
                )
                nextOrder++
            }
        }

        private val WEBAPP_BASE_COLUMNS = listOf(
            "uuid" to "TEXT NOT NULL PRIMARY KEY",
            "baseUrl" to "TEXT NOT NULL",
            "title" to "TEXT NOT NULL",
            "isUseContainer" to "INTEGER NOT NULL",
            "isEphemeralSandbox" to "INTEGER NOT NULL",
            "`order`" to "INTEGER NOT NULL",
            "groupUuid" to "TEXT",
        )

        private val GROUP_BASE_COLUMNS = listOf(
            "uuid" to "TEXT NOT NULL PRIMARY KEY",
            "title" to "TEXT NOT NULL",
            "`order`" to "INTEGER NOT NULL",
            "isUseContainer" to "INTEGER NOT NULL",
            "isEphemeralSandbox" to "INTEGER NOT NULL",
        )

        private val PROXY_COLUMN = "proxyUuid" to "TEXT"

        private fun recreateTables(
            db: SupportSQLiteDatabase,
            webappColumns: List<Pair<String, String>>,
            groupColumns: List<Pair<String, String>>,
        ) {
            recreateTable(db, "webapps", webappColumns + SETTINGS_COLUMNS)
            recreateTable(db, "webapp_groups", groupColumns + SETTINGS_COLUMNS)
        }

        private fun recreateTable(
            db: SupportSQLiteDatabase,
            table: String,
            columns: List<Pair<String, String>>,
        ) {
            val definitions = columns.joinToString(",\n") { "${it.first} ${it.second}" }
            val names = columns.joinToString(", ") { it.first }
            db.execSQL("CREATE TABLE IF NOT EXISTS ${table}_new (\n$definitions\n)")
            db.execSQL("INSERT INTO ${table}_new ($names) SELECT $names FROM $table")
            db.execSQL("DROP TABLE $table")
            db.execSQL("ALTER TABLE ${table}_new RENAME TO $table")
        }

        private fun recreateTablesV9(db: SupportSQLiteDatabase) {
            ensureSettingsColumns(db)
            val webappColumns = WEBAPP_BASE_COLUMNS.toMutableList()
                .apply { add(3, "isActiveEntry" to "INTEGER NOT NULL") }
            recreateTables(db, webappColumns, GROUP_BASE_COLUMNS)
        }

        private fun recreateTablesCanonical(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS sandbox_slots")
            ensureSettingsColumns(db)
            recreateTables(db, WEBAPP_BASE_COLUMNS, GROUP_BASE_COLUMNS)
        }

        private fun recreateTablesWithProxy(db: SupportSQLiteDatabase) {
            recreateTables(db, WEBAPP_BASE_COLUMNS + PROXY_COLUMN, GROUP_BASE_COLUMNS + PROXY_COLUMN)
        }

        private fun migration(from: Int, to: Int, body: (SupportSQLiteDatabase) -> Unit): Migration =
            object : Migration(from, to) {
                override fun migrate(db: SupportSQLiteDatabase) = body(db)
            }

        private fun settingsColumnsMigration(from: Int, to: Int): Migration =
            migration(from, to, ::ensureSettingsColumns)

        private val MIGRATIONS = arrayOf(
            migration(1, 2) { db ->
                db.execSQL("ALTER TABLE webapps ADD COLUMN groupUuid TEXT DEFAULT NULL")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS webapp_groups (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    `order` INTEGER NOT NULL DEFAULT 0,
                    isUseContainer INTEGER NOT NULL DEFAULT 0,
                    isEphemeralSandbox INTEGER NOT NULL DEFAULT 0,
                    isOpenUrlExternal INTEGER DEFAULT NULL,
                    isAllowCookies INTEGER DEFAULT NULL,
                    isAllowThirdPartyCookies INTEGER DEFAULT NULL,
                    isAllowJs INTEGER DEFAULT NULL,
                    isRequestDesktop INTEGER DEFAULT NULL,
                    isClearCache INTEGER DEFAULT NULL,
                    isBlockImages INTEGER DEFAULT NULL,
                    isAlwaysHttps INTEGER DEFAULT NULL,
                    isAllowLocationAccess INTEGER DEFAULT NULL,
                    customHeaders TEXT DEFAULT NULL,
                    isAutoReload INTEGER DEFAULT NULL,
                    timeAutoReload INTEGER DEFAULT NULL,
                    isForceDarkMode INTEGER DEFAULT NULL,
                    isUseTimespanDarkMode INTEGER DEFAULT NULL,
                    timespanDarkModeBegin TEXT DEFAULT NULL,
                    timespanDarkModeEnd TEXT DEFAULT NULL,
                    isIgnoreSslErrors INTEGER DEFAULT NULL,
                    isBlockThirdPartyRequests INTEGER DEFAULT NULL,
                    isDrmAllowed INTEGER DEFAULT NULL,
                    isShowFullscreen INTEGER DEFAULT NULL,
                    isKeepAwake INTEGER DEFAULT NULL,
                    isCameraPermission INTEGER DEFAULT NULL,
                    isMicrophonePermission INTEGER DEFAULT NULL,
                    isEnableZooming INTEGER DEFAULT NULL,
                    isBiometricProtection INTEGER DEFAULT NULL,
                    isAllowMediaPlaybackInBackground INTEGER DEFAULT NULL,
                    isLongClickShare INTEGER DEFAULT NULL,
                    isShowProgressbar INTEGER DEFAULT NULL,
                    isDisableScreenshots INTEGER DEFAULT NULL,
                    isPullToRefresh INTEGER DEFAULT NULL,
                    isSafeBrowsing INTEGER DEFAULT NULL
                )"""
                )
            },
            migration(2, 3) { db ->
                db.execSQL(
                    "ALTER TABLE webapps ADD COLUMN isDynamicStatusBar INTEGER DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE webapp_groups ADD COLUMN isDynamicStatusBar INTEGER DEFAULT NULL"
                )
            },
            migration(3, 4) { db ->
                val tables = listOf("webapps", "webapp_groups")
                val columns =
                    listOf(
                        "isAllowLocationAccess", "isCameraPermission", "isMicrophonePermission"
                    )
                for (table in tables) {
                    for (col in columns) {
                        db.execSQL("UPDATE $table SET $col = 2 WHERE $col = 1")
                    }
                }
            },
            migration(4, 5) { db ->
                db.execSQL(
                    "ALTER TABLE webapps ADD COLUMN isShowNotification INTEGER DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE webapp_groups ADD COLUMN isShowNotification INTEGER DEFAULT NULL"
                )
            },
            migration(5, 6) { db ->
                for (table in listOf("webapps", "webapp_groups")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN isUseBasicAuth INTEGER DEFAULT NULL")
                    db.execSQL("ALTER TABLE $table ADD COLUMN basicAuthUsername TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE $table ADD COLUMN basicAuthPassword TEXT DEFAULT NULL")
                }
            },
            migration(6, 7) { db ->
                for (table in listOf("webapps", "webapp_groups")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN isAppLinksPermission INTEGER DEFAULT NULL")
                }
            },
            migration(7, 9) { db ->
                for (table in listOf("webapps", "webapp_groups")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN colorScheme INTEGER DEFAULT NULL")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isAlgorithmicDarkening INTEGER DEFAULT NULL")
                    db.execSQL("UPDATE $table SET colorScheme = 2, isAlgorithmicDarkening = 1 WHERE isForceDarkMode = 1")
                }
                recreateTablesV9(db)
            },
            migration(8, 9, ::recreateTablesV9),
            migration(9, 10, ::recreateTablesCanonical),
            migration(10, 11, ::recreateTablesCanonical),
            migration(11, 12, ::recreateTablesCanonical),
            settingsColumnsMigration(12, 13),
            settingsColumnsMigration(13, 14),
            settingsColumnsMigration(14, 15),
            settingsColumnsMigration(15, 16),
            migration(16, 17) { db ->
                ensureSettingsColumns(db)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS proxies (
                        uuid TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        type INTEGER NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        username TEXT,
                        password TEXT,
                        remoteDns INTEGER NOT NULL,
                        bypassList TEXT NOT NULL
                    )
                    """
                )
                for (table in listOf("webapps", "webapp_groups")) {
                    if ("proxyUuid" !in tableColumns(db, table)) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN proxyUuid TEXT DEFAULT NULL")
                    }
                }
            },
            settingsColumnsMigration(17, 18),
            settingsColumnsMigration(18, 19),
            settingsColumnsMigration(19, 20),
            migration(20, 21) { db ->
                ensureSettingsColumns(db)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS push_subscriptions (
                        instance TEXT NOT NULL PRIMARY KEY,
                        contextId TEXT,
                        scope TEXT NOT NULL,
                        endpoint TEXT NOT NULL,
                        appServerKey TEXT
                    )
                    """
                )
            },
            settingsColumnsMigration(21, 22),
            migration(22, 23) { db ->
                ensureSettingsColumns(db)
                for (table in listOf("webapps", "webapp_groups")) {
                    if ("isShowNotification" !in tableColumns(db, table)) continue
                    db.execSQL(
                        """
                        UPDATE $table SET browserControlsMode =
                            CASE isShowNotification
                                WHEN 1 THEN ${WebAppSettings.BROWSER_CONTROLS_BUTTON}
                                WHEN 0 THEN ${WebAppSettings.BROWSER_CONTROLS_OFF}
                            END
                        WHERE isShowNotification IS NOT NULL
                        """
                    )
                }
                recreateTablesWithProxy(db)
            },
            settingsColumnsMigration(23, 24),
            settingsColumnsMigration(24, 25),
            migration(25, 26) { db ->
                renumberOrderColumn(
                    db,
                    table = "webapps",
                    excludeUuid = Const.GLOBAL_WEBAPP_UUID,
                    scopeColumn = "groupUuid",
                )
                renumberOrderColumn(db, table = "webapp_groups")
            },
            settingsColumnsMigration(26, 27),
            settingsColumnsMigration(27, 28),
        )

        fun getInstance(context: Context): AppDatabase {
            return instance
                ?: synchronized(this) { instance ?: buildDatabase(context).also { instance = it } }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
