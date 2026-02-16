package wtf.mazy.peel.activities

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.WebappSettingsBinding
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.shortcut.WebAppIconFetcher
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.ui.settings.SettingViewFactory
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.Utility

class WebAppSettingsActivity :
    ToolbarBaseActivity<WebappSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {
    var webappUuid: String? = null
    var originalWebapp: WebApp? = null
    private var modifiedWebapp: WebApp? = null
    private var isEditingDefaults: Boolean = false
    private var customIconBitmap: Bitmap? = null
    private lateinit var iconPickerLauncher: ActivityResultLauncher<String>
    private var executorService: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isFetchingIcon = false

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        iconPickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let { handleSelectedIcon(it) }
            }
        executorService = Executors.newSingleThreadExecutor()
        super.onCreate(savedInstanceState)

        setToolbarTitle(getString(R.string.web_app_settings))

        webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        Utility.assert(webappUuid != null, "WebApp UUID could not be retrieved.")
        isEditingDefaults = webappUuid == DataManager.instance.defaultSettings.uuid

        if (isEditingDefaults) {
            originalWebapp = DataManager.instance.defaultSettings
            prepareGlobalWebAppScreen()
        } else originalWebapp = webappUuid?.let { DataManager.instance.getWebApp(it) }

        if (originalWebapp == null) {
            showToast(this, getString(R.string.webapp_not_found), Toast.LENGTH_SHORT)
            finish()
            return
        }
        val baseWebapp = originalWebapp ?: return
        modifiedWebapp = WebApp(baseWebapp)
        val editableWebapp =
            modifiedWebapp
                ?: run {
                    finish()
                    return
                }
        binding.webapp = editableWebapp
        binding.activity = this@WebAppSettingsActivity

        setupIconButton()
        setupFetchButton(editableWebapp)
        setupOverridePicker(editableWebapp)
        if (!isEditingDefaults) {
            setupSandboxSwitch(editableWebapp)
            setupGroupPicker(editableWebapp)
        }

        loadCurrentIcon(editableWebapp)

        if (intent.getBooleanExtra(Const.INTENT_AUTO_FETCH, false)) {
            binding.root.post { fetchIconAndName(editableWebapp) }
        }

        setupKeyboardListener()
    }

    private fun setupKeyboardListener() {
        val rootView = binding.root
        val contentContainer = findViewById<android.widget.LinearLayout>(R.id.contentContainer)

        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keyboardHeight = screenHeight - rect.bottom

            if (keyboardHeight > screenHeight * 0.15) {
                contentContainer?.setPadding(0, 0, 0, keyboardHeight)
            } else {
                contentContainer?.setPadding(0, 0, 0, 0)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        modifiedWebapp?.let { webapp ->
            if (isEditingDefaults) {
                DataManager.instance.defaultSettings = webapp
            } else {
                DataManager.instance.replaceWebApp(webapp)
                ShortcutHelper.updatePinnedShortcut(webapp, this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executorService?.shutdownNow()
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): WebappSettingsBinding {
        return WebappSettingsBinding.inflate(layoutInflater)
    }

    private fun prepareGlobalWebAppScreen() {
        binding.sectionMainSettings.visibility = View.GONE
        binding.sandboxRow.visibility = View.GONE
        binding.groupRow.visibility = View.GONE
        binding.groupDivider.visibility = View.GONE
        binding.sectionOverrideHeader.visibility = View.GONE
        binding.linearLayoutOverrides.visibility = View.GONE
        binding.globalSettingsInfoText.visibility = View.VISIBLE
        setToolbarTitle(getString(R.string.global_web_app_settings))
    }

    private fun setupGroupPicker(webapp: WebApp) {
        val groups = DataManager.instance.sortedGroups
        if (groups.isEmpty()) {
            binding.groupRow.visibility = View.GONE
            binding.groupDivider.visibility = View.GONE
            return
        }

        binding.groupRow.visibility = View.VISIBLE
        binding.groupDivider.visibility = View.VISIBLE
        updateGroupLabel(webapp)

        binding.groupRow.setOnClickListener { showGroupPopup(binding.txtGroupName, webapp, groups) }
    }

    private fun updateGroupLabel(webapp: WebApp) {
        val groupName = webapp.groupUuid?.let { DataManager.instance.getGroup(it)?.title }
        binding.txtGroupName.text = groupName ?: getString(R.string.none)
    }

    private fun showGroupPopup(
        anchor: View,
        webapp: WebApp,
        groups: List<wtf.mazy.peel.model.WebAppGroup>
    ) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        groups.forEachIndexed { index, group -> popup.menu.add(0, index, index, group.title) }
        popup.menu.add(0, groups.size, groups.size, getString(R.string.none))

        popup.setOnMenuItemClickListener { item ->
            webapp.groupUuid = if (item.itemId < groups.size) groups[item.itemId].uuid else null
            updateGroupLabel(webapp)
            true
        }
        popup.show()
    }

    private fun setupSandboxSwitch(modifiedWebapp: WebApp) {
        updateClearSandboxButtonVisibility(modifiedWebapp)
        updateEphemeralSandboxVisibility(modifiedWebapp.isUseContainer)

        binding.switchSandbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == modifiedWebapp.isUseContainer) {
                return@setOnCheckedChangeListener
            }
            SandboxManager.releaseSandbox(this, modifiedWebapp.uuid)
            modifiedWebapp.isUseContainer = isChecked
            if (!isChecked) {
                modifiedWebapp.isEphemeralSandbox = false
                binding.switchEphemeralSandbox.isChecked = false
            }
            updateClearSandboxButtonVisibility(modifiedWebapp)
            updateEphemeralSandboxVisibility(isChecked)
        }

        binding.switchEphemeralSandbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == modifiedWebapp.isEphemeralSandbox) {
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                val sandboxDir = SandboxManager.getSandboxDataDir(modifiedWebapp.uuid)
                if (sandboxDir.exists()) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.clear_sandbox_data_confirm)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            modifiedWebapp.isEphemeralSandbox = true
                            clearSandboxData(modifiedWebapp)
                        }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            binding.switchEphemeralSandbox.isChecked = false
                        }
                        .show()
                } else {
                    SandboxManager.releaseSandbox(this, modifiedWebapp.uuid)
                    modifiedWebapp.isEphemeralSandbox = true
                    updateClearSandboxButtonVisibility(modifiedWebapp)
                }
            } else {
                modifiedWebapp.isEphemeralSandbox = false
                updateClearSandboxButtonVisibility(modifiedWebapp)
            }
        }

        binding.btnClearSandbox.setOnClickListener { showClearSandboxConfirmDialog(modifiedWebapp) }
    }

    private fun updateEphemeralSandboxVisibility(sandboxEnabled: Boolean) {
        binding.ephemeralSandboxRow.visibility = if (sandboxEnabled) View.VISIBLE else View.GONE
    }

    private fun updateClearSandboxButtonVisibility(webapp: WebApp) {
        val sandboxDir = SandboxManager.getSandboxDataDir(webapp.uuid)
        val show = sandboxDir.exists() && !webapp.isEphemeralSandbox
        binding.btnClearSandbox.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showClearSandboxConfirmDialog(webapp: WebApp) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_sandbox_data_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> clearSandboxData(webapp) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearSandboxData(webapp: WebApp) {
        if (SandboxManager.clearSandboxData(this, webapp.uuid)) {
            showToast(this, getString(R.string.clear_sandbox_data), Toast.LENGTH_SHORT)
        }
        updateClearSandboxButtonVisibility(webapp)
    }

    private fun setupIconButton() {
        val launchIconPicker = {
            try {
                iconPickerLauncher.launch("image/*")
            } catch (e: Exception) {
                showToast(this, getString(R.string.icon_not_found), Toast.LENGTH_SHORT)
                Log.e("WebAppSettings", "Failed to launch icon picker", e)
            }
        }
        binding.iconContainer.setOnClickListener { launchIconPicker() }
        binding.btnEditIcon.setOnClickListener { launchIconPicker() }
    }

    private fun handleSelectedIcon(uri: Uri) {
        try {
            @Suppress("DEPRECATION")
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
            if (bitmap != null) {
                customIconBitmap = bitmap
                binding.imgWebAppIcon.setImageBitmap(bitmap)
                binding.imgWebAppIconPlaceholder.visibility = View.GONE
                binding.webapp?.let { modifiedWebapp -> saveIconToFile(modifiedWebapp, bitmap) }
            }
        } catch (e: IOException) {
            showToast(this, getString(R.string.icon_not_found), Toast.LENGTH_SHORT)
            Log.e("WebAppSettings", "Failed to load icon from URI", e)
        }
    }

    private fun loadCurrentIcon(modifiedWebapp: WebApp) {
        if (modifiedWebapp.hasCustomIcon) {
            try {
                val bitmap = BitmapFactory.decodeFile(modifiedWebapp.iconFile.absolutePath)
                if (bitmap != null) {
                    binding.imgWebAppIcon.setImageBitmap(bitmap)
                    binding.imgWebAppIconPlaceholder.visibility = View.GONE
                    customIconBitmap = bitmap
                }
            } catch (e: Exception) {
                Log.w("WebAppSettings", "Failed to load custom icon", e)
            }
        }
    }

    private fun saveIconToFile(webApp: WebApp, bitmap: Bitmap) {
        try {
            val iconFile = webApp.iconFile
            iconFile.parentFile?.mkdirs()
            FileOutputStream(iconFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } catch (e: Exception) {
            Log.w("WebAppSettings", "Failed to save icon for webapp ${webApp.uuid}", e)
        }
    }

    private fun setupFetchButton(modifiedWebapp: WebApp) {
        binding.btnFetch.setOnClickListener {
            if (!isFetchingIcon) {
                fetchIconAndName(modifiedWebapp)
            }
        }
    }

    private fun fetchIconAndName(modifiedWebapp: WebApp) {
        if (isFetchingIcon) return

        val urlToFetch = binding.textBaseUrl.text.toString().trim()
        if (urlToFetch.isEmpty()) {
            showToast(this, getString(R.string.enter_valid_url), Toast.LENGTH_SHORT)
            return
        }

        isFetchingIcon = true
        val rotateAnimation = AnimationUtils.loadAnimation(this, R.anim.rotate_indefinitely)
        binding.btnFetch.startAnimation(rotateAnimation)

        executorService?.submit {
            try {
                val result = WebAppIconFetcher.fetch(urlToFetch)

                mainHandler.post {
                    isFetchingIcon = false
                    binding.btnFetch.clearAnimation()

                    if (!result.title.isNullOrEmpty()) {
                        binding.txtWebAppName.setText(result.title)
                        modifiedWebapp.title = result.title
                    }

                    if (result.icon != null) {
                        customIconBitmap = result.icon
                        binding.imgWebAppIcon.setImageBitmap(result.icon)
                        binding.imgWebAppIconPlaceholder.visibility = View.GONE
                        saveIconToFile(modifiedWebapp, result.icon)
                    }

                    if (result.title == null && result.icon == null) {
                        showToast(this, getString(R.string.fetch_failed), Toast.LENGTH_SHORT)
                    }
                }
            } catch (e: Exception) {
                Log.e("WebAppSettings", "Failed to fetch data", e)
                mainHandler.post {
                    isFetchingIcon = false
                    binding.btnFetch.clearAnimation()
                    showToast(this, getString(R.string.fetch_failed), Toast.LENGTH_SHORT)
                }
            }
        }
    }

    private lateinit var overrideViewFactory: SettingViewFactory

    private fun setupOverridePicker(modifiedWebapp: WebApp) {
        updateOverrideViewFactory(modifiedWebapp)
        updateOverridesList(modifiedWebapp)
        binding.btnAddOverride.setOnClickListener { showOverridePickerDialog(modifiedWebapp) }
    }

    private fun updateOverrideViewFactory(webapp: WebApp) {
        overrideViewFactory =
            SettingViewFactory(
                layoutInflater,
                SettingViewFactory.ButtonStrategy.Override { setting ->
                    removeOverride(webapp, setting.key)
                    updateOverridesList(webapp)
                },
            )
    }

    private fun updateOverridesList(modifiedWebapp: WebApp) {
        val allOverriddenKeys = modifiedWebapp.settings.getOverriddenKeys()
        val compoundKeys =
            SettingRegistry.getAllSettings().flatMapTo(mutableSetOf()) { setting ->
                setting.allFields.drop(1).map { it.key }
            }
        val overriddenKeys = allOverriddenKeys.filter { it !in compoundKeys }

        val container = binding.linearLayoutOverrides
        container.removeAllViews()

        overriddenKeys.forEach { key ->
            val setting = SettingRegistry.getSettingByKey(key) ?: return@forEach
            container.addView(
                overrideViewFactory.createView(container, setting, modifiedWebapp.settings))
        }
    }

    private fun removeOverride(webapp: WebApp, key: String) {
        val setting = SettingRegistry.getSettingByKey(key) ?: return
        setting.allFields.forEach { webapp.settings.setValue(it.key, null) }
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        val webapp = modifiedWebapp ?: return
        val globalSettings = DataManager.instance.defaultSettings.settings
        if (setting is SettingDefinition.StringMapSetting) {
            webapp.settings.customHeaders = mutableMapOf()
        } else {
            setting.allFields.forEach { field ->
                webapp.settings.setValue(field.key, globalSettings.getValue(field.key))
            }
        }
        binding.linearLayoutOverrides.addView(
            overrideViewFactory.createView(binding.linearLayoutOverrides, setting, webapp.settings))
    }

    private fun showOverridePickerDialog(modifiedWebapp: WebApp) {
        val dialog =
            OverridePickerDialog.newInstance(
                modifiedWebapp.settings,
                DataManager.instance.defaultSettings.settings,
                this,
            )
        dialog.show(supportFragmentManager, "OverridePickerDialog")
    }
}
