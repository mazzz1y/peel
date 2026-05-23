package wtf.mazy.peel.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.util.App
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TranslationLanguages {

    const val ANY_LANGUAGE = "*"

    @Volatile
    var cachedSupport: RuntimeTranslation.TranslationSupport? = null
        private set

    @Volatile
    private var cachedEngineSupported: Boolean = false

    private fun deviceLanguageTag(): String = Locale.getDefault().toLanguageTag()

    fun defaultTranslationTarget(docLang: String): String {
        val deviceTag = deviceLanguageTag()
        if (deviceTag.isBlank()) return ""
        val deviceIdentity = deviceTag.langIdentity()
        if (docLang.isNotBlank() && docLang.langIdentity() == deviceIdentity) return ""
        val supportedTo = cachedSupport?.toLanguages ?: return ""
        return supportedTo.firstOrNull { it.code.langIdentity() == deviceIdentity }?.code.orEmpty()
    }

    fun resolveConfiguredTarget(pairs: Map<String, String>?, docLang: String): String? {
        if (pairs.isNullOrEmpty()) return null
        val docBase = docLang.langBase()
        val specific = pairs.entries.firstOrNull { entry ->
            entry.key != ANY_LANGUAGE &&
                    entry.key.langBase() == docBase &&
                    entry.value.isNotBlank() &&
                    !langBaseEqual(docLang, entry.value)
        }
        val chosen = specific ?: pairs.entries.firstOrNull { entry ->
            entry.key == ANY_LANGUAGE &&
                    entry.value.isNotBlank() &&
                    !langBaseEqual(docLang, entry.value)
        } ?: return null
        return chosen.value
    }

    private suspend fun ensureRuntime(context: Context = App.appContext) {
        withContext(Dispatchers.Main) {
            GeckoRuntimeProvider.getRuntime(context)
        }
    }

    suspend fun isEngineSupported(): Boolean {
        if (cachedEngineSupported) return true
        return try {
            ensureRuntime()
            val supported = withContext(Dispatchers.Main) {
                RuntimeTranslation.isTranslationsEngineSupported()
            }.awaitResult() == true
            if (supported) cachedEngineSupported = true
            supported
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun listSupportedLanguages(): RuntimeTranslation.TranslationSupport? {
        cachedSupport?.let { return it }
        return try {
            ensureRuntime()
            val support = withContext(Dispatchers.Main) {
                RuntimeTranslation.listSupportedLanguages()
            }.awaitResult()
            if (support != null) cachedSupport = support
            support
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun downloadedLanguageCodes(): Set<String> {
        return try {
            ensureRuntime()
            val states = withContext(Dispatchers.Main) {
                RuntimeTranslation.listModelDownloadStates()
            }.awaitResult() ?: return emptySet()
            states.filter { it.isDownloaded }
                .mapNotNull { it.language?.code }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    suspend fun deleteAllModels(): Boolean {
        return try {
            ensureRuntime()
            val options = RuntimeTranslation.ModelManagementOptions.Builder()
                .operation(RuntimeTranslation.DELETE)
                .operationLevel(RuntimeTranslation.ALL)
                .build()
            withContext(Dispatchers.Main) {
                RuntimeTranslation.manageLanguageModel(options)
            }.awaitResult()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun <T> GeckoResult<T>.awaitResult(): T? =
        suspendCancellableCoroutine { cont ->
            then(
                { value -> cont.resume(value); GeckoResult() },
                { throwable -> cont.resumeWithException(throwable); GeckoResult<Void>() },
            )
        }
}

fun TranslationsController.Language.label(): String =
    localizedDisplayName?.takeIf { it.isNotBlank() } ?: code

fun String.langBase(): String = substringBefore('-').lowercase()

fun String.langKey(): String = trim().lowercase()

private fun String.langIdentity(): String {
    val locale = Locale.forLanguageTag(this)
    val builder = Locale.Builder().setLanguage(locale.language)
    if (locale.script.isNotEmpty()) builder.setScript(locale.script)
    return builder.build().toLanguageTag().lowercase()
}

fun langBaseEqual(a: String?, b: String?): Boolean =
    !a.isNullOrBlank() && !b.isNullOrBlank() && a.langBase() == b.langBase()
