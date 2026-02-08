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
import wtf.mazy.peel.BuildConfig
import wtf.mazy.peel.R
import wtf.mazy.peel.model.deserializer.WebAppDeserializer
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.ShortcutIconUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
        // Webapp data
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
        return getWebAppIgnoringGlobalOverride(uuid, false)
    }

    fun getWebAppIgnoringGlobalOverride(uuid: String, ignoreOverride: Boolean): WebApp? {
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
        // With nullable settings, we no longer copy global settings
        // effectiveSettings property handles the fallback at runtime
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
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                    sdf.timeZone = TimeZone.getTimeZone("UTC")

                    val manifest =
                        mapOf(
                            "version" to "1.0",
                            "exportDate" to sdf.format(Date()),
                            "appVersion" to BuildConfig.VERSION_NAME,
                            "webApps" to
                                websites.map { webApp ->
                                    mapOf(
                                        "uuid" to webApp.uuid,
                                        "title" to webApp.title,
                                        "baseUrl" to webApp.baseUrl,
                                        "hasCustomIcon" to webApp.hasCustomIcon,
                                        "customIconPath" to webApp.customIconPath,
                                    )
                                },
                        )

                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(gson.toJson(manifest).toByteArray())
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("data.json"))
                    val data = mapOf("websites" to websites, "defaultSettings" to defaultSettings)
                    zip.write(gson.toJson(data).toByteArray())
                    zip.closeEntry()

                    websites.forEach { webApp ->
                        if (webApp.hasCustomIcon && webApp.customIconPath != null) {
                            try {
                                val iconFile = java.io.File(webApp.customIconPath!!)
                                if (iconFile.exists()) {
                                    val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                                    if (bitmap != null) {
                                        zip.putNextEntry(ZipEntry("icons/${webApp.uuid}.png"))
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                                        zip.closeEntry()
                                        bitmap.recycle()
                                    }
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
                    var manifest: Map<String, Any>? = null
                    var data: Map<String, Any>? = null
                    val icons = mutableMapOf<String, Bitmap>()

                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            "manifest.json" -> {
                                val json = zip.readBytes().toString(Charsets.UTF_8)
                                val type = object : TypeToken<Map<String, Any>>() {}.type
                                manifest = Gson().fromJson(json, type)
                            }

                            "data.json" -> {
                                val json = zip.readBytes().toString(Charsets.UTF_8)
                                val type = object : TypeToken<Map<String, Any>>() {}.type
                                data = Gson().fromJson(json, type)
                            }

                            else -> {
                                if (entry.name.startsWith("icons/") &&
                                    entry.name.endsWith(".png")) {
                                    val appUuid =
                                        entry.name.substringAfter("icons/").substringBefore(".png")
                                    val bitmap = BitmapFactory.decodeStream(zip)
                                    if (bitmap != null) {
                                        icons[appUuid] = bitmap
                                    }
                                }
                            }
                        }
                        entry = zip.nextEntry
                    }

                    val version = manifest?.get("version") as? String
                    if (version != "1.0") {
                        Log.e("Import", "Unsupported backup version: $version (expected 1.0)")
                        return false
                    }

                    if (data == null) {
                        Log.e("Import", "Missing data in backup")
                        return false
                    }

                    @Suppress("UNCHECKED_CAST")
                    val websitesJson = data["websites"] as? List<Map<String, Any>>

                    @Suppress("UNCHECKED_CAST")
                    val defaultSettingsJson = data["defaultSettings"] as? Map<String, Any>

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

                    if (defaultSettingsJson != null) {
                        val gson =
                            GsonBuilder()
                                .registerTypeAdapter(WebApp::class.java, WebAppDeserializer())
                                .create()
                        defaultSettings =
                            gson.fromJson(Gson().toJson(defaultSettingsJson), WebApp::class.java)
                    }

                    @Suppress("UNCHECKED_CAST")
                    val webAppsManifest = manifest["webApps"] as? List<Map<String, Any>>
                    webAppsManifest?.forEach { appManifest ->
                        val uuid = appManifest["uuid"] as? String
                        val title = appManifest["title"] as? String
                        val hasCustomIcon = appManifest["hasCustomIcon"] as? Boolean ?: false

                        if (uuid != null && title != null) {
                            websites
                                .find { it.uuid == uuid }
                                ?.let { webApp ->
                                    webApp.title = title
                                    webApp.hasCustomIcon = hasCustomIcon
                                    if (hasCustomIcon) {
                                        icons[uuid]?.let { bitmap ->
                                            saveIconForWebApp(webApp, bitmap)
                                        }
                                    }
                                }
                        }
                    }

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

    private fun saveIconForWebApp(webApp: WebApp, bitmap: Bitmap) {
        try {
            val iconFile = java.io.File(App.appContext.filesDir, "icons/${webApp.uuid}.png")
            iconFile.parentFile?.mkdirs()
            java.io.FileOutputStream(iconFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            webApp.customIconPath = iconFile.absolutePath
            webApp.hasCustomIcon = true
        } catch (e: Exception) {
            Log.w("DataManager", "Failed to save icon for webapp ${webApp.uuid}", e)
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

        @JvmField val instance = DataManager()
    }
}
