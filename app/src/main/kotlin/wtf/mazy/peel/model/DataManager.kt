package wtf.mazy.peel.model

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import wtf.mazy.peel.R
import wtf.mazy.peel.shortcut.ShortcutIconUtils
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const

class DataManager private constructor() {
    private var websites = ArrayList<WebApp>()
    private var appdata: SharedPreferences? = null

    var defaultSettings: WebApp = createDefaultSettings()
        set(value) {
            field = value
            saveDefaultSettings()
        }

    private fun createDefaultSettings(): WebApp {
        val webapp = WebApp("", Const.GLOBAL_WEBAPP_UUID)
        webapp.settings = WebAppSettings.createWithDefaults()
        return webapp
    }

    fun saveWebAppData() {
        appdata = App.appContext.getSharedPreferences(SHARED_PREF_KEY, Context.MODE_PRIVATE)
        appdata?.edit {
            val gson = Gson()
            val json = gson.toJson(websites)
            putString(SHARED_PREF_WEBAPPDATA, json)
        }
    }

    private fun checkIfWebAppUuidsCollide(
        oldWebApps: ArrayList<WebApp>,
        newWebApps: ArrayList<WebApp>,
    ) {
        val shortcutsToBeRemoved = ArrayList<String>()

        for (newWebApp in newWebApps) {
            val oldWebApp = oldWebApps.find { it.uuid == newWebApp.uuid }
            if (oldWebApp != null && oldWebApp.baseUrl != newWebApp.baseUrl) {
                shortcutsToBeRemoved.add(newWebApp.uuid)
            }
        }

        ShortcutIconUtils.deleteShortcuts(shortcutsToBeRemoved, App.appContext)
    }

    fun loadAppData() {
        appdata = App.appContext.getSharedPreferences(SHARED_PREF_KEY, Context.MODE_PRIVATE)
        appdata?.let { pref ->
            if (pref.contains(SHARED_PREF_WEBAPPDATA)) {
                val gson =
                    GsonBuilder()
                        .registerTypeAdapter(WebApp::class.java, WebAppDeserializer())
                        .create()
                val json = pref.getString(SHARED_PREF_WEBAPPDATA, "") ?: ""
                val newWebsites =
                    gson.fromJson<ArrayList<WebApp>>(
                        json, object : TypeToken<ArrayList<WebApp?>?>() {}.type)
                checkIfWebAppUuidsCollide(websites, newWebsites)
                websites = newWebsites
            }

            if (pref.contains(SHARED_PREF_GLOBALSETTINGS)) {
                val gson =
                    GsonBuilder()
                        .registerTypeAdapter(WebApp::class.java, WebAppDeserializer())
                        .create()
                val json = pref.getString(SHARED_PREF_GLOBALSETTINGS, "") ?: ""
                defaultSettings = gson.fromJson(json, WebApp::class.java)
                assertDefaultSettingsData()
            }
        }
    }

    fun saveDefaultSettings() {
        appdata = App.appContext.getSharedPreferences(SHARED_PREF_KEY, Context.MODE_PRIVATE)
        appdata?.edit {
            val gson = Gson()
            val json = gson.toJson(defaultSettings)
            putString(SHARED_PREF_GLOBALSETTINGS, json)
        }
    }

    fun addWebsite(newSite: WebApp) {
        websites.add(newSite)
        saveWebAppData()
    }

    val incrementedOrder: Int
        get() = activeWebsitesCount + 1

    fun getWebsites(): ArrayList<WebApp> = websites

    val activeWebsites: ArrayList<WebApp>
        get() = websites.filter { it.isActiveEntry }.sortedBy { it.order }.toCollection(ArrayList())

    fun getWebApp(uuid: String): WebApp? {
        val webApp = websites.find { it.uuid == uuid }
        if (webApp == null) {
            Toast.makeText(
                    App.appContext,
                    App.appContext.getString(R.string.webapp_not_found),
                    Toast.LENGTH_LONG,
                )
                .apply {
                    setGravity(Gravity.TOP, 0, 100)
                    show()
                }
            return null
        }
        return webApp
    }

    fun replaceWebApp(webapp: WebApp) {
        val index = websites.indexOfFirst { it.uuid == webapp.uuid }
        if (index >= 0) {
            websites[index] = webapp
            saveWebAppData()
        }
    }

    val activeWebsitesCount: Int
        get() = websites.count { it.isActiveEntry }

    fun exportToZip(uri: Uri?): Boolean {
        if (uri == null) return false
        try {
            App.appContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zip ->
                    val gson = GsonBuilder().setPrettyPrinting().create()

                    val data =
                        mapOf(
                            "version" to BACKUP_VERSION,
                            "websites" to websites,
                            "globalSettings" to defaultSettings.settings,
                        )
                    zip.putNextEntry(ZipEntry("data.json"))
                    zip.write(gson.toJson(data).toByteArray())
                    zip.closeEntry()

                    websites.forEach { webApp ->
                        if (webApp.hasCustomIcon) {
                            try {
                                val bitmap = BitmapFactory.decodeFile(webApp.iconFile.absolutePath)
                                if (bitmap != null) {
                                    zip.putNextEntry(ZipEntry("icons/${webApp.uuid}.png"))
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                                    zip.closeEntry()
                                    bitmap.recycle()
                                }
                            } catch (e: Exception) {
                                Log.w(
                                    "Export", "Failed to export icon for webapp ${webApp.uuid}", e)
                            }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("DataManager", "Failed to export to ZIP", e)
            return false
        }
    }

    fun importFromZip(uri: Uri?): Boolean {
        if (uri == null) return false
        try {
            App.appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var data: Map<String, Any>? = null
                    val icons = mutableMapOf<String, Bitmap>()

                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "data.json" -> {
                                val json = zip.readBytes().toString(Charsets.UTF_8)
                                val type = object : TypeToken<Map<String, Any>>() {}.type
                                data = Gson().fromJson(json, type)
                            }

                            entry.name.startsWith("icons/") && entry.name.endsWith(".png") -> {
                                val appUuid =
                                    entry.name.substringAfter("icons/").substringBefore(".png")
                                val bitmap = BitmapFactory.decodeStream(zip)
                                if (bitmap != null) {
                                    icons[appUuid] = bitmap
                                }
                            }
                        }
                        entry = zip.nextEntry
                    }

                    if (data == null) {
                        Log.e("Import", "Missing data.json in backup")
                        return false
                    }

                    val version = data["version"] as? String
                    if (version != BACKUP_VERSION) {
                        Log.e("Import", "Unsupported backup version: $version")
                        return false
                    }

                    @Suppress("UNCHECKED_CAST")
                    val websitesJson = data["websites"] as? List<Map<String, Any>>

                    @Suppress("UNCHECKED_CAST")
                    val globalSettingsJson = data["globalSettings"] as? Map<String, Any>

                    if (websitesJson != null) {
                        val gson =
                            GsonBuilder()
                                .registerTypeAdapter(WebApp::class.java, WebAppDeserializer())
                                .create()
                        websites =
                            websitesJson
                                .map { gson.fromJson(Gson().toJson(it), WebApp::class.java) }
                                .toCollection(ArrayList())
                    }

                    if (globalSettingsJson != null) {
                        val gson = Gson()
                        defaultSettings.settings =
                            gson.fromJson(
                                gson.toJson(globalSettingsJson), WebAppSettings::class.java)
                    }

                    icons.forEach { (uuid, bitmap) -> saveIconForWebApp(uuid, bitmap) }

                    saveWebAppData()
                    saveDefaultSettings()
                    loadAppData()
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            Log.e("DataManager", "Failed to import from ZIP", e)
            return false
        }
    }

    private fun saveIconForWebApp(uuid: String, bitmap: Bitmap) {
        try {
            val iconFile = java.io.File(App.appContext.filesDir, "icons/${uuid}.png")
            iconFile.parentFile?.mkdirs()
            java.io.FileOutputStream(iconFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } catch (e: Exception) {
            Log.w("DataManager", "Failed to save icon for webapp $uuid", e)
        }
    }

    private fun assertDefaultSettingsData() {
        ensureDefaultSettingsAreConcrete()
    }

    private fun ensureDefaultSettingsAreConcrete() {
        val hadNulls =
            WebAppSettings.DEFAULTS.keys.any { defaultSettings.settings.getValue(it) == null }
        defaultSettings.settings.ensureAllConcrete()
        if (hadNulls) saveDefaultSettings()
    }

    companion object {
        private const val SHARED_PREF_KEY = "WEBSITEDATA"
        private const val SHARED_PREF_WEBAPPDATA = "WEBSITEDATA"
        private const val SHARED_PREF_GLOBALSETTINGS = "GLOBALSETTINGS"
        private const val BACKUP_VERSION = "1"

        @JvmField val instance = DataManager()
    }
}
