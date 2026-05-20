package wtf.mazy.peel.ui.browser

import wtf.mazy.peel.R
import wtf.mazy.peel.activities.BrowserActivity
import wtf.mazy.peel.browser.TranslationLanguages
import wtf.mazy.peel.browser.TranslationProgress
import wtf.mazy.peel.browser.languageDisplayName
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.translations.TranslationPlanDialog
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.withBoldSpan

class BrowserTranslationProgress(
    private val activity: BrowserActivity,
    private val loader: LoadingDialogController,
) : TranslationProgress {

    override suspend fun confirmDownload(
        plan: TranslationLanguages.DownloadPlan,
    ): Boolean = TranslationPlanDialog.awaitDownload(activity, plan)

    override fun onDownloadStart(plan: TranslationLanguages.DownloadPlan) {
        val joined = TranslationLanguages.joinLanguageLabels(activity, plan.codes)
        var message: CharSequence =
            activity.getString(R.string.translation_downloading_named, joined)
        for (code in plan.codes) {
            message = message.withBoldSpan(languageDisplayName(code))
        }
        loader.showWithMessage(message)
    }

    override fun onDownloadEnd(success: Boolean) {
        loader.dismiss()
        if (!success) {
            NotificationUtils.showToast(
                activity,
                activity.getString(R.string.translation_download_failed),
            )
        }
    }

    override fun onTranslateError() {
        NotificationUtils.showToast(
            activity,
            activity.getString(R.string.translation_download_failed),
        )
    }
}
