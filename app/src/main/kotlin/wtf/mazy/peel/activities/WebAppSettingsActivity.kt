package wtf.mazy.peel.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.WebappSettingsBinding
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.IconCache
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.shortcut.FetchCandidate
import wtf.mazy.peel.shortcut.HeadlessWebViewFetcher
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.ui.IconEditorController
import wtf.mazy.peel.ui.ListPickerAdapter
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.ui.settings.OverridePickerController
import wtf.mazy.peel.ui.settings.SandboxSwitchController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.Utility
import wtf.mazy.peel.util.WebViewLauncher

class WebAppSettingsActivity :
    ToolbarBaseActivity<WebappSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {
    var webappUuid: String? = null
    var originalWebapp: WebApp? = null
    private var modifiedWebapp: WebApp? = null
    private var isEditingDefaults: Boolean = false
    private lateinit var iconEditor: IconEditorController
    private var fetchDialog: AlertDialog? = null
    private var fetchDialogText: TextView? = null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        iconEditor = IconEditorController(this, { binding.imgWebAppIcon }) { modifiedWebapp }
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

        binding.imgWebAppIcon.setOnClickListener { iconEditor.onIconTap() }
        setupFetchButton(editableWebapp)
        setupOverridePicker(editableWebapp)
        if (!isEditingDefaults) {
            setupSandboxSwitch(editableWebapp)
            setupGroupPicker(editableWebapp)
        }

        iconEditor.refreshIcon()

        if (intent.getBooleanExtra(Const.INTENT_AUTO_FETCH, false)) {
            binding.root.post { fetchIconAndName(editableWebapp) }
        }

        setupKeyboardPadding(findViewById(R.id.contentContainer))
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

        binding.txtGroupName.setOnClickListener { showGroupPicker(webapp, groups) }
    }

    private fun updateGroupLabel(webapp: WebApp) {
        val groupName = webapp.groupUuid?.let { DataManager.instance.getGroup(it)?.title }
        binding.txtGroupName.text = groupName ?: getString(R.string.none)
    }

    private fun showGroupPicker(webapp: WebApp, groups: List<WebAppGroup>) {
        val popup = PopupMenu(this, binding.txtGroupName)
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
        SandboxSwitchController(
            this, modifiedWebapp,
            binding.switchSandbox, binding.switchEphemeralSandbox,
            binding.ephemeralSandboxRow, binding.btnClearSandbox,
            onReleaseSandbox = { SandboxManager.releaseSandbox(this, modifiedWebapp.uuid) },
        ).setup()
    }

    private fun setupFetchButton(modifiedWebapp: WebApp) {
        binding.btnFetch.setOnClickListener {
            if (fetchDialog == null) fetchIconAndName(modifiedWebapp)
        }
    }

    private fun showFetchProgress() {
        val dp = resources.displayMetrics.density
        val text = TextView(this).apply {
            setText(R.string.loading_icon_and_website_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((dp * 24).toInt(), (dp * 24).toInt(), (dp * 24).toInt(), (dp * 16).toInt())
            addView(CircularProgressIndicator(context).apply {
                isIndeterminate = true
                indicatorSize = (dp * 40).toInt()
            })
            addView(text, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (dp * 16).toInt() })
        }
        fetchDialogText = text
        fetchDialog = MaterialAlertDialogBuilder(this)
            .setView(layout)
            .setCancelable(true)
            .setOnCancelListener { dismissFetchProgress() }
            .show()
    }

    private fun dismissFetchProgress() {
        fetchDialog?.dismiss()
        fetchDialog = null
        fetchDialogText = null
    }

    private fun fetchIconAndName(webapp: WebApp) {
        val url = binding.textBaseUrl.text.toString().trim()
        if (url.isEmpty()) {
            showToast(this, getString(R.string.enter_valid_url), Toast.LENGTH_SHORT)
            return
        }

        showFetchProgress()
        val onProgress: (String) -> Unit = { fetchDialogText?.text = it }

        val sandboxId = WebViewLauncher.resolveSandboxId(webapp)
        if (sandboxId != null) {
            val slotId = SandboxManager.resolveSlotId(this, sandboxId)
            val receiver = object : ResultReceiver(Handler(mainLooper)) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    when (resultCode) {
                        SandboxFetchService.RESULT_PROGRESS ->
                            onProgress(resultData?.getString(SandboxFetchService.KEY_PROGRESS) ?: "")
                        SandboxFetchService.RESULT_DONE ->
                            handleFetchCandidates(webapp, SandboxFetchService.parseCandidates(resultData))
                    }
                }
            }
            startService(SandboxFetchService.createIntent(this, slotId, sandboxId, url, webapp.effectiveSettings, receiver))
        } else {
            HeadlessWebViewFetcher(this, url, webapp.effectiveSettings,
                onProgress = onProgress,
                onResult = { handleFetchCandidates(webapp, it) },
            ).start()
        }
    }

    private fun handleFetchCandidates(webapp: WebApp, candidates: List<FetchCandidate>) {
        if (isFinishing || isDestroyed) return
        if (candidates.isEmpty()) {
            dismissFetchProgress()
            showToast(this, getString(R.string.fetch_failed), Toast.LENGTH_SHORT)
            return
        }
        val withIcon = candidates.filter { it.icon != null }
        if (!webapp.hasCustomIcon && webapp.title.isEmpty() && withIcon.size == 1) {
            applyFetchResult(webapp, withIcon.first())
            return
        }
        showFetchPickerDialog(webapp, candidates)
    }

    private fun showFetchPickerDialog(webapp: WebApp, candidates: List<FetchCandidate>) {
        dismissFetchProgress()
        val defaultIconSizePx = (resources.displayMetrics.density * 48).toInt()
        val colorSeed = webapp.letterIconSeed

        val adapter = ListPickerAdapter(candidates) { candidate, iconView, nameView, detailView ->
            val title = candidate.title ?: getString(R.string.none)
            nameView.text = title
            val bmp = candidate.icon
            if (bmp != null) {
                iconView.setImageBitmap(bmp)
                detailView.text = "${bmp.width}x${bmp.height} · ${candidate.source}"
            } else {
                iconView.setImageBitmap(LetterIconGenerator.generate(title, colorSeed, defaultIconSizePx))
                detailView.text = candidate.source
            }
            detailView.visibility = View.VISIBLE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_icon)
            .setAdapter(adapter) { _, which -> applyFetchResult(webapp, candidates[which]) }
            .show()
    }

    private fun applyFetchResult(webapp: WebApp, candidate: FetchCandidate) {
        dismissFetchProgress()
        if (!candidate.title.isNullOrEmpty()) {
            binding.txtWebAppName.setText(candidate.title)
            webapp.title = candidate.title
        }
        if (candidate.icon != null) {
            webapp.saveIcon(candidate.icon)
        } else {
            IconCache.evict(webapp)
        }
        iconEditor.refreshIcon()
    }

    private lateinit var overrideController: OverridePickerController

    private fun setupOverridePicker(modifiedWebapp: WebApp) {
        overrideController = OverridePickerController(
            this, modifiedWebapp.settings, binding.linearLayoutOverrides, binding.btnAddOverride,
        )
        overrideController.setup()
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        overrideController.onSettingSelected(setting)
    }
}
