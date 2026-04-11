package wtf.mazy.peel.gecko

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import wtf.mazy.peel.BuildConfig
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppSettings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GeckoRuntimeProvider {

    const val THEME_COLOR_APP = "themeColor"
    const val PAGE_INFO_APP = "pageInfo"

    @Volatile
    var installPromptHandler: (suspend (
        WebExtension,
        Array<String>,
        Array<String>,
    ) -> Boolean)? = null

    @Volatile
    var updatePromptHandler: (suspend (
        WebExtension,
        Array<String>,
        Array<String>,
    ) -> Boolean)? = null

    private val installMutex = Mutex()

    private var promptJob = SupervisorJob()
    private var promptScope = CoroutineScope(promptJob + Dispatchers.Main.immediate)

    fun cancelPromptScope() {
        promptJob.cancel()
        promptJob = SupervisorJob()
        promptScope = CoroutineScope(promptJob + Dispatchers.Main.immediate)
    }

    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var themeColorExtension: WebExtension? = null

    @Volatile
    private var pageInfoExtension: WebExtension? = null

    private val initStarted = AtomicBoolean(false)

    fun getRuntime(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: createRuntime(context.applicationContext).also { rt ->
                runtime = rt
                setupPromptDelegate(rt)
            }
        }
    }

    fun initAsync(context: Context) {
        if (!initStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            DataManager.instance.awaitReady()
            try {
                getRuntime(appContext)
            } catch (_: Exception) {
                initStarted.set(false)
            }
        }
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
        return getRuntime(context).webExtensionController.list().await()
            .filter { it.id !in BUILT_IN_IDS }
    }

    private fun allowResponse() = WebExtension.PermissionPromptResponse(true, true, true)

    private fun denyResponse() = WebExtension.PermissionPromptResponse(false, false, false)

    private fun setupPromptDelegate(rt: GeckoRuntime) {
        rt.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val handler = installPromptHandler
                    ?: return GeckoResult.fromValue(denyResponse())
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                promptScope.launch {
                    val allowed = try {
                        handler(extension, permissions, origins)
                    } catch (_: Exception) {
                        false
                    }
                    result.complete(if (allowed) allowResponse() else denyResponse())
                }
                return result
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny>? {
                val handler = updatePromptHandler
                    ?: return GeckoResult.fromValue(AllowOrDeny.DENY)
                val result = GeckoResult<AllowOrDeny>()
                promptScope.launch {
                    val allowed = try {
                        handler(extension, newPermissions, newOrigins)
                    } catch (_: Exception) {
                        false
                    }
                    result.complete(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
                return result
            }
        })
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

    suspend fun ensurePageInfoExtension(context: Context): WebExtension? {
        pageInfoExtension?.let { return it }
        return try {
            val ext = getRuntime(context).webExtensionController
                .ensureBuiltIn(PAGE_INFO_URI, PAGE_INFO_ID)
                .await()
            pageInfoExtension = ext
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
        val builder = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(BuildConfig.DEBUG)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
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
        writeGeckoConfig(context, defaults)?.let { builder.configFilePath(it) }
        val rt = GeckoRuntime.create(context, builder.build())
        rt.settings.setFingerprintingProtection(defaults.isFingerprintingProtection == true)
        return rt
    }

    private fun writeGeckoConfig(
        context: Context,
        defaults: WebAppSettings,
    ): String? {
        val prefs = buildList {
            if (defaults.isBlockWebRtcIpLeak == true) {
                add("  media.peerconnection.ice.default_address_only: true")
                add("  media.peerconnection.ice.no_host: true")
            }

        }
        if (prefs.isEmpty()) return null
        val file = File(context.filesDir, GECKO_CONFIG_FILE)
        file.writeText("prefs:\n" + prefs.joinToString("\n") + "\n")
        return file.absolutePath
    }

    suspend fun <T> GeckoResult<T>.awaitNullable(): T? = suspendCancellableCoroutine { cont ->
        then(
            { value -> cont.resume(value); GeckoResult<Void>() },
            { throwable -> cont.resumeWithException(throwable); GeckoResult<Void>() },
        )
    }

    suspend fun <T> GeckoResult<T>.await(): T =
        awaitNullable() ?: throw NullPointerException("GeckoResult resolved to null")

    suspend fun GeckoResult<Void>.awaitVoid() {
        awaitNullable()
    }

    private val BUILT_IN_IDS = setOf(THEME_COLOR_ID, PAGE_INFO_ID)

    private const val GECKO_CONFIG_FILE = "geckoview-config.yaml"
    private const val TAG = "PeelGecko"
    private const val THEME_COLOR_URI = "resource://android/assets/extensions/theme-color/"
    private const val THEME_COLOR_ID = "theme-color@peel.mazy.wtf"
    private const val PAGE_INFO_URI = "resource://android/assets/extensions/page-info/"
    private const val PAGE_INFO_ID = "peel@mazy.wtf"
}
