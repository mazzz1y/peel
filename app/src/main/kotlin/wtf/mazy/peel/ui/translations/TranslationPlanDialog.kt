package wtf.mazy.peel.ui.translations

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.TranslationLanguages
import wtf.mazy.peel.browser.languageDisplayName
import wtf.mazy.peel.util.withBoldSpan
import kotlin.coroutines.resume

object TranslationPlanDialog {

    fun showDownload(
        context: Context,
        plan: TranslationLanguages.DownloadPlan,
        onConfirm: () -> Unit,
    ) {
        val names = plan.codes.map { languageDisplayName(it) }
        buildDialog(
            context = context,
            titleRes = R.string.translation_download_plan_title,
            names = names,
            inlineMessageRes = R.string.translation_download_plan_one,
            listIntroRes = R.string.translation_download_plan_intro,
            positiveRes = R.string.download,
            onPositive = onConfirm,
            onCancel = {},
        ).show()
    }

    fun showDeleteAll(
        context: Context,
        downloadedCodes: List<String>,
        onConfirm: () -> Unit,
    ) {
        if (downloadedCodes.isEmpty()) return
        buildDialog(
            context = context,
            titleRes = R.string.translations_delete_all_title,
            names = downloadedCodes.map { languageDisplayName(it) },
            inlineMessageRes = R.string.translation_delete_plan_one,
            listIntroRes = R.string.translation_delete_plan_intro,
            positiveRes = R.string.delete,
            onPositive = onConfirm,
            onCancel = {},
        ).show()
    }

    fun showDelete(
        context: Context,
        targetName: String,
        plan: TranslationLanguages.DeletePlan,
        onConfirm: () -> Unit,
    ) {
        buildDialog(
            context = context,
            titleRes = R.string.translation_delete_title,
            names = listOf(targetName),
            inlineMessageRes = R.string.translation_delete_plan_one,
            listIntroRes = R.string.translation_delete_plan_intro,
            positiveRes = R.string.delete,
            onPositive = onConfirm,
            onCancel = {},
        ).show()
    }

    suspend fun awaitDownload(
        context: Context,
        plan: TranslationLanguages.DownloadPlan,
    ): Boolean = suspendCancellableCoroutine { cont ->
        var resumed = false
        val dialog = buildDialog(
            context = context,
            titleRes = R.string.translation_download_plan_title,
            names = plan.codes.map { languageDisplayName(it) },
            inlineMessageRes = R.string.translation_download_plan_one,
            listIntroRes = R.string.translation_download_plan_intro,
            positiveRes = R.string.download,
            onPositive = {
                if (!resumed) {
                    resumed = true; cont.resume(true)
                }
            },
            onCancel = {
                if (!resumed) {
                    resumed = true; cont.resume(false)
                }
            },
        )
        cont.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

    private fun buildDialog(
        context: Context,
        @StringRes titleRes: Int,
        names: List<String>,
        @StringRes inlineMessageRes: Int,
        @StringRes listIntroRes: Int,
        @StringRes positiveRes: Int,
        onPositive: () -> Unit,
        onCancel: () -> Unit,
    ): androidx.appcompat.app.AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setPositiveButton(positiveRes) { _, _ -> onPositive() }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }

        if (names.size == 1) {
            val name = names[0]
            val message: CharSequence =
                context.getString(inlineMessageRes, name).withBoldSpan(name)
            builder.setMessage(message)
        } else {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.dialog_translation_plan, null)
            view.findViewById<TextView>(R.id.translationPlanSummary).text =
                context.getString(listIntroRes)
            val listContainer = view.findViewById<LinearLayout>(R.id.translationPlanList)
            for (name in names) {
                val rowView = inflater.inflate(
                    R.layout.item_translation_plan_row, listContainer, false,
                ) as ViewGroup
                rowView.findViewById<TextView>(R.id.translationPlanRowName).text = name
                listContainer.addView(rowView)
            }
            builder.setView(view)
        }

        return builder.create()
    }
}
