package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.entitylist.EntityListActivity
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.extensions.AmoExtensionsRepository
import wtf.mazy.peel.ui.extensions.AmoExtensionsRepository.AmoExtension
import wtf.mazy.peel.ui.extensions.ExtensionAdapter
import wtf.mazy.peel.ui.extensions.ExtensionIconCache
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.withBoldSpan
import java.io.File

class ExtensionsActivity : EntityListActivity<WebExtension>() {

    override val titleRes: Int = R.string.extensions
    override val emptyStateRes: Int = R.string.no_extensions_installed

    override fun subscribeDataChanges(onChange: () -> Unit) = Unit

    private val loader: LoadingDialogController by lazy { LoadingDialogController(this) }

    private var cachedRecommended: List<AmoExtension>? = null
    private var lastLoadedExtensions: List<WebExtension> = emptyList()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val tempFile = withContext(Dispatchers.IO) {
                val input = contentResolver.openInputStream(uri) ?: return@withContext null
                val file = File.createTempFile("extension-", ".xpi", cacheDir)
                input.use { src ->
                    file.outputStream().use { dst -> src.copyTo(dst) }
                }
                file
            }
            if (tempFile == null) {
                Log.w(TAG, "filePicker: could not open $uri")
                NotificationUtils.showToast(
                    this@ExtensionsActivity,
                    getString(R.string.install_extension_error),
                    Toast.LENGTH_SHORT,
                )
                return@launch
            }
            installExtension("file://${tempFile.absolutePath}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fab.setOnLongClickListener {
            filePickerLauncher.launch(
                arrayOf("application/x-xpinstall", "application/octet-stream")
            )
            true
        }
    }

    override fun createAdapter(): EntityListAdapter<WebExtension, *> = ExtensionAdapter(
        context = this,
        checkIconColor = checkIconColor,
        onUpdate = ::updateExtension,
        onSettings = {
            startActivity(ExtensionPageActivity.intentForExtension(this, it.id))
        },
        onUninstall = ::confirmUninstall,
    )

    override fun loadEntities(): List<WebExtension> = lastLoadedExtensions

    override fun rowEntityUuid(entity: WebExtension): String = entity.id

    override fun onAddClicked() {
        showAvailableExtensions()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_extensions, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_update_all -> {
                updateAllExtensions(); true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        loadInstalledExtensions()
    }

    override fun onDestroy() {
        loader.dismiss()
        super.onDestroy()
    }

    private fun loadInstalledExtensions() {
        lifecycleScope.launch {
            lastLoadedExtensions = GeckoRuntimeProvider.listUserExtensions(this@ExtensionsActivity)
            refreshList()
        }
    }

    private fun updateExtension(ext: WebExtension) {
        lifecycleScope.launch {
            try {
                val updated =
                    GeckoRuntimeProvider.updateExtension(this@ExtensionsActivity, ext)
                if (updated != null) {
                    ExtensionIconCache.refreshFromExtension(this@ExtensionsActivity, updated)
                    val version = updated.metaData.version
                    NotificationUtils.showToast(
                        this@ExtensionsActivity,
                        getString(R.string.extension_updated_to, version),
                        Toast.LENGTH_SHORT,
                    )
                    loadInstalledExtensions()
                } else {
                    NotificationUtils.showToast(
                        this@ExtensionsActivity,
                        getString(R.string.extension_already_up_to_date),
                        Toast.LENGTH_SHORT,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "updateExtension failed for ${ext.id}", e)
                NotificationUtils.showToast(
                    this@ExtensionsActivity,
                    getString(R.string.install_extension_error),
                    Toast.LENGTH_SHORT,
                )
            }
        }
    }

    private fun updateAllExtensions() {
        lifecycleScope.launch {
            loader.show(R.string.loading_extensions)
            var updated = 0
            var upToDate = 0
            var failed = 0
            val extensions = GeckoRuntimeProvider.listUserExtensions(this@ExtensionsActivity)
            for (ext in extensions) {
                try {
                    val result = GeckoRuntimeProvider.updateExtension(
                        this@ExtensionsActivity, ext,
                    )
                    if (result != null) {
                        ExtensionIconCache.refreshFromExtension(this@ExtensionsActivity, result)
                        updated++
                    } else {
                        upToDate++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "updateAllExtensions: ${ext.id} failed", e)
                    failed++
                }
            }
            loader.dismiss()
            val message = if (failed > 0) {
                getString(R.string.extensions_update_summary_with_failed, updated, upToDate, failed)
            } else {
                getString(R.string.extensions_update_summary, updated, upToDate)
            }
            NotificationUtils.showToast(
                this@ExtensionsActivity,
                message,
                Toast.LENGTH_SHORT,
            )
            loadInstalledExtensions()
        }
    }

    private fun confirmUninstall(ext: WebExtension) {
        val name = ext.metaData.name ?: ext.id
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.uninstall_extension_confirm, name).withBoldSpan(name))
            .setPositiveButton(R.string.uninstall_extension) { _, _ ->
                lifecycleScope.launch {
                    try {
                        GeckoRuntimeProvider.uninstallExtension(
                            this@ExtensionsActivity, ext,
                        )
                        ExtensionIconCache.delete(this@ExtensionsActivity, ext.id)
                        loadInstalledExtensions()
                    } catch (e: Exception) {
                        Log.w(TAG, "uninstallExtension failed for ${ext.id}", e)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAvailableExtensions() {
        val cached = cachedRecommended
        if (cached != null) {
            lifecycleScope.launch { proceedWithRecommended(cached) }
            return
        }
        loader.show(R.string.loading_extensions)
        lifecycleScope.launch {
            val available = AmoExtensionsRepository.fetchRecommended(this@ExtensionsActivity)
            loader.dismiss()
            if (available.isNotEmpty()) cachedRecommended = available
            proceedWithRecommended(available)
        }
    }

    private suspend fun proceedWithRecommended(available: List<AmoExtension>) {
        val installed = GeckoRuntimeProvider.listUserExtensions(this@ExtensionsActivity)
            .map { it.id }.toSet()
        val filtered = available.filter { it.guid !in installed }
        if (filtered.isEmpty()) {
            openAmo()
        } else {
            showExtensionPicker(filtered)
        }
    }

    private fun showExtensionPicker(extensions: List<AmoExtension>) {
        val dialog = PickerDialog.show(
            activity = this,
            title = getString(R.string.recommended_extensions),
            items = extensions,
            onPick = { installExtension(it.downloadUrl) },
            configure = {
                setNeutralButton(R.string.browse_all_extensions) { _, _ -> openAmo() }
                setNegativeButton(R.string.cancel, null)
            },
        ) { ext, icon, name, detail ->
            name.text = ext.name
            if (ext.summary.isNotBlank()) {
                detail.visibility = View.VISIBLE
                detail.text = ext.summary
            } else {
                detail.visibility = View.GONE
            }
            ExtensionIconCache.bind(icon, this, ext.guid, ext.name)
        }
        (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) as? MaterialButton)
            ?.setIconResource(R.drawable.ic_symbols_open_in_new_24)
    }

    private fun openAmo() {
        val uuid = DataManager.instance.registerTransientWebApp(
            baseUrl = AMO_URL, title = getString(R.string.extensions),
        )
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(Const.INTENT_WEBAPP_UUID, uuid)
        )
    }

    private fun installExtension(uri: String) {
        loader.show(R.string.loading_extension)
        lifecycleScope.launch {
            try {
                val installed = withTimeout(60_000) {
                    GeckoRuntimeProvider.installExtension(this@ExtensionsActivity, uri)
                }
                ExtensionIconCache.refreshFromExtension(this@ExtensionsActivity, installed)
                loadInstalledExtensions()
            } catch (e: Exception) {
                val canceled = (e as? WebExtension.InstallException)?.code ==
                        WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED
                if (!canceled) {
                    NotificationUtils.showToast(
                        this@ExtensionsActivity,
                        getString(R.string.install_extension_error),
                        Toast.LENGTH_SHORT,
                    )
                }
            } finally {
                loader.dismiss()
            }
        }
    }

    companion object {
        private const val TAG = "ExtensionsActivity"
        private const val AMO_URL = "https://addons.mozilla.org/android/"
    }
}
