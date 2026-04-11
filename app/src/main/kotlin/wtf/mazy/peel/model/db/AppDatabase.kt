package wtf.mazy.peel.model.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WebAppEntity::class, WebAppGroupEntity::class],
    version = 12,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun webAppDao(): WebAppDao

    abstract fun webAppGroupDao(): WebAppGroupDao

    companion object {
        private const val DATABASE_NAME = "peel.db"

        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE webapps ADD COLUMN isDynamicStatusBar INTEGER DEFAULT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE webapp_groups ADD COLUMN isDynamicStatusBar INTEGER DEFAULT NULL"
                    )
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE webapps ADD COLUMN isShowNotification INTEGER DEFAULT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE webapp_groups ADD COLUMN isShowNotification INTEGER DEFAULT NULL"
                    )
                }
            }

        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    for (table in listOf("webapps", "webapp_groups")) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN isUseBasicAuth INTEGER DEFAULT NULL")
                        db.execSQL("ALTER TABLE $table ADD COLUMN basicAuthUsername TEXT DEFAULT NULL")
                        db.execSQL("ALTER TABLE $table ADD COLUMN basicAuthPassword TEXT DEFAULT NULL")
                    }
                }
            }

        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    for (table in listOf("webapps", "webapp_groups")) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN isAppLinksPermission INTEGER DEFAULT NULL")
                    }
                }
            }

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
            "isShowNotification" to "INTEGER",
            "isAppLinksPermission" to "INTEGER",
            "isGlobalPrivacyControl" to "INTEGER",
            "isFingerprintingProtection" to "INTEGER",
            "isBlockLocalNetwork" to "INTEGER",
            "isBlockWebRtcIpLeak" to "INTEGER",
            "isDisableQuic" to "INTEGER",
            "isUseBasicAuth" to "INTEGER",
            "basicAuthUsername" to "TEXT",
            "basicAuthPassword" to "TEXT",
        )

        private val SETTINGS_COLS =
            SETTINGS_COLUMNS.joinToString(",\n") { "${it.first} ${it.second}" }

        private val SETTINGS_COL_NAMES =
            SETTINGS_COLUMNS.joinToString(", ") { it.first }

        private fun ensureSettingsColumns(db: SupportSQLiteDatabase) {
            for (table in listOf("webapps", "webapp_groups")) {
                val cursor = db.query("PRAGMA table_info($table)")
                val existing = mutableSetOf<String>()
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) existing.add(cursor.getString(nameIdx))
                cursor.close()
                for ((col, type) in SETTINGS_COLUMNS) {
                    if (col !in existing) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN $col $type DEFAULT NULL")
                    }
                }
            }
        }

        private fun recreateTablesV9(db: SupportSQLiteDatabase) {
            ensureSettingsColumns(db)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS webapps_new (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    baseUrl TEXT NOT NULL,
                    title TEXT NOT NULL,
                    isActiveEntry INTEGER NOT NULL,
                    isUseContainer INTEGER NOT NULL,
                    isEphemeralSandbox INTEGER NOT NULL,
                    `order` INTEGER NOT NULL,
                    groupUuid TEXT,
                    $SETTINGS_COLS
                )
                """
            )
            db.execSQL(
                """
                INSERT INTO webapps_new (
                    uuid, baseUrl, title, isActiveEntry, isUseContainer,
                    isEphemeralSandbox, `order`, groupUuid, $SETTINGS_COL_NAMES
                )
                SELECT uuid, baseUrl, title, isActiveEntry, isUseContainer,
                    isEphemeralSandbox, `order`, groupUuid, $SETTINGS_COL_NAMES
                FROM webapps
                """
            )
            db.execSQL("DROP TABLE webapps")
            db.execSQL("ALTER TABLE webapps_new RENAME TO webapps")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS webapp_groups_new (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    `order` INTEGER NOT NULL,
                    isUseContainer INTEGER NOT NULL,
                    isEphemeralSandbox INTEGER NOT NULL,
                    $SETTINGS_COLS
                )
                """
            )
            db.execSQL(
                """
                INSERT INTO webapp_groups_new (
                    uuid, title, `order`, isUseContainer, isEphemeralSandbox,
                    $SETTINGS_COL_NAMES
                )
                SELECT uuid, title, `order`, isUseContainer, isEphemeralSandbox,
                    $SETTINGS_COL_NAMES
                FROM webapp_groups
                """
            )
            db.execSQL("DROP TABLE webapp_groups")
            db.execSQL("ALTER TABLE webapp_groups_new RENAME TO webapp_groups")
        }

        val MIGRATION_7_9 =
            object : Migration(7, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    for (table in listOf("webapps", "webapp_groups")) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN colorScheme INTEGER DEFAULT NULL")
                        db.execSQL("ALTER TABLE $table ADD COLUMN isAlgorithmicDarkening INTEGER DEFAULT NULL")
                        db.execSQL("UPDATE $table SET colorScheme = 2, isAlgorithmicDarkening = 1 WHERE isForceDarkMode = 1")
                    }
                    recreateTablesV9(db)
                }
            }

        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    recreateTablesV9(db)
                }
            }

        private fun recreateTablesCanonical(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS sandbox_slots")
            ensureSettingsColumns(db)

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS webapps_new (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    baseUrl TEXT NOT NULL,
                    title TEXT NOT NULL,
                    isUseContainer INTEGER NOT NULL,
                    isEphemeralSandbox INTEGER NOT NULL,
                    `order` INTEGER NOT NULL,
                    groupUuid TEXT,
                    $SETTINGS_COLS
                )
                """
            )
            db.execSQL(
                """
                INSERT INTO webapps_new (
                    uuid, baseUrl, title, isUseContainer,
                    isEphemeralSandbox, `order`, groupUuid, $SETTINGS_COL_NAMES
                )
                SELECT uuid, baseUrl, title, isUseContainer,
                    isEphemeralSandbox, `order`, groupUuid, $SETTINGS_COL_NAMES
                FROM webapps
                """
            )
            db.execSQL("DROP TABLE webapps")
            db.execSQL("ALTER TABLE webapps_new RENAME TO webapps")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS webapp_groups_new (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    `order` INTEGER NOT NULL,
                    isUseContainer INTEGER NOT NULL,
                    isEphemeralSandbox INTEGER NOT NULL,
                    $SETTINGS_COLS
                )
                """
            )
            db.execSQL(
                """
                INSERT INTO webapp_groups_new (
                    uuid, title, `order`, isUseContainer, isEphemeralSandbox,
                    $SETTINGS_COL_NAMES
                )
                SELECT uuid, title, `order`, isUseContainer, isEphemeralSandbox,
                    $SETTINGS_COL_NAMES
                FROM webapp_groups
                """
            )
            db.execSQL("DROP TABLE webapp_groups")
            db.execSQL("ALTER TABLE webapp_groups_new RENAME TO webapp_groups")
        }

        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    recreateTablesCanonical(db)
                }
            }

        val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    recreateTablesCanonical(db)
                }
            }

        val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    recreateTablesCanonical(db)
                }
            }

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
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_9,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                )
                .allowMainThreadQueries()
                .build()
        }
    }
}
