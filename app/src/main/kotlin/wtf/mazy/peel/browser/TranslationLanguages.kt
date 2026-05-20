package wtf.mazy.peel.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.util.App
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TranslationLanguages {

    const val ANY_LANGUAGE = "*"

    @Volatile
    private var cachedSupport: RuntimeTranslation.TranslationSupport? = null

    @Volatile
    private var cachedEngineSupported: Boolean = false

    fun cachedSupport(): RuntimeTranslation.TranslationSupport? = cachedSupport

    fun deviceLanguageTag(): String = Locale.getDefault().toLanguageTag()

    fun defaultTranslationTarget(docLang: String): String {
        val deviceTag = deviceLanguageTag()
        if (deviceTag.isBlank()) return ""
        if (docLang.isNotBlank() && deviceTag.langBase() == docLang.langBase()) return ""
        val supportedTo = cachedSupport()?.toLanguages ?: return ""
        val deviceKey = deviceTag.langKey()
        supportedTo.firstOrNull { it.code.langKey() == deviceKey }?.let { return it.code }
        val deviceBase = deviceTag.langBase()
        return supportedTo.firstOrNull { it.code.langBase() == deviceBase }?.code.orEmpty()
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

    suspend fun manageLanguageModel(languageCode: String, operation: String): Boolean {
        return try {
            ensureRuntime()
            val options = RuntimeTranslation.ModelManagementOptions.Builder()
                .languageToManage(languageCode)
                .operation(operation)
                .operationLevel(RuntimeTranslation.LANGUAGE)
                .build()
            withContext(Dispatchers.Main) {
                RuntimeTranslation.manageLanguageModel(options)
            }.awaitResult()
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun manageAllLanguageModels(operation: String): Boolean {
        return try {
            ensureRuntime()
            val options = RuntimeTranslation.ModelManagementOptions.Builder()
                .operation(operation)
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

    suspend fun listModelDownloadStates(): List<RuntimeTranslation.LanguageModel> {
        return try {
            ensureRuntime()
            withContext(Dispatchers.Main) {
                RuntimeTranslation.listModelDownloadStates()
            }.awaitResult() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun downloadedLanguageCodes(): Set<String> =
        listModelDownloadStates()
            .filter { it.isDownloaded }
            .mapNotNull { it.language?.code }
            .toSet()

    const val PIVOT_LANGUAGE = "en"

    fun joinLanguageLabels(context: Context, codes: List<String>): String {
        val labels = codes.map { languageDisplayName(it) }
        return when (labels.size) {
            0 -> ""
            1 -> labels[0]
            2 -> context.getString(
                R.string.translation_language_list_join, labels[0], labels[1],
            )

            else -> {
                val head = labels.dropLast(1).joinToString(", ")
                context.getString(R.string.translation_language_list_join, head, labels.last())
            }
        }
    }

    data class DownloadPlan(val codes: List<String>)

    data class DeletePlan(val isLastNonPivot: Boolean)

    suspend fun planDownload(requestedCodes: List<String>): DownloadPlan {
        val requested = requestedCodes.filter { it.isNotBlank() }
            .distinctBy { it.langKey() }
        if (requested.isEmpty()) return DownloadPlan(emptyList())
        val models = listModelDownloadStates()
        val downloadedKeys = models.filter { it.isDownloaded }
            .mapNotNull { it.language?.code?.langKey() }
            .toSet()
        val pivotKey = PIVOT_LANGUAGE.langKey()
        val codes = mutableListOf<String>()
        for (code in requested) {
            val key = code.langKey()
            if (key == pivotKey) continue
            if (key in downloadedKeys) continue
            codes += code
        }
        return DownloadPlan(codes)
    }

    suspend fun planDelete(code: String): DeletePlan {
        val key = code.langKey()
        val pivotKey = PIVOT_LANGUAGE.langKey()
        val others = listModelDownloadStates()
            .filter { it.isDownloaded }
            .mapNotNull { it.language?.code }
            .filter { it.langKey() != key }
        val isLastNonPivot = key != pivotKey && others.none { it.langKey() != pivotKey }
        return DeletePlan(isLastNonPivot = isLastNonPivot)
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

fun languageDisplayName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        .takeIf { it.isNotBlank() } ?: code

fun String.langBase(): String = substringBefore('-').lowercase()

fun String.langKey(): String = trim().lowercase()

fun langBaseEqual(a: String?, b: String?): Boolean =
    !a.isNullOrBlank() && !b.isNullOrBlank() && a.langBase() == b.langBase()
