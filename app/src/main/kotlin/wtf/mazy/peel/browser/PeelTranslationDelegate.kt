package wtf.mazy.peel.browser

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController.SessionTranslation
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.BrowserActivity
import java.lang.ref.WeakReference

data class PairKey(val from: String, val to: String) {
    companion object {
        fun of(from: String, to: String): PairKey =
            PairKey(from.langKey(), to.langKey())
    }
}

class PeelTranslationDelegate(activity: BrowserActivity) :
    SessionTranslation.Delegate {

    private val activityRef = WeakReference(activity)

    @Volatile
    var lastTranslationState: SessionTranslation.TranslationState? = null
        private set

    @Volatile
    private var sessionManualTarget: String? = null

    val isPageTranslated: Boolean
        get() = lastTranslationState?.requestedTranslationPair != null

    private sealed interface PageDecision {
        data object Undecided : PageDecision
        data class Translated(val pair: PairKey, val isManual: Boolean) : PageDecision
        data object ShownOriginal : PageDecision
    }

    private data class PageContext(
        val url: String,
        val decision: PageDecision,
        val declinedPairs: Set<PairKey>,
    )

    private data class ActiveRequest(
        val pair: PairKey,
        val isManual: Boolean,
        val ready: CompletableDeferred<Boolean>,
        val job: Job,
    )

    private var page =
        PageContext(url = "", decision = PageDecision.Undecided, declinedPairs = emptySet())
    private var active: ActiveRequest? = null

    override fun onTranslationStateChange(
        session: GeckoSession,
        translationState: SessionTranslation.TranslationState?,
    ) {
        lastTranslationState = translationState
        val activity = activityRef.get() ?: return
        val pair = translationState?.requestedTranslationPair
        activity.setTranslateButtonActive(pair != null)

        completeActiveRequestIfMatching(
            pair,
            translationState?.error,
            translationState?.isEngineReady == true,
        )

        if (pair != null) return
        if (active != null) return
        if (page.decision !is PageDecision.Undecided) return

        val docLang = translationState?.detectedLanguages?.docLangTag ?: return
        if (docLang.isBlank()) return
        val target = resolveAutoTarget(activity, docLang) ?: return
        val pairKey = PairKey.of(docLang, target)
        if (pairKey in page.declinedPairs) return
        startTranslate(session, docLang, target, isManual = false)
    }

    fun onLocationChanged(newUrl: String) {
        if (newUrl == page.url) return
        active?.job?.cancel()
        active = null
        page =
            PageContext(url = newUrl, decision = PageDecision.Undecided, declinedPairs = emptySet())
    }

    fun translateToTarget(session: GeckoSession, fromCode: String, toCode: String) {
        startTranslate(session, fromCode, toCode, isManual = true)
    }

    fun restoreOriginal(session: GeckoSession) {
        val activity = activityRef.get() ?: return
        val activePair = lastTranslationState?.requestedTranslationPair
        val pairToDecline = activePair?.let {
            PairKey.of(it.fromLanguage.orEmpty(), it.toLanguage.orEmpty())
        } ?: (page.decision as? PageDecision.Translated)?.pair
        val declined = buildSet {
            addAll(page.declinedPairs)
            pairToDecline?.let { add(it) }
            val docLang = lastTranslationState?.detectedLanguages?.docLangTag
            if (!docLang.isNullOrBlank()) {
                resolveAutoTarget(activity, docLang)?.let { add(PairKey.of(docLang, it)) }
            }
        }
        page = page.copy(decision = PageDecision.ShownOriginal, declinedPairs = declined)
        sessionManualTarget = null
        active?.job?.cancel()
        active = null
        if (!isPageTranslated) return
        activity.lifecycleScope.launch {
            runCatching { session.sessionTranslation?.restoreOriginalPage() }
        }
    }

    fun cancelActive() {
        active?.job?.cancel()
        active = null
    }

    fun resolveLongPressTarget(docLang: String?): String? {
        if (docLang.isNullOrBlank()) return null
        val activity = activityRef.get() ?: return null
        resolveAutoTarget(activity, docLang)?.let { return it }
        return TranslationLanguages.defaultTranslationTarget(docLang).takeIf { it.isNotBlank() }
    }

    private fun startTranslate(
        session: GeckoSession,
        fromCode: String,
        toCode: String,
        isManual: Boolean,
    ) {
        val activity = activityRef.get() ?: return
        val sessionTranslation = session.sessionTranslation ?: return
        if (active != null) {
            if (!isManual) return
            active?.job?.cancel()
            active = null
        }
        val pairKey = PairKey.of(fromCode, toCode)
        val ready = CompletableDeferred<Boolean>()
        lateinit var request: ActiveRequest
        val job = activity.lifecycleScope.launch {
            try {
                runTranslate(
                    activity,
                    sessionTranslation,
                    fromCode,
                    toCode,
                    pairKey,
                    isManual,
                    ready,
                )
            } finally {
                if (active === request) active = null
            }
        }
        request = ActiveRequest(pair = pairKey, isManual = isManual, ready = ready, job = job)
        active = request
    }

    private suspend fun runTranslate(
        activity: BrowserActivity,
        sessionTranslation: SessionTranslation,
        fromCode: String,
        toCode: String,
        pairKey: PairKey,
        isManual: Boolean,
        ready: CompletableDeferred<Boolean>,
    ) {
        val options = SessionTranslation.TranslationOptions.Builder()
            .downloadModel(true)
            .build()
        val started = runCatching { sessionTranslation.translate(fromCode, toCode, options) }
        if (started.isFailure) return
        val loaderJob = activity.lifecycleScope.launch {
            delay(LOADER_DELAY_MS)
            activity.translationLoader.show(R.string.translation_loading)
        }
        try {
            val modelReady =
                withTimeoutOrNull(MODEL_READY_TIMEOUT_MS) { ready.await() } ?: false
            if (modelReady) {
                page = page.copy(
                    decision = PageDecision.Translated(
                        pair = pairKey,
                        isManual = isManual,
                    ),
                )
                if (isManual) sessionManualTarget = toCode
            }
        } finally {
            loaderJob.cancel()
            activity.translationLoader.dismiss()
        }
    }

    private fun completeActiveRequestIfMatching(
        pair: SessionTranslation.TranslationPair?,
        error: String?,
        engineReady: Boolean,
    ) {
        val current = active ?: return
        if (error != null) {
            current.ready.complete(false)
            return
        }
        if (!engineReady || pair == null) return
        val incoming = PairKey.of(pair.fromLanguage.orEmpty(), pair.toLanguage.orEmpty())
        if (incoming == current.pair) current.ready.complete(true)
    }

    private fun resolveAutoTarget(activity: BrowserActivity, docLang: String): String? {
        sessionManualTarget?.let { sticky ->
            if (!langBaseEqual(docLang, sticky)) return sticky
        }
        if (activity.effectiveSettings.isTranslatorEnabled != true) return null
        val pairs = activity.effectiveSettings.autoTranslatePairs
        return TranslationLanguages.resolveConfiguredTarget(pairs, docLang)
    }

    private companion object {
        const val MODEL_READY_TIMEOUT_MS = 120_000L
        const val LOADER_DELAY_MS = 300L
    }
}
