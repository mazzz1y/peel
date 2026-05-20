package wtf.mazy.peel.browser

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController.SessionTranslation
import wtf.mazy.peel.activities.BrowserActivity
import java.lang.ref.WeakReference

interface TranslationProgress {
    suspend fun confirmDownload(plan: TranslationLanguages.DownloadPlan): Boolean
    fun onDownloadStart(plan: TranslationLanguages.DownloadPlan)
    fun onDownloadEnd(success: Boolean)
    fun onTranslateError()
}

class PeelTranslationDelegate(activity: BrowserActivity) :
    SessionTranslation.Delegate {

    private val activityRef = WeakReference(activity)

    @Volatile
    var lastTranslationState: SessionTranslation.TranslationState? = null
        private set

    private val declinedAutoPairs: MutableSet<String> = mutableSetOf()
    private var lastNavigatedUrl: String? = null

    @Volatile
    var sessionManualTarget: String? = null
        private set

    val isPageTranslated: Boolean
        get() = lastTranslationState?.requestedTranslationPair != null

    private var translationCompletion: CompletableDeferred<Boolean>? = null
    private var pendingTranslationPair: Pair<String, String>? = null
    private var activeRunTranslate: Boolean = false
    private var downloadInFlight: Boolean = false
    private var pendingIsManual: Boolean = false

    override fun onTranslationStateChange(
        session: GeckoSession,
        translationState: SessionTranslation.TranslationState?,
    ) {
        lastTranslationState = translationState
        val activity = activityRef.get() ?: return
        val pair = translationState?.requestedTranslationPair
        val isAlreadyTranslated = pair != null
        activity.runOnUiThread { activity.setTranslateButtonActive(isAlreadyTranslated) }

        val error = translationState?.error
        val engineReady = translationState?.isEngineReady == true
        val pending = pendingTranslationPair
        val matchesPending = pending != null && pair != null &&
                pair.fromLanguage?.langKey() == pending.first.langKey() &&
                pair.toLanguage?.langKey() == pending.second.langKey()

        if (error != null && pending != null) {
            translationCompletion?.complete(false)
            translationCompletion = null
            pendingTranslationPair = null
        } else if (engineReady && matchesPending) {
            translationCompletion?.complete(true)
            translationCompletion = null
            pendingTranslationPair = null
        }

        if (isAlreadyTranslated) return
        if (maybeSessionTranslate(session, translationState)) return
        maybeAutoTranslate(session, translationState)
    }

    fun onLocationChanged(newUrl: String) {
        val previous = lastNavigatedUrl
        if (previous != null && previous == newUrl) return
        lastNavigatedUrl = newUrl
        declinedAutoPairs.clear()
        sessionManualTarget = null
        if (downloadInFlight) cancelPendingDownload()
    }

    fun translateToTarget(session: GeckoSession, fromCode: String, toCode: String) {
        translateInternal(session, fromCode, toCode, isAutomatic = false)
    }

    private fun translateInternal(
        session: GeckoSession,
        fromCode: String,
        toCode: String,
        isAutomatic: Boolean,
    ) {
        val activity = activityRef.get() ?: return
        val sessionTranslation = session.sessionTranslation ?: return
        if (activeRunTranslate || downloadInFlight) cancelPendingDownload()
        if (!isAutomatic) sessionManualTarget = toCode
        runTranslate(activity, sessionTranslation, fromCode, toCode, isAutomatic)
    }

    fun restoreOriginal(session: GeckoSession) {
        val activity = activityRef.get() ?: return
        if (downloadInFlight) cancelPendingDownload()
        val pair = lastTranslationState?.requestedTranslationPair
        val from = pair?.fromLanguage
        val to = pair?.toLanguage ?: sessionManualTarget
        if (!from.isNullOrBlank() && !to.isNullOrBlank()) {
            declinedAutoPairs += pairKey(from, to)
        }
        val docLang = lastTranslationState?.detectedLanguages?.docLangTag
        val autoTarget = if (!docLang.isNullOrBlank()) {
            TranslationLanguages.resolveConfiguredTarget(
                activity.effectiveSettings.autoTranslatePairs, docLang,
            )
        } else null
        if (!docLang.isNullOrBlank() && !autoTarget.isNullOrBlank()) {
            declinedAutoPairs += pairKey(docLang, autoTarget)
        }
        sessionManualTarget = null
        if (!isPageTranslated) return
        activity.lifecycleScope.launch {
            runCatching { session.sessionTranslation?.restoreOriginalPage() }
        }
    }

    fun cancelPendingDownload() {
        val wasDownloading = downloadInFlight
        val wasManual = pendingIsManual
        downloadInFlight = false
        activeRunTranslate = false
        pendingIsManual = false
        translationCompletion?.complete(false)
        translationCompletion = null
        pendingTranslationPair = null
        if (wasManual && !isPageTranslated) sessionManualTarget = null
        if (wasDownloading) {
            activityRef.get()?.translationProgress?.onDownloadEnd(success = false)
        }
    }

    private fun maybeSessionTranslate(
        session: GeckoSession,
        state: SessionTranslation.TranslationState?,
    ): Boolean {
        val target = sessionManualTarget ?: return false
        val docLang = state?.detectedLanguages?.docLangTag
        if (docLang.isNullOrBlank()) return false
        if (langBaseEqual(docLang, target)) return false
        if (pairKey(docLang, target) in declinedAutoPairs) return true
        session.sessionTranslation ?: return false
        translateInternal(session, docLang, target, isAutomatic = true)
        return true
    }

    private fun maybeAutoTranslate(
        session: GeckoSession,
        state: SessionTranslation.TranslationState?,
    ) {
        val activity = activityRef.get() ?: return
        if (activity.effectiveSettings.isTranslatorEnabled != true) return
        val pairs = activity.effectiveSettings.autoTranslatePairs
        val docLang = state?.detectedLanguages?.docLangTag
        if (docLang.isNullOrBlank()) return
        val target = TranslationLanguages.resolveConfiguredTarget(pairs, docLang) ?: return
        if (pairKey(docLang, target) in declinedAutoPairs) return
        session.sessionTranslation ?: return
        translateInternal(session, docLang, target, isAutomatic = true)
    }

    private fun runTranslate(
        activity: BrowserActivity,
        sessionTranslation: SessionTranslation,
        fromCode: String,
        toCode: String,
        isAutomatic: Boolean,
    ) {
        if (activeRunTranslate) return
        activeRunTranslate = true
        val progress = activity.translationProgress
        activity.lifecycleScope.launch {
            try {
                val plan = TranslationLanguages.planDownload(listOf(fromCode, toCode))
                val needsDownload = plan.codes.isNotEmpty()
                if (needsDownload) {
                    if (isAutomatic && pairKey(fromCode, toCode) in declinedAutoPairs) {
                        return@launch
                    }
                    if (!progress.confirmDownload(plan)) {
                        if (isAutomatic) declinedAutoPairs += pairKey(fromCode, toCode)
                        return@launch
                    }
                }
                val completion = CompletableDeferred<Boolean>()
                if (needsDownload) {
                    downloadInFlight = true
                    pendingIsManual = !isAutomatic
                    pendingTranslationPair = fromCode to toCode
                    translationCompletion?.complete(false)
                    translationCompletion = completion
                    progress.onDownloadStart(plan)
                }
                val options = SessionTranslation.TranslationOptions.Builder()
                    .downloadModel(true)
                    .build()
                val started =
                    runCatching { sessionTranslation.translate(fromCode, toCode, options) }
                if (started.isFailure) {
                    val wasManual = !isAutomatic
                    pendingTranslationPair = null
                    if (translationCompletion === completion) translationCompletion = null
                    completion.complete(false)
                    if (downloadInFlight) {
                        downloadInFlight = false
                        pendingIsManual = false
                        progress.onDownloadEnd(success = false)
                    }
                    if (wasManual && !isPageTranslated) sessionManualTarget = null
                    progress.onTranslateError()
                    return@launch
                }
                var modelReady = true
                if (needsDownload) {
                    modelReady = withTimeoutOrNull(MODEL_READY_TIMEOUT_MS) {
                        completion.await()
                    } ?: false
                    if (!modelReady) {
                        if (translationCompletion === completion) translationCompletion = null
                        pendingTranslationPair = null
                        completion.complete(false)
                    }
                    if (downloadInFlight) {
                        downloadInFlight = false
                        pendingIsManual = false
                        progress.onDownloadEnd(modelReady)
                    }
                    if (!modelReady && !isAutomatic && !isPageTranslated) {
                        sessionManualTarget = null
                    }
                }
            } finally {
                activeRunTranslate = false
            }
        }
    }

    private fun pairKey(fromCode: String, toCode: String): String =
        "${fromCode.langKey()}->${toCode.langKey()}"

    private companion object {
        const val MODEL_READY_TIMEOUT_MS = 120_000L
    }
}
