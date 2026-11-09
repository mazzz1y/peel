package wtf.mazy.peel.model.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import wtf.mazy.peel.model.WebAppSettings

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun everyVersionKeepsStoredSettings() {
        (FIRST_VERSION until CURRENT_VERSION).forEach { version ->
            val name = "migration-$version.db"
            helper.createDatabase(name, version).use { db -> seed(db, version) }
            helper.runMigrationsAndValidate(name, CURRENT_VERSION, true, *AppDatabase.MIGRATIONS)
                .use { db -> verify(db, version) }
        }
    }

    private fun seed(db: SupportSQLiteDatabase, version: Int) {
        val webApp = columnsOf(db, "webapps")
        insert(
            db, "webapps", webApp,
            "uuid" to WEB_APP,
            "baseUrl" to "https://example.com/",
            "title" to "Example",
            "isActiveEntry" to 1,
            "isUseContainer" to 0,
            "isEphemeralSandbox" to 0,
            "order" to 0,
            "groupUuid" to GROUP,
            "isAllowJs" to 0,
            "isDynamicStatusBar" to 1,
            "isShowNotification" to 1,
            "basicAuthUsername" to "user",
        )
        if (version < 2) return
        insert(
            db, "webapp_groups", columnsOf(db, "webapp_groups"),
            "uuid" to GROUP,
            "title" to "Work",
            "order" to 0,
            "isUseContainer" to 1,
            "isEphemeralSandbox" to 0,
            "isKeepAwake" to 1,
            "isShowNotification" to 0,
        )
    }

    private fun verify(db: SupportSQLiteDatabase, version: Int) {
        val webApp = row(db, "webapps", WEB_APP)
        assertEquals("v$version baseUrl", "https://example.com/", webApp["baseUrl"])
        assertEquals("v$version title", "Example", webApp["title"])
        assertEquals("v$version isAllowJs", "0", webApp["isAllowJs"])
        if (version >= 2) assertEquals("v$version groupUuid", GROUP, webApp["groupUuid"])
        if (version >= 3) assertEquals("v$version isDynamicStatusBar", "1", webApp["isDynamicStatusBar"])
        if (version >= 6) assertEquals("v$version basicAuthUsername", "user", webApp["basicAuthUsername"])
        if (version in SHOW_NOTIFICATION_VERSIONS) {
            assertEquals(
                "v$version web app browserControlsMode",
                WebAppSettings.BROWSER_CONTROLS_BUTTON.toString(),
                webApp["browserControlsMode"],
            )
        }
        if (version < 2) return
        val group = row(db, "webapp_groups", GROUP)
        assertEquals("v$version group title", "Work", group["title"])
        assertEquals("v$version group isUseContainer", "1", group["isUseContainer"])
        assertEquals("v$version group isKeepAwake", "1", group["isKeepAwake"])
        if (version in SHOW_NOTIFICATION_VERSIONS) {
            assertEquals(
                "v$version group browserControlsMode",
                WebAppSettings.BROWSER_CONTROLS_OFF.toString(),
                group["browserControlsMode"],
            )
        }
    }

    private fun insert(
        db: SupportSQLiteDatabase,
        table: String,
        existing: Set<String>,
        vararg values: Pair<String, Any>,
    ) {
        val present = values.filter { it.first in existing }
        val columns = present.joinToString(", ") { "`${it.first}`" }
        val marks = present.joinToString(", ") { "?" }
        db.execSQL(
            "INSERT INTO $table ($columns) VALUES ($marks)",
            present.map { it.second }.toTypedArray(),
        )
    }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private fun row(db: SupportSQLiteDatabase, table: String, uuid: String): Map<String, String?> =
        db.query("SELECT * FROM $table WHERE uuid = ?", arrayOf(uuid)).use { cursor ->
            check(cursor.moveToFirst()) { "$table row $uuid missing" }
            cursor.columnNames.associateWith { column ->
                val index = cursor.getColumnIndexOrThrow(column)
                if (cursor.isNull(index)) null else cursor.getString(index)
            }
        }

    private companion object {
        const val FIRST_VERSION = 1
        val CURRENT_VERSION = AppDatabase.MIGRATIONS.maxOf { it.endVersion }
        val SHOW_NOTIFICATION_VERSIONS = 5..22
        const val WEB_APP = "11111111-1111-4111-8111-111111111111"
        const val GROUP = "22222222-2222-4222-8222-222222222222"
    }
}
