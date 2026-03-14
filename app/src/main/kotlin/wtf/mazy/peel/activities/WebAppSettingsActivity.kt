package wtf.mazy.peel.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.WebappSettingsBinding
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.IconCache
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.shortcut.FetchCandidate
import wtf.mazy.peel.shortcut.FetchResult
import wtf.mazy.peel.shortcut.HeadlessWebViewFetcher
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.ui.IconEditorController
import wtf.mazy.peel.ui.ListPickerAdapter
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.settings.OverridePickerController
import wtf.mazy.peel.ui.settings.SandboxSwitchController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.WebViewLauncher
import wtf.mazy.peel.util.displayUrl

class WebAppSettingsActivity :
    ToolbarBaseActivity<WebappSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {
    var webappUuid: String? = null
    var originalWebapp: WebApp? = null
    private var modifiedWebapp: WebApp? = null
    private var isEditingDefaults: Boolean = false
    private lateinit var iconEditor: IconEditorController
    private var fetchDialog: AlertDialog? = null
    private var fetchDialogText: TextView? = null
    private var activeFetcher: HeadlessWebViewFetcher? = null
    private var activeFetchServiceIntent: Intent? = null
    private var fetchGeneration: Int = 0

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        iconEditor = IconEditorController(this, { binding.imgWebAppIcon }) { modifiedWebapp }
        super.onCreate(savedInstanceState)

        setToolbarTitle(getString(R.string.web_app_settings))

        webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
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
        binding.textBaseUrl.text = displayUrl(editableWebapp.baseUrl)

        binding.imgWebAppIcon.setOnClickListener { iconEditor.onIconTap() }
        binding.titleUrlBlock.setOnClickListener { showEditDialog(editableWebapp) }
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

        setupKeyboardPadding(binding.scrollView)
    }

    override fun onPause() {
        super.onPause()
        modifiedWebapp?.let { webapp ->
            lifecycleScope.launch {
                withContext(NonCancellable) {
                    if (isEditingDefaults) {
                        DataManager.instance.setDefaultSettings(webapp)
                    } else {
                        DataManager.instance.replaceWebApp(webapp)
                        ShortcutHelper.updatePinnedShortcut(webapp, this@WebAppSettingsActivity)
                    }
                }
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
        setToolbarTitle(getString(R.string.global_web_app_settings))
    }

    private fun showEditDialog(webapp: WebApp) {
        var urlInput: TextInputEditText? = null
        showInputDialogRaw(
            InputDialogConfig(
                hintRes = R.string.name,
                prefill = webapp.title,
                positiveRes = R.string.save,
                extraContent = { container ->
                    val urlLayout = TextInputLayout(container.context).apply {
                        hint = getString(R.string.url)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = (resources.displayMetrics.density * 16).toInt()
                        }
                    }
                    urlInput = TextInputEditText(urlLayout.context).apply {
                        setText(webapp.baseUrl)
                        inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                        isSingleLine = true
                    }
                    urlLayout.addView(urlInput)
                    container.addView(urlLayout)
                },
            ),
        ) { nameInput, _ ->
            webapp.title = nameInput.text.toString().trim()
            webapp.baseUrl = urlInput?.text.toString().trim()
            binding.txtWebAppName.text = webapp.title
            binding.textBaseUrl.text = displayUrl(webapp.baseUrl)
            iconEditor.refreshIcon()
        }
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
        binding.txtGroupName.text = groupName ?: getString(R.string.ungrouped)
    }

    private fun showGroupPicker(webapp: WebApp, groups: List<WebAppGroup>) {
        val popup = PopupMenu(this, binding.txtGroupName, Gravity.END)
        groups.forEachIndexed { index, group -> popup.menu.add(0, index, index, group.title) }
        popup.menu.add(0, groups.size, groups.size, getString(R.string.ungrouped))

        popup.setOnMenuItemClickListener { item ->
            webapp.groupUuid = if (item.itemId < groups.size) groups[item.itemId].uuid else null
            updateGroupLabel(webapp)
            true
        }
        popup.show()
    }

    private fun setupSandboxSwitch(modifiedWebapp: WebApp) {
        SandboxSwitchController(
            this,
            modifiedWebapp,
            binding.switchSandbox,
            binding.switchEphemeralSandbox,
            binding.ephemeralSandboxRow,
            binding.btnClearSandbox,
            onReleaseSandbox = { SandboxManager.releaseSandbox(this, modifiedWebapp.uuid) },
        )
            .setup()
    }

    private fun setupFetchButton(modifiedWebapp: WebApp) {
        binding.btnFetch.setOnClickListener {
            if (fetchDialog == null) fetchIconAndName(modifiedWebapp)
        }
    }

    private fun showFetchProgress() {
        val dp = resources.displayMetrics.density
        val text =
            TextView(this).apply {
                setText(R.string.loading_icon_and_website_title)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
            }
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (dp * 24).toInt(), (dp * 24).toInt(), (dp * 24).toInt(), (dp * 16).toInt()
                )
                addView(
                    CircularProgressIndicator(context).apply {
                        isIndeterminate = true
                        indicatorSize = (dp * 40).toInt()
                    })
                addView(
                    text,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                        .apply { marginStart = (dp * 16).toInt() },
                )
            }
        fetchDialogText = text
        fetchDialog =
            MaterialAlertDialogBuilder(this)
                .setView(layout)
                .setCancelable(true)
                .setOnCancelListener {
                    cancelActiveFetch()
                    dismissFetchProgress()
                }
                .show()
    }

    private fun cancelActiveFetch() {
        fetchGeneration += 1
        activeFetcher?.cancel()
        activeFetcher = null
        activeFetchServiceIntent?.let { stopService(it) }
        activeFetchServiceIntent = null
    }

    private fun dismissFetchProgress() {
        fetchDialog?.dismiss()
        fetchDialog = null
        fetchDialogText = null
    }

    private fun fetchIconAndName(webapp: WebApp) {
        val url = webapp.baseUrl.trim()
        if (url.isEmpty()) {
            showToast(this, getString(R.string.enter_valid_url), Toast.LENGTH_SHORT)
            return
        }

        showFetchProgress()
        fetchGeneration += 1
        val generation = fetchGeneration
        val onProgress: (String) -> Unit = { fetchDialogText?.text = it }

        val sandboxId = WebViewLauncher.resolveSandboxId(webapp)
        if (sandboxId != null) {
            val slotId = SandboxManager.resolveSlotId(this, sandboxId)
            val receiver =
                object : ResultReceiver(Handler(mainLooper)) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        if (generation != fetchGeneration) return
                        when (resultCode) {
                            SandboxFetchService.RESULT_PROGRESS ->
                                onProgress(
                                    resultData?.getString(SandboxFetchService.KEY_PROGRESS) ?: ""
                                )

                            SandboxFetchService.RESULT_DONE ->
                                handleFetchResult(
                                    webapp,
                                    SandboxFetchService.parseResult(resultData),
                                    generation,
                                )
                        }
                    }
                }
            val intent = SandboxFetchService.createIntent(
                this,
                slotId,
                sandboxId,
                url,
                DataManager.instance.resolveEffectiveSettings(webapp),
                receiver,
            )
            activeFetchServiceIntent = intent
            startService(intent)
        } else {
            val fetcher = HeadlessWebViewFetcher(
                this,
                url,
                DataManager.instance.resolveEffectiveSettings(webapp),
                onProgress = onProgress,
                onResult = {
                    if (generation == fetchGeneration) handleFetchResult(
                        webapp,
                        it,
                        generation
                    )
                },
            )
            activeFetcher = fetcher
            fetcher.start()
        }
    }

    private fun handleFetchResult(
        webapp: WebApp,
        result: FetchResult,
        generation: Int,
    ) {
        if (generation != fetchGeneration) return
        if (isFinishing || isDestroyed) return
        activeFetcher = null
        activeFetchServiceIntent = null
        val candidates = result.candidates
        if (candidates.isEmpty()) {
            dismissFetchProgress()
            showToast(this, getString(R.string.fetch_failed), Toast.LENGTH_SHORT)
            return
        }
        val startUrl = candidates.firstNotNullOfOrNull { it.startUrl }
        val urlSuggestion = resolveUrlSuggestion(webapp.baseUrl, startUrl, result.redirectedUrl)
        val withIcon = candidates.filter { it.icon != null }
        if (!webapp.hasCustomIcon && webapp.title.isEmpty() && withIcon.size == 1) {
            applyFetchResult(webapp, withIcon.first(), urlSuggestion)
            return
        }
        showFetchPickerDialog(webapp, candidates, urlSuggestion)
    }

    private fun resolveUrlSuggestion(
        baseUrl: String, startUrl: String?, redirectedUrl: String?,
    ): Pair<String, Int>? {
        val base = baseUrl.trimEnd('/')
        if (startUrl != null && startUrl.trimEnd('/') != base)
            return startUrl to R.string.manifest_start_url_message
        if (redirectedUrl != null && redirectedUrl.trimEnd('/') != base)
            return redirectedUrl to R.string.redirect_url_message
        return null
    }

    private fun showFetchPickerDialog(
        webapp: WebApp,
        candidates: List<FetchCandidate>,
        urlSuggestion: Pair<String, Int>?,
    ) {
        dismissFetchProgress()
        val defaultIconSizePx = (resources.displayMetrics.density * 48).toInt()
        val colorSeed = webapp.letterIconSeed

        val adapter =
            ListPickerAdapter(candidates) { candidate, iconView, nameView, detailView ->
                val title = candidate.title ?: candidate.source
                nameView.text = title
                val bmp = candidate.icon
                if (bmp != null) {
                    iconView.setImageBitmap(bmp)
                    detailView.text = getString(
                        R.string.icon_dimensions_source,
                        bmp.width,
                        bmp.height,
                        candidate.source
                    )
                    detailView.visibility = View.VISIBLE
                } else {
                    iconView.setImageBitmap(
                        LetterIconGenerator.generate(title, colorSeed, defaultIconSizePx)
                    )
                    detailView.visibility = View.GONE
                }
            }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_icon)
            .setAdapter(adapter) { _, which ->
                applyFetchResult(
                    webapp,
                    candidates[which],
                    urlSuggestion
                )
            }
            .setOnCancelListener {
                urlSuggestion?.let { (url, messageResId) ->
                    promptUrlUpdate(
                        webapp,
                        url,
                        messageResId
                    )
                }
            }
            .show()
    }

    private fun applyFetchResult(
        webapp: WebApp,
        candidate: FetchCandidate,
        urlSuggestion: Pair<String, Int>?,
    ) {
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
        urlSuggestion?.let { (url, messageResId) -> promptUrlUpdate(webapp, url, messageResId) }
    }

    private fun promptUrlUpdate(webapp: WebApp, suggestedUrl: String, messageResId: Int) {
        if (suggestedUrl.trimEnd('/') == webapp.baseUrl.trimEnd('/')) return
        val text = getString(messageResId, suggestedUrl)
        val urlStart = text.indexOf(suggestedUrl)
        val message = SpannableString(text).apply {
            if (urlStart >= 0) {
                setSpan(
                    TypefaceSpan("monospace"),
                    urlStart,
                    urlStart + suggestedUrl.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manifest_start_url_title)
            .setMessage(message)
            .setPositiveButton(R.string.manifest_start_url_update) { _, _ ->
                webapp.baseUrl = suggestedUrl
                binding.textBaseUrl.text = displayUrl(suggestedUrl)
            }
            .setNegativeButton(R.string.manifest_start_url_keep, null)
            .show()
    }

    private lateinit var overrideController: OverridePickerController

    private fun setupOverridePicker(modifiedWebapp: WebApp) {
        overrideController =
            OverridePickerController(
                this,
                modifiedWebapp.settings,
                binding.linearLayoutOverrides,
                binding.btnAddOverride,
            )
        overrideController.setup()
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        overrideController.onSettingSelected(setting)
    }
}
