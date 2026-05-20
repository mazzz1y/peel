package wtf.mazy.peel.ui.dialog

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.BrowserActivity
import wtf.mazy.peel.browser.TranslationLanguages
import wtf.mazy.peel.browser.label
import wtf.mazy.peel.browser.langBase
import wtf.mazy.peel.browser.langKey
import wtf.mazy.peel.ui.bindDropdown
import wtf.mazy.peel.util.NotificationUtils

object TranslateDialog {

    data class Prefill(
        val fromCode: String? = null,
        val toCode: String? = null,
        val showOriginal: Boolean = false,
    )

    fun show(
        activity: BrowserActivity,
        session: GeckoSession,
        prefill: Prefill = Prefill(),
    ) {
        activity.lifecycleScope.launch {
            if (!TranslationLanguages.isEngineSupported()) {
                NotificationUtils.showToast(
                    activity,
                    activity.getString(R.string.translate_error_engine_unavailable),
                )
                return@launch
            }
            val support = TranslationLanguages.listSupportedLanguages()
            if (support == null) {
                NotificationUtils.showToast(
                    activity,
                    activity.getString(R.string.translate_error_languages_unavailable),
                )
                return@launch
            }
            val downloaded = TranslationLanguages.downloadedLanguageCodes()
            showDialog(activity, session, support, downloaded, prefill)
        }
    }

    private fun showDialog(
        activity: BrowserActivity,
        session: GeckoSession,
        support: RuntimeTranslation.TranslationSupport,
        downloaded: Set<String>,
        prefill: Prefill,
    ) {
        val detectedBase = prefill.fromCode?.langBase().orEmpty()
        val fromLanguages = (support.fromLanguages ?: emptyList())
            .sortedWith(detectedThenDownloaded(detectedBase, downloaded))
        val toLanguages = (support.toLanguages ?: emptyList())
            .sortedWith(downloadedFirst(downloaded))
        if (fromLanguages.isEmpty() || toLanguages.isEmpty()) {
            NotificationUtils.showToast(
                activity,
                activity.getString(R.string.translate_error_languages_unavailable),
            )
            return
        }

        val view = activity.layoutInflater.inflate(R.layout.item_language_pair_entry, null)
        val fromBtn = view.findViewById<MaterialButton>(R.id.btnLanguageFrom)
        val toBtn = view.findViewById<MaterialButton>(R.id.btnLanguageTo)
        view.findViewById<MaterialButton>(R.id.btnRemoveEntry).visibility = View.GONE
        val pad =
            activity.resources.getDimensionPixelSize(R.dimen.dialog_content_horizontal_padding)
        val padTop = activity.resources.getDimensionPixelSize(R.dimen.dialog_content_top_padding)
        view.setPadding(pad, padTop, pad, 0)

        var fromIndex = pickFromIndex(fromLanguages, prefill)
        var toIndex = pickToIndex(toLanguages, fromLanguages, fromIndex, prefill)

        fromBtn.bindDropdown(
            items = fromLanguages.map { it.label() },
            currentIndex = { fromIndex },
            onSelected = { i -> fromIndex = i },
        )
        toBtn.bindDropdown(
            items = toLanguages.map { it.label() },
            currentIndex = { toIndex },
            onSelected = { i -> toIndex = i },
        )

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.translate_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.translate_action) { _, _ ->
                val from = fromLanguages.getOrNull(fromIndex)?.code ?: return@setPositiveButton
                val to = toLanguages.getOrNull(toIndex)?.code ?: return@setPositiveButton
                if (from == to) {
                    NotificationUtils.showToast(
                        activity,
                        activity.getString(R.string.translate_error_same_language),
                    )
                    return@setPositiveButton
                }
                activity.translationDelegate?.translateToTarget(session, from, to)
            }
            .setNegativeButton(R.string.cancel, null)
        if (prefill.showOriginal) {
            builder.setNeutralButton(R.string.translate_show_original) { _, _ ->
                activity.translationDelegate?.restoreOriginal(session)
            }
        }
        builder.show()
    }

    private fun downloadedFirst(downloaded: Set<String>): Comparator<TranslationsController.Language> =
        compareBy<TranslationsController.Language> { it.code !in downloaded }
            .thenBy { it.label().lowercase() }

    private fun detectedThenDownloaded(
        detectedBase: String,
        downloaded: Set<String>,
    ): Comparator<TranslationsController.Language> =
        compareBy<TranslationsController.Language> {
            !(detectedBase.isNotBlank() && it.code.equals(detectedBase, ignoreCase = true))
        }
            .thenBy { it.code !in downloaded }
            .thenBy { it.label().lowercase() }

    private fun pickFromIndex(
        fromLanguages: List<TranslationsController.Language>,
        prefill: Prefill,
    ): Int {
        val code = prefill.fromCode ?: return 0
        val key = code.langKey()
        val exact = fromLanguages.indexOfFirst { it.code.langKey() == key }
        if (exact >= 0) return exact
        val base = code.langBase()
        val byBase = fromLanguages.indexOfFirst { it.code.langBase() == base }
        return if (byBase >= 0) byBase else 0
    }

    private fun pickToIndex(
        toLanguages: List<TranslationsController.Language>,
        fromLanguages: List<TranslationsController.Language>,
        fromIndex: Int,
        prefill: Prefill,
    ): Int {
        val fromCode = fromLanguages.getOrNull(fromIndex)?.code
        prefill.toCode?.let { code ->
            val idx = matchLanguageIndex(toLanguages, code, fromCode)
            if (idx >= 0) return idx
        }
        val defaultTarget = TranslationLanguages.defaultTranslationTarget(fromCode.orEmpty())
        if (defaultTarget.isNotBlank()) {
            val idx = matchLanguageIndex(toLanguages, defaultTarget, fromCode)
            if (idx >= 0) return idx
        }
        val fallback = toLanguages.indexOfFirst { it.code != fromCode }
        return if (fallback >= 0) fallback else 0
    }

    private fun matchLanguageIndex(
        languages: List<TranslationsController.Language>,
        code: String,
        excludeCode: String?,
    ): Int {
        val key = code.langKey()
        val exact = languages.indexOfFirst { it.code.langKey() == key && it.code != excludeCode }
        if (exact >= 0) return exact
        val base = code.langBase()
        return languages.indexOfFirst { it.code.langBase() == base && it.code != excludeCode }
    }
}
