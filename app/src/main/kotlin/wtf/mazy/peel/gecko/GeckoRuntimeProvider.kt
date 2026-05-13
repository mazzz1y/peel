package wtf.mazy.peel.gecko

import android.content.Context
import android.os.LocaleList
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import wtf.mazy.peel.BuildConfig
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.extensions.ExtensionIconCache
import wtf.mazy.peel.ui.extensions.ExtensionPermissionPrompt
import wtf.mazy.peel.ui.extensions.SessionExtensionActions
import wtf.mazy.peel.util.ForegroundActivityTracker
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GeckoRuntimeProvider {

    const val THEME_COLOR_APP = "themeColor"
    const val PAGE_BRIDGE_APP = "pageBridge"

    private val installMutex = Mutex()

    private val promptScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var themeColorExtension: WebExtension? = null

    @Volatile
    private var pageBridgeExtension: WebExtension? = null

    private val initStarted = AtomicBoolean(false)

    private val extensionStateListeners = CopyOnWriteArraySet<ExtensionStateListener>()

    fun addExtensionStateListener(listener: ExtensionStateListener) {
        extensionStateListeners.add(listener)
    }

    fun removeExtensionStateListener(listener: ExtensionStateListener) {
        extensionStateListeners.remove(listener)
    }

    fun getRuntime(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: createRuntime(context.applicationContext).also { rt ->
                runtime = rt
                setupPromptDelegate(rt)
                setupAddonManagerDelegate(rt, context.applicationContext)
            }
        }
    }

    private suspend fun showPrompt(
        ext: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int,
        @StringRes positiveRes: Int,
        showEvenIfEmpty: Boolean,
    ): Boolean {
        val activity = ForegroundActivityTracker.current as? AppCompatActivity ?: return false
        return ExtensionPermissionPrompt.confirm(
            activity = activity,
            title = activity.getString(titleRes),
            summaryRes = summaryRes,
            ext = ext,
            permissions = permissions,
            origins = origins,
            showEvenIfEmpty = showEvenIfEmpty,
            positiveRes = positiveRes,
        )
    }

    private fun notifyExtensionStateChanged(event: ExtensionStateEvent) {
        SessionExtensionActions.extensionsChanged = true
        extensionStateListeners.forEach { it.onExtensionStateChanged(event) }
    }

    private fun setupAddonManagerDelegate(rt: GeckoRuntime, context: Context) {
        rt.webExtensionController.setAddonManagerDelegate(
            object : WebExtensionController.AddonManagerDelegate {
                override fun onInstalled(extension: WebExtension) {
                    promptScope.launch {
                        ExtensionIconCache.refreshFromExtension(context, extension)
                    }
                    notifyExtensionStateChanged(ExtensionStateEvent.ADDED)
                }

                override fun onUninstalled(extension: WebExtension) {
                    ExtensionIconCache.delete(context, extension.id)
                    notifyExtensionStateChanged(ExtensionStateEvent.REMOVED)
                }

                override fun onDisabled(extension: WebExtension) {
                    notifyExtensionStateChanged(ExtensionStateEvent.TOGGLED)
                }

                override fun onEnabled(extension: WebExtension) {
                    notifyExtensionStateChanged(ExtensionStateEvent.TOGGLED)
                }
            },
        )
    }

    fun initAsync(context: Context, warmUp: Boolean = true) {
        if (!initStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            DataManager.instance.awaitReady()
            try {
                withContext(Dispatchers.Main) {
                    val rt = getRuntime(appContext)
                    if (warmUp) warmUpContentProcess(rt)
                }
            } catch (_: Exception) {
                initStarted.set(false)
            }
        }
    }

    private fun warmUpContentProcess(rt: GeckoRuntime) {
        val session = GeckoSession()
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                session.close()
            }
        }
        session.open(rt)
        session.loadUri("about:blank")
    }

    suspend fun installExtension(context: Context, uri: String): WebExtension =
        installMutex.withLock {
            getRuntime(context).webExtensionController.install(uri).await()
        }

    suspend fun uninstallExtension(context: Context, ext: WebExtension) {
        getRuntime(context).webExtensionController.uninstall(ext).awaitVoid()
    }

    suspend fun updateExtension(context: Context, ext: WebExtension): WebExtension? {
        return getRuntime(context).webExtensionController.update(ext).awaitNullable()
    }

    suspend fun listUserExtensions(context: Context): List<WebExtension> {
        val extensions = getRuntime(context).webExtensionController.list().await()
            .filter { it.id !in BUILT_IN_IDS }
        SessionExtensionActions.ensureExtensionDelegatesRegistered(extensions)
        return extensions
    }

    private fun allowResponse() = WebExtension.PermissionPromptResponse(true, true, true)

    private fun denyResponse() = WebExtension.PermissionPromptResponse(false, false, false)

    private fun setupPromptDelegate(rt: GeckoRuntime) {
        rt.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                promptScope.launch {
                    val allowed = runCatching {
                        showPrompt(
                            extension, permissions, origins,
                            titleRes = R.string.install_extension_confirm_title,
                            summaryRes = R.string.install_extension_permission_summary,
                            positiveRes = R.string.install,
                            showEvenIfEmpty = true,
                        )
                    }.getOrDefault(false)
                    result.complete(if (allowed) allowResponse() else denyResponse())
                }
                return result
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny> {
                val result = GeckoResult<AllowOrDeny>()
                promptScope.launch {
                    val allowed = runCatching {
                        showPrompt(
                            extension, newPermissions, newOrigins,
                            titleRes = R.string.update_extension_confirm_title,
                            summaryRes = R.string.update_extension_permission_summary,
                            positiveRes = R.string.extension_update,
                            showEvenIfEmpty = false,
                        )
                    }.getOrDefault(false)
                    result.complete(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
                return result
            }
        }
    }

    suspend fun ensureThemeColorExtension(context: Context): WebExtension? {
        themeColorExtension?.let { return it }
        return try {
            val ext = getRuntime(context).webExtensionController
                .ensureBuiltIn(THEME_COLOR_URI, THEME_COLOR_ID)
                .await()
            Log.d(TAG, "ensureThemeColorExtension: installed id=${ext.id}")
            themeColorExtension = ext
            ext
        } catch (e: Exception) {
            Log.d(TAG, "ensureThemeColorExtension failed: $e")
            null
        }
    }

    suspend fun ensurePageBridgeExtension(context: Context): WebExtension? {
        pageBridgeExtension?.let { return it }
        return try {
            val ext = getRuntime(context).webExtensionController
                .ensureBuiltIn(PAGE_BRIDGE_URI, PAGE_BRIDGE_ID)
                .await()
            pageBridgeExtension = ext
            ext
        } catch (_: Exception) {
            null
        }
    }

    private fun createRuntime(context: Context): GeckoRuntime {
        val defaults = DataManager.instance.defaultSettings.settings
        val lna = defaults.isBlockLocalNetwork == true
        val antiTracking = when (defaults.isSafeBrowsing) {
            WebAppSettings.TRACKER_PROTECTION_STRICT -> ContentBlocking.AntiTracking.STRICT
            WebAppSettings.TRACKER_PROTECTION_DEFAULT -> ContentBlocking.AntiTracking.DEFAULT
            else -> ContentBlocking.AntiTracking.NONE
        }
        val colorScheme = when (defaults.colorScheme) {
            WebAppSettings.COLOR_SCHEME_LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            WebAppSettings.COLOR_SCHEME_DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
            else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        }
        val builder = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(BuildConfig.DEBUG)
            .extensionsWebAPIEnabled(true)
            .globalPrivacyControlEnabled(defaults.isGlobalPrivacyControl == true)
            .setLnaEnabled(lna)
            .setLnaBlocking(lna)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(antiTracking)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
                    .build()
            )
        val resolvedLocales = if (defaults.isUseCustomLocale == true) {
            parseLocales(defaults.customLocale) ?: systemLocalesAsArray()
        } else {
            systemLocalesAsArray()
        }
        builder.locales(resolvedLocales)
        writeGeckoConfig(context, defaults)?.let { builder.configFilePath(it) }
        val rt = GeckoRuntime.create(context, builder.build())
        rt.settings.setFingerprintingProtection(defaults.isFingerprintingProtection == true)
        rt.settings.preferredColorScheme = colorScheme
        rt.warmUp()
        return rt
    }

    private fun pref(key: String, value: Any) = "  $key: $value"

    private fun parseLocales(raw: String?): Array<String>? {
        val list = raw?.split(',')?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.orEmpty()
        return list.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun systemLocalesAsArray(): Array<String> {
        val list = LocaleList.getDefault()
        if (list.isEmpty) return arrayOf(Locale.getDefault().toLanguageTag())
        return Array(list.size()) { i -> list[i].toLanguageTag() }
    }

    private fun writeGeckoConfig(
        context: Context,
        defaults: WebAppSettings,
    ): String? {
        val prefs = buildList {
            if (defaults.isBlockWebRtcIpLeak == true) {
                add(pref("media.peerconnection.ice.default_address_only", true))
                add(pref("media.peerconnection.ice.no_host", true))
            }
            if (defaults.isDisableQuic == true) {
                add(pref("network.http.http3.enable", false))
            }
            defaults.customGeckoPrefs?.forEach { (rawKey, rawValue) ->
                val key = rawKey.trim()
                val value = rawValue.trim()
                if (key.isEmpty() || value.isEmpty()) return@forEach
                if (!PREF_KEY_REGEX.matches(key)) return@forEach
                add(pref(key, formatPrefValue(value)))
            }
        }
        val file = File(context.filesDir, GECKO_CONFIG_FILE)
        if (prefs.isEmpty()) {
            if (file.exists()) file.delete()
            return null
        }
        file.writeText("prefs:\n" + prefs.joinToString("\n") + "\n")
        return file.absolutePath
    }

    private val PREF_KEY_REGEX = Regex("^[A-Za-z0-9._-]+$")

    private fun formatPrefValue(raw: String): Any {
        if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            return yamlQuote(raw.substring(1, raw.length - 1))
        }
        if (raw.equals("true", ignoreCase = true)) return true
        if (raw.equals("false", ignoreCase = true)) return false
        raw.toLongOrNull()?.let { return it }
        raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
        return yamlQuote(raw)
    }

    private fun yamlQuote(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }

    suspend fun <T> GeckoResult<T>.awaitNullable(): T? = suspendCancellableCoroutine { cont ->
        then(
            { value -> cont.resume(value); GeckoResult() },
            { throwable -> cont.resumeWithException(throwable); GeckoResult<Void>() },
        )
    }

    suspend fun <T> GeckoResult<T>.await(): T =
        awaitNullable() ?: throw NullPointerException("GeckoResult resolved to null")

    suspend fun GeckoResult<Void>.awaitVoid() {
        awaitNullable()
    }

    private val BUILT_IN_IDS = setOf(THEME_COLOR_ID, PAGE_BRIDGE_ID)

    private const val GECKO_CONFIG_FILE = "geckoview-config.yaml"
    private const val TAG = "PeelGecko"
    private const val THEME_COLOR_URI = "resource://android/assets/extensions/theme-color/"
    private const val THEME_COLOR_ID = "theme-color@peel.mazy.wtf"
    private const val PAGE_BRIDGE_URI = "resource://android/assets/extensions/page-bridge/"
    private const val PAGE_BRIDGE_ID = "peel@mazy.wtf"
}
