package wtf.mazy.peel.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.TranslationLanguages
import wtf.mazy.peel.browser.label
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.entitylist.EntityListActivity
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.translations.TranslationItem
import wtf.mazy.peel.ui.translations.TranslationPlanDialog
import wtf.mazy.peel.ui.translations.TranslationsAdapter
import wtf.mazy.peel.util.NotificationUtils

class TranslationsActivity : EntityListActivity<TranslationItem>() {

    override val titleRes: Int = R.string.translations_title
    override val emptyStateRes: Int = R.string.translations_empty

    override fun subscribeDataChanges(onChange: () -> Unit) = Unit

    private var downloadedItems: List<TranslationItem> = emptyList()
    private var availableItems: List<TranslationItem> = emptyList()
    private var hasLoaded: Boolean = false
    private val loader by lazy { LoadingDialogController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        emptyStateText.visibility = View.GONE
        loader.show(R.string.translations_loading)
        refreshFromEngine()
    }

    override fun onResume() {
        super.onResume()
        refreshFromEngine()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_translations, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_download_all)?.isVisible = availableItems.isNotEmpty()
        menu.findItem(R.id.action_delete_all)?.isVisible = downloadedItems.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_download_all -> {
                confirmDownloadAll(); true
            }

            R.id.action_delete_all -> {
                confirmDeleteAll(); true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun createAdapter(): EntityListAdapter<TranslationItem, *> = TranslationsAdapter(
        checkIconColor = checkIconColor,
        onDownload = ::confirmDownload,
        onTrash = ::confirmDelete,
    )

    override fun loadEntities(): List<TranslationItem> = downloadedItems

    override fun rowEntityUuid(entity: TranslationItem): String = entity.code

    override fun onAddClicked() {
        showAvailablePicker()
    }

    private fun refreshFromEngine() {
        lifecycleScope.launch {
            val models = TranslationLanguages.listModelDownloadStates()
            val mapped = models.mapNotNull { model ->
                val language = model.language ?: return@mapNotNull null
                TranslationItem(
                    code = language.code,
                    displayName = language.label(),
                    isDownloaded = model.isDownloaded,
                )
            }
            val sorted = mapped.sortedBy { it.displayName.lowercase() }
            downloadedItems = sorted.filter { it.isDownloaded }
            availableItems = sorted.filterNot { it.isDownloaded }
            invalidateOptionsMenu()
            if (!hasLoaded) {
                hasLoaded = true
                loader.dismiss()
            }
            refreshList()
        }
    }

    private fun showAvailablePicker() {
        if (availableItems.isEmpty()) {
            NotificationUtils.showToast(this, getString(R.string.translations_picker_empty))
            return
        }
        val items = availableItems
        val labels = items.map { it.displayName }.toTypedArray()
        val checked = BooleanArray(items.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translations_add_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.add) { _, _ ->
                val selected = items.filterIndexed { i, _ -> checked[i] }
                if (selected.isNotEmpty()) confirmDownloadSelection(selected.map { it.code })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDownload(item: TranslationItem) {
        confirmDownloadSelection(listOf(item.code))
    }

    private fun confirmDownloadSelection(codes: List<String>) {
        if (codes.isEmpty()) return
        lifecycleScope.launch {
            val plan = TranslationLanguages.planDownload(codes)
            if (plan.codes.isEmpty()) {
                refreshFromEngine()
                return@launch
            }
            TranslationPlanDialog.showDownload(this@TranslationsActivity, plan) {
                runBatch(plan.codes, RuntimeTranslation.DOWNLOAD)
            }
        }
    }

    private fun confirmDownloadAll() {
        if (availableItems.isEmpty()) return
        confirmDownloadSelection(availableItems.map { it.code })
    }

    private fun confirmDelete(item: TranslationItem) {
        lifecycleScope.launch {
            val plan = TranslationLanguages.planDelete(item.code)
            TranslationPlanDialog.showDelete(
                this@TranslationsActivity,
                item.displayName,
                plan,
            ) {
                if (plan.isLastNonPivot) {
                    runDeleteAll()
                } else {
                    runBatch(listOf(item.code), RuntimeTranslation.DELETE)
                }
            }
        }
    }

    private fun confirmDeleteAll() {
        if (downloadedItems.isEmpty()) return
        TranslationPlanDialog.showDeleteAll(
            this,
            downloadedItems.map { it.code },
        ) { runDeleteAll() }
    }

    private fun runDeleteAll() {
        loader.show(R.string.translation_deleting)
        lifecycleScope.launch {
            val ok = TranslationLanguages.manageAllLanguageModels(RuntimeTranslation.DELETE)
            loader.dismiss()
            if (!ok) {
                NotificationUtils.showToast(
                    this@TranslationsActivity,
                    getString(R.string.translation_delete_failed),
                )
            }
            refreshFromEngine()
        }
    }

    private fun runBatch(codes: List<String>, operation: String) {
        if (codes.isEmpty()) return
        val messageRes = if (operation == RuntimeTranslation.DOWNLOAD) {
            R.string.translation_downloading
        } else {
            R.string.translation_deleting
        }
        loader.show(messageRes)
        lifecycleScope.launch {
            var failures = 0
            for (code in codes) {
                if (!TranslationLanguages.manageLanguageModel(code, operation)) failures++
            }
            loader.dismiss()
            if (failures > 0) {
                val msg = if (operation == RuntimeTranslation.DOWNLOAD) {
                    R.string.translation_download_failed
                } else {
                    R.string.translation_delete_failed
                }
                NotificationUtils.showToast(this@TranslationsActivity, getString(msg))
            }
            refreshFromEngine()
        }
    }
}
