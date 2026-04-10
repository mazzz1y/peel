package wtf.mazy.peel.activities

import android.os.Bundle
import android.text.style.BulletSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.geckoview.WebExtension
import kotlin.coroutines.resume
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.ui.ListPickerAdapter
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.extensions.AmoExtensionsRepository
import wtf.mazy.peel.ui.extensions.AmoExtensionsRepository.AmoExtension
import wtf.mazy.peel.ui.extensions.ExtensionAdapter
import wtf.mazy.peel.ui.extensions.ExtensionIconCache
import wtf.mazy.peel.ui.extensions.ExtensionPopupBottomSheet
import wtf.mazy.peel.util.NotificationUtils
import java.io.File

class ExtensionsActivity : AppCompatActivity() {

    private lateinit var adapter: ExtensionAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var fab: FloatingActionButton
    private lateinit var loader: LoadingDialogController

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
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extensions)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.extensions)
        toolbar.setNavigationOnClickListener { finish() }

        loader = LoadingDialogController(this)

        recyclerView = findViewById(R.id.extension_list)
        emptyStateText = findViewById(R.id.empty_state_text)
        fab = findViewById(R.id.fab_add_extension)

        adapter = ExtensionAdapter(
            onUpdate = ::updateExtension,
            onSettings = { ExtensionPopupBottomSheet.showOptionsPage(this, it) },
            onUninstall = ::confirmUninstall,
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fab.setOnClickListener { showAvailableExtensions() }
        fab.setOnLongClickListener {
            filePickerLauncher.launch(
                arrayOf("application/x-xpinstall", "application/octet-stream")
            )
            true
        }

        loadInstalledExtensions()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_extensions, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_update_all -> {
                updateAllExtensions()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        GeckoRuntimeProvider.installPromptHandler = { ext, permissions, origins ->
            confirmPermissionPrompt(
                title = getString(R.string.install_extension_confirm_title),
                summaryRes = R.string.install_extension_permission_summary,
                ext = ext,
                permissions = permissions,
                origins = origins,
                showEvenIfEmpty = true,
                positiveRes = R.string.install,
            )
        }
        GeckoRuntimeProvider.updatePromptHandler = { ext, permissions, origins ->
            confirmPermissionPrompt(
                title = getString(R.string.update_extension_confirm_title),
                summaryRes = R.string.update_extension_permission_summary,
                ext = ext,
                permissions = permissions,
                origins = origins,
                showEvenIfEmpty = false,
                positiveRes = R.string.extension_update,
            )
        }
    }

    override fun onStop() {
        GeckoRuntimeProvider.installPromptHandler = null
        GeckoRuntimeProvider.updatePromptHandler = null
        GeckoRuntimeProvider.cancelPromptScope()
        super.onStop()
    }

    override fun onDestroy() {
        loader.dismiss()
        super.onDestroy()
    }

    private suspend fun confirmPermissionPrompt(
        title: String,
        summaryRes: Int,
        ext: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        showEvenIfEmpty: Boolean,
        positiveRes: Int,
    ): Boolean {
        if (isFinishing || isDestroyed) return false
        if (!showEvenIfEmpty && permissions.isEmpty() && origins.isEmpty()) {
            return true
        }
        val name = ext.metaData.name ?: ext.id
        val bulletGapPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics,
        ).toInt()
        val message = buildSpannedString {
            append(getString(summaryRes, name))
            append("\n\n")
            if (permissions.isEmpty() && origins.isEmpty()) {
                append(getString(R.string.install_extension_no_permissions))
            } else {
                if (permissions.isNotEmpty()) {
                    append(getString(R.string.install_extension_permissions_header))
                    append('\n')
                    permissions.forEachIndexed { index, perm ->
                        inSpans(BulletSpan(bulletGapPx)) { append(perm) }
                        if (index != permissions.lastIndex) append('\n')
                    }
                }
                if (origins.isNotEmpty()) {
                    if (permissions.isNotEmpty()) append("\n\n")
                    append(getString(R.string.install_extension_origins_header))
                    append('\n')
                    origins.forEachIndexed { index, origin ->
                        inSpans(BulletSpan(bulletGapPx)) { append(origin) }
                        if (index != origins.lastIndex) append('\n')
                    }
                }
            }
        }
        return suspendCancellableCoroutine { cont ->
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveRes) { _, _ ->
                    if (!cont.isCompleted) cont.resume(true)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    if (!cont.isCompleted) cont.resume(false)
                }
                .setOnCancelListener {
                    if (!cont.isCompleted) cont.resume(false)
                }
                .create()
            dialog.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
    }

    private fun loadInstalledExtensions() {
        lifecycleScope.launch {
            val extensions = GeckoRuntimeProvider.listUserExtensions(this@ExtensionsActivity)
            adapter.submitList(extensions)
            val isEmpty = extensions.isEmpty()
            emptyStateText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun updateExtension(ext: WebExtension) {
        lifecycleScope.launch {
            try {
                val updated =
                    GeckoRuntimeProvider.updateExtension(this@ExtensionsActivity, ext)
                if (updated != null) {
                    ExtensionIconCache.refreshFromExtension(this@ExtensionsActivity, updated)
                    val version = updated.metaData.version.orEmpty()
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
                        this@ExtensionsActivity, ext
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
            .setMessage(getString(R.string.uninstall_extension_confirm, name))
            .setPositiveButton(R.string.uninstall_extension) { _, _ ->
                lifecycleScope.launch {
                    try {
                        GeckoRuntimeProvider.uninstallExtension(
                            this@ExtensionsActivity, ext
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
        loader.show(R.string.loading_extensions)
        lifecycleScope.launch {
            val installed = GeckoRuntimeProvider.listUserExtensions(this@ExtensionsActivity)
                .map { it.id }.toSet()
            val available = AmoExtensionsRepository.fetchRecommended(this@ExtensionsActivity)
            loader.dismiss()

            if (available.isEmpty()) {
                NotificationUtils.showToast(
                    this@ExtensionsActivity,
                    getString(R.string.install_extension_error),
                    Toast.LENGTH_SHORT,
                )
                return@launch
            }

            val filtered = available.filter { it.guid !in installed }
            if (filtered.isEmpty()) {
                NotificationUtils.showToast(
                    this@ExtensionsActivity,
                    getString(R.string.all_extensions_installed),
                    Toast.LENGTH_SHORT,
                )
                return@launch
            }
            showExtensionPicker(filtered)
        }
    }

    private fun showExtensionPicker(extensions: List<AmoExtension>) {
        val pickerAdapter = ListPickerAdapter(extensions) { ext, icon, name, detail ->
            name.text = ext.name
            detail.visibility = View.GONE
            ExtensionIconCache.bind(icon, this, ext.guid, ext.name)
        }

        var dialog: androidx.appcompat.app.AlertDialog? = null
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.available_extensions)
            .setAdapter(pickerAdapter) { _, position ->
                dialog?.dismiss()
                installExtension(extensions[position].downloadUrl)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun installExtension(uri: String) {
        loader.show(R.string.installing_extension)
        lifecycleScope.launch {
            try {
                val installed = withTimeout(60_000) {
                    GeckoRuntimeProvider.installExtension(this@ExtensionsActivity, uri)
                }
                ExtensionIconCache.refreshFromExtension(this@ExtensionsActivity, installed)
                loadInstalledExtensions()
            } catch (e: Exception) {
                Log.w(TAG, "installExtension failed for $uri", e)
                NotificationUtils.showToast(
                    this@ExtensionsActivity,
                    getString(R.string.install_extension_error),
                    Toast.LENGTH_SHORT,
                )
            } finally {
                loader.dismiss()
            }
        }
    }

    companion object {
        private const val TAG = "ExtensionsActivity"
    }
}
