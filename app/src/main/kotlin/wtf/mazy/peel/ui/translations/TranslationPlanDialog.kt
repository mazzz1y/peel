package wtf.mazy.peel.ui.translations

import android.content.Context
import android.text.format.Formatter
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

    data class Entry(val name: String, val sizeBytes: Long)

    fun showDownload(
        context: Context,
        plan: TranslationLanguages.DownloadPlan,
        onConfirm: () -> Unit,
    ) {
        buildDialog(
            context = context,
            titleRes = R.string.translation_download_plan_title,
            entries = downloadEntries(plan),
            inlineMessageRes = R.string.translation_download_plan_one,
            listIntroRes = R.string.translation_download_plan_intro,
            positiveRes = R.string.download,
            onPositive = onConfirm,
            onCancel = {},
        ).show()
    }

    fun showDeleteAll(
        context: Context,
        downloadedItems: List<TranslationItem>,
        onConfirm: () -> Unit,
    ) {
        if (downloadedItems.isEmpty()) return
        buildDialog(
            context = context,
            titleRes = R.string.translations_delete_all_title,
            entries = downloadedItems.map { Entry(it.displayName, it.sizeBytes) },
            inlineMessageRes = R.string.translation_delete_plan_one,
            listIntroRes = R.string.translation_delete_plan_intro,
            positiveRes = R.string.delete,
            onPositive = onConfirm,
            onCancel = {},
        ).show()
    }

    fun showDelete(
        context: Context,
        item: TranslationItem,
        plan: TranslationLanguages.DeletePlan,
        onConfirm: () -> Unit,
    ) {
        buildDialog(
            context = context,
            titleRes = R.string.translation_delete_title,
            entries = listOf(Entry(item.displayName, item.sizeBytes)),
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
            entries = downloadEntries(plan),
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

    private fun downloadEntries(plan: TranslationLanguages.DownloadPlan): List<Entry> =
        plan.codes.map { code ->
            Entry(languageDisplayName(code), plan.sizes[code] ?: 0L)
        }

    private fun labelFor(context: Context, entry: Entry): String =
        if (entry.sizeBytes > 0) {
            "${entry.name} (${Formatter.formatShortFileSize(context, entry.sizeBytes)})"
        } else {
            entry.name
        }

    private fun buildDialog(
        context: Context,
        @StringRes titleRes: Int,
        entries: List<Entry>,
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

        if (entries.size == 1) {
            val label = labelFor(context, entries[0])
            val message: CharSequence =
                context.getString(inlineMessageRes, label).withBoldSpan(entries[0].name)
            builder.setMessage(message)
        } else {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.dialog_translation_plan, null)
            view.findViewById<TextView>(R.id.translationPlanSummary).text =
                context.getString(listIntroRes)
            val listContainer = view.findViewById<LinearLayout>(R.id.translationPlanList)
            for (entry in entries) {
                val rowView = inflater.inflate(
                    R.layout.item_translation_plan_row, listContainer, false,
                ) as ViewGroup
                rowView.findViewById<TextView>(R.id.translationPlanRowName).text =
                    labelFor(context, entry)
                listContainer.addView(rowView)
            }
            builder.setView(view)
        }

        return builder.create()
    }
}
