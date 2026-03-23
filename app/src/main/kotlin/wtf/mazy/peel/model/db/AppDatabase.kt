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

class StringMapConverter {
    @TypeConverter
    fun fromStringMap(map: MutableMap<String, String>?): String? {
        return map?.let { Json.encodeToString(it) }
    }

    @TypeConverter
    fun toStringMap(json: String?): MutableMap<String, String>? {
        if (json == null) return null
        return Json.decodeFromString<Map<String, String>>(json).toMutableMap()
    }
}

@Database(
    entities = [WebAppEntity::class, SandboxSlotEntity::class, WebAppGroupEntity::class],
    version = 10,
    exportSchema = true,
)
@TypeConverters(StringMapConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun webAppDao(): WebAppDao

    abstract fun sandboxSlotDao(): SandboxSlotDao

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

        private val SETTINGS_COLS =
            """
            isOpenUrlExternal INTEGER,
            isAllowCookies INTEGER,
            isAllowThirdPartyCookies INTEGER,
            isAllowJs INTEGER,
            isRequestDesktop INTEGER,
            isClearCache INTEGER,
            isBlockImages INTEGER,
            isAlwaysHttps INTEGER,
            isAllowLocationAccess INTEGER,
            customHeaders TEXT,
            isAutoReload INTEGER,
            timeAutoReload INTEGER,
            colorScheme INTEGER,
            isAlgorithmicDarkening INTEGER,
            isIgnoreSslErrors INTEGER,
            isBlockThirdPartyRequests INTEGER,
            isDrmAllowed INTEGER,
            isShowFullscreen INTEGER,
            isKeepAwake INTEGER,
            isCameraPermission INTEGER,
            isMicrophonePermission INTEGER,
            isEnableZooming INTEGER,
            isBiometricProtection INTEGER,
            isAllowMediaPlaybackInBackground INTEGER,
            isLongClickShare INTEGER,
            isShowProgressbar INTEGER,
            isDisableScreenshots INTEGER,
            isPullToRefresh INTEGER,
            isSafeBrowsing INTEGER,
            isDynamicStatusBar INTEGER,
            isShowNotification INTEGER,
            isAppLinksPermission INTEGER,
            isUseBasicAuth INTEGER,
            basicAuthUsername TEXT,
            basicAuthPassword TEXT
            """.trimIndent()

        private val SETTINGS_COL_NAMES =
            listOf(
                "isOpenUrlExternal",
                "isAllowCookies",
                "isAllowThirdPartyCookies",
                "isAllowJs",
                "isRequestDesktop",
                "isClearCache",
                "isBlockImages",
                "isAlwaysHttps",
                "isAllowLocationAccess",
                "customHeaders",
                "isAutoReload",
                "timeAutoReload",
                "colorScheme",
                "isAlgorithmicDarkening",
                "isIgnoreSslErrors",
                "isBlockThirdPartyRequests",
                "isDrmAllowed",
                "isShowFullscreen",
                "isKeepAwake",
                "isCameraPermission",
                "isMicrophonePermission",
                "isEnableZooming",
                "isBiometricProtection",
                "isAllowMediaPlaybackInBackground",
                "isLongClickShare",
                "isShowProgressbar",
                "isDisableScreenshots",
                "isPullToRefresh",
                "isSafeBrowsing",
                "isDynamicStatusBar",
                "isShowNotification",
                "isAppLinksPermission",
                "isUseBasicAuth",
                "basicAuthUsername",
                "basicAuthPassword",
            ).joinToString(", ")

        private fun recreateTablesV9(db: SupportSQLiteDatabase) {
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

        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                )
                .allowMainThreadQueries()
                .build()
        }
    }
}
