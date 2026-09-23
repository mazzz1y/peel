package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.WebappSettingsBinding
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.IconCache
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.shortcut.FetchCandidate
import wtf.mazy.peel.shortcut.FetchResult
import wtf.mazy.peel.shortcut.HeadlessFetcher
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.ui.IconEditorController
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.ui.bindDropdown
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.ui.dialog.ScopeExtensionsPrompt
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.common.GroupPosition
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.settings.OverridePickerController
import wtf.mazy.peel.ui.common.SettingsSurface
import wtf.mazy.peel.ui.settings.SandboxSwitchController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.SameAppDomainMatcher
import wtf.mazy.peel.util.isSameHost
import wtf.mazy.peel.util.prettyBaseUrl
import wtf.mazy.peel.util.withBoldSpan
import wtf.mazy.peel.util.withMonoSpan

class WebAppSettingsActivity :
    ToolbarBaseActivity<WebappSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {
    var webappUuid: String? = null
    var originalWebapp: WebApp? = null
    private var modifiedWebapp: WebApp? = null
    private lateinit var iconEditor: IconEditorController
    private val fetchDialog = LoadingDialogController(this)
    private var activeFetcher: HeadlessFetcher? = null
    private var fetchGeneration: Int = 0
    private lateinit var originalSettingsSnapshot: WebAppSettings

    private val imgWebAppIcon get() = binding.root.findViewById<ImageView>(R.id.imgWebAppIcon)
    private val txtWebAppName get() = binding.root.findViewById<TextView>(R.id.txtWebAppName)
    private val textBaseUrl get() = binding.root.findViewById<TextView>(R.id.textBaseUrl)
    private val titleUrlBlock get() = binding.root.findViewById<View>(R.id.titleUrlBlock)
    private val btnFetch get() = binding.root.findViewById<ImageView>(R.id.btnFetch)
    private val sandboxLabel get() = binding.root.findViewById<TextView>(R.id.sandboxLabel)
    private val switchSandbox get() = binding.root.findViewById<MaterialSwitch>(R.id.switchSandbox)
    private val switchEphemeralSandbox get() = binding.root.findViewById<MaterialSwitch>(R.id.switchEphemeralSandbox)
    private val ephemeralSandboxRow get() = binding.root.findViewById<LinearLayout>(R.id.ephemeralSandboxRow)
    private val proxyRow get() = binding.root.findViewById<LinearLayout>(R.id.proxyRow)
    private val btnProxyPicker get() = binding.root.findViewById<MaterialButton>(R.id.btnProxyPicker)
    private val btnClearSandbox get() = binding.root.findViewById<MaterialButton>(R.id.btnClearSandbox)
    private val btnAddOverride get() = binding.root.findViewById<MaterialButton>(R.id.btnAddOverride)
    private val linearLayoutOverrides get() = binding.root.findViewById<LinearLayout>(R.id.linearLayoutOverrides)

    override fun onCreate(savedInstanceState: Bundle?) {
        iconEditor = IconEditorController(this, { imgWebAppIcon }) { modifiedWebapp }
        super.onCreate(savedInstanceState)

        setToolbarTitle(getString(R.string.web_app_settings))

        webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        originalWebapp = webappUuid?.let { DataManager.instance.getWebApp(it) }

        if (originalWebapp == null) {
            showToast(this, getString(R.string.webapp_not_found), Toast.LENGTH_SHORT)
            finish()
            return
        }
        val baseWebapp = originalWebapp ?: return
        modifiedWebapp = WebApp(baseWebapp)
        originalSettingsSnapshot =
            baseWebapp.settings.deepCopy().apply { sanitize(asOverride = true) }
        val editableWebapp =
            modifiedWebapp
                ?: run {
                    finish()
                    return
                }
        txtWebAppName.text = editableWebapp.title
        textBaseUrl.text = prettyBaseUrl(editableWebapp.baseUrl)
        sandboxLabel.setText(R.string.enable_sandbox)
        switchSandbox.isChecked = editableWebapp.isUseContainer
        switchEphemeralSandbox.isChecked = editableWebapp.isEphemeralSandbox

        imgWebAppIcon.setOnClickListener { iconEditor.onIconTap() }
        titleUrlBlock.setOnClickListener { showEditDialog(editableWebapp) }
        setupFetchButton(editableWebapp)
        setupOverridePicker(editableWebapp)
        setupSandboxSwitch(editableWebapp)
        setupGroupPicker(editableWebapp)
        SettingsSurface.apply(binding.root.findViewById(R.id.identityBlock), GroupPosition.ONLY)
        SettingsSurface.bindGroup(binding.settingsRowsGroup)

        iconEditor.refreshIcon()

        if (intent.getBooleanExtra(Const.INTENT_AUTO_FETCH, false)) {
            binding.root.post {
                if (!isDestroyed && !isFinishing) fetchIconAndName(editableWebapp)
            }
        }

        setupScrollInsets(binding.scrollView)
    }

    override fun onPause() {
        super.onPause()
        modifiedWebapp?.let { webapp ->
            webapp.settings.sanitize(asOverride = true)
            lifecycleScope.launch {
                withContext(NonCancellable) {
                    DataManager.instance.replaceWebApp(webapp)
                    ShortcutHelper.updatePinnedShortcut(webapp, this@WebAppSettingsActivity)
                }
            }
        }
    }

    override fun onDestroy() {
        cancelActiveFetch()
        fetchDialog.dismiss()
        super.onDestroy()
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): WebappSettingsBinding {
        return WebappSettingsBinding.inflate(layoutInflater)
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
            txtWebAppName.text = webapp.title
            textBaseUrl.text = prettyBaseUrl(webapp.baseUrl)
            iconEditor.refreshIcon()
        }
    }

    private fun setupGroupPicker(webapp: WebApp) {
        val groups = DataManager.instance.sortedGroups
        if (groups.isEmpty()) {
            binding.groupRow.visibility = View.GONE
            return
        }

        binding.groupRow.visibility = View.VISIBLE

        val labels = groups.map { it.title } + getString(R.string.ungrouped)
        val ungroupedIndex = groups.size

        binding.btnGroupPicker.bindDropdown(
            items = labels,
            currentIndex = {
                val uuid = webapp.groupUuid
                if (uuid == null) ungroupedIndex
                else groups.indexOfFirst { it.uuid == uuid }
                    .takeIf { it >= 0 } ?: ungroupedIndex
            },
            onSelected = { i ->
                webapp.groupUuid = if (i < groups.size) groups[i].uuid else null
            },
        )
    }

    private fun setupSandboxSwitch(modifiedWebapp: WebApp) {
        val proxyController = wtf.mazy.peel.ui.settings.ProxyDropdownController(
            activity = this,
            owner = modifiedWebapp,
            proxyRow = proxyRow,
            proxyButton = btnProxyPicker,
        )
        SandboxSwitchController(
            this,
            modifiedWebapp,
            switchSandbox,
            switchEphemeralSandbox,
            ephemeralSandboxRow,
            btnClearSandbox,
            onSandboxChanged = { proxyController.refresh() },
        ).setup()
        proxyController.setup()
    }

    private fun setupFetchButton(modifiedWebapp: WebApp) {
        btnFetch.setOnClickListener {
            if (!fetchDialog.isShowing) fetchIconAndName(modifiedWebapp)
        }
    }

    private fun cancelActiveFetch() {
        fetchGeneration += 1
        activeFetcher?.cancel()
        activeFetcher = null
    }

    private fun fetchIconAndName(webapp: WebApp) {
        val url = webapp.baseUrl.trim()
        if (url.isEmpty()) {
            showToast(this, getString(R.string.enter_valid_url), Toast.LENGTH_SHORT)
            return
        }

        fetchDialog.show(R.string.fetch_step_loading, onCancel = ::cancelActiveFetch)
        fetchGeneration += 1
        val generation = fetchGeneration

        val contextId = webapp.resolveContextId()
        val usePrivateMode = webapp.resolvePrivateMode()

        val fetcher = HeadlessFetcher(
            activity = this,
            url = url,
            settings = DataManager.instance.resolveEffectiveSettings(webapp),
            contextId = contextId,
            usePrivateMode = usePrivateMode,
            onProgress = { text ->
                runOnUiThread {
                    if (generation == fetchGeneration) fetchDialog.setMessage(text)
                }
            },
            onResult = { result ->
                runOnUiThread {
                    handleFetchResult(
                        webapp,
                        result,
                        generation
                    )
                }
            },
        )
        activeFetcher = fetcher
        fetcher.start()
    }

    private fun handleFetchResult(
        webapp: WebApp,
        result: FetchResult,
        generation: Int,
    ) {
        if (generation != fetchGeneration) return
        if (isFinishing || isDestroyed) return
        activeFetcher = null
        val candidates = result.candidates
        if (candidates.isEmpty()) {
            fetchDialog.dismiss()
            showToast(this, getString(R.string.fetch_failed), Toast.LENGTH_SHORT)
            return
        }
        val urlSuggestion =
            resolveUrlSuggestion(webapp.baseUrl, result.startUrl, result.redirectedUrl)
        val scopeDomains = result.scopeExtensionDomains
        val withIcon = candidates.filter { it.icon != null }
        if (!webapp.hasCustomIcon && webapp.title.isEmpty() && withIcon.size == 1) {
            val candidate = withIcon.first()
            val titled = if (result.title != null) FetchCandidate(
                result.title,
                candidate.icon,
                candidate.source
            ) else candidate
            applyFetchResult(webapp, titled, urlSuggestion, scopeDomains)
            return
        }
        showFetchPickerDialog(webapp, candidates, urlSuggestion, scopeDomains)
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
        scopeDomains: List<String>,
    ) {
        fetchDialog.dismiss()
        val defaultIconSizePx = (resources.displayMetrics.density * 48).toInt()
        val colorSeed = webapp.letterIconSeed

        PickerDialog.show(
            activity = this,
            title = getString(R.string.choose_icon),
            items = candidates,
            onPick = { candidate ->
                applyFetchResult(webapp, candidate, urlSuggestion, scopeDomains)
            },
            configure = {
                setOnCancelListener {
                    promptUrlUpdate(webapp, urlSuggestion) {
                        promptScopeExtensions(webapp, scopeDomains)
                    }
                }
            },
        ) { candidate, iconView, nameView, _, detailView ->
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
            }
        }
    }

    private fun applyFetchResult(
        webapp: WebApp,
        candidate: FetchCandidate,
        urlSuggestion: Pair<String, Int>?,
        scopeDomains: List<String>,
    ) {
        fetchDialog.dismiss()
        if (!candidate.title.isNullOrEmpty()) {
            txtWebAppName.text = candidate.title
            webapp.title = candidate.title
        }
        if (candidate.icon != null) {
            webapp.saveIcon(candidate.icon)
        } else {
            IconCache.evict(webapp)
        }
        iconEditor.refreshIcon()
        promptUrlUpdate(webapp, urlSuggestion) { promptScopeExtensions(webapp, scopeDomains) }
    }

    private fun promptUrlUpdate(
        webapp: WebApp,
        urlSuggestion: Pair<String, Int>?,
        onDone: () -> Unit,
    ) {
        val (suggestedUrl, messageResId) = urlSuggestion ?: return onDone()
        if (suggestedUrl.trimEnd('/') == webapp.baseUrl.trimEnd('/')) return onDone()
        val message = getString(messageResId, suggestedUrl)
            .withMonoSpan(suggestedUrl)
            .withBoldSpan(suggestedUrl)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manifest_start_url_title)
            .setMessage(message)
            .setPositiveButton(R.string.manifest_start_url_update) { _, _ ->
                webapp.baseUrl = suggestedUrl
                textBaseUrl.text = prettyBaseUrl(suggestedUrl)
                onDone()
            }
            .setNegativeButton(R.string.manifest_start_url_keep) { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }

    private fun promptScopeExtensions(webapp: WebApp, domains: List<String>) {
        if (domains.isEmpty()) return
        val effective =
            DataManager.instance.resolveEffectiveSettings(webapp).sameAppDomains.orEmpty()
        val proposed = domains.filterNot { host ->
            val probe = "https://$host"
            isSameHost(webapp.baseUrl, probe) || SameAppDomainMatcher.matches(probe, effective)
        }
        if (proposed.isEmpty()) return
        lifecycleScope.launch {
            val accepted = ScopeExtensionsPrompt.confirm(
                activity = this@WebAppSettingsActivity,
                appName = webapp.title.ifEmpty { prettyBaseUrl(webapp.baseUrl) },
                domains = proposed,
            )
            if (!accepted) return@launch
            webapp.settings.sameAppDomains = effective + proposed
            overrideController.refresh()
        }
    }

    private lateinit var overrideController: OverridePickerController

    private fun setupOverridePicker(modifiedWebapp: WebApp) {
        overrideController =
            OverridePickerController(
                this,
                modifiedWebapp.settings,
                linearLayoutOverrides,
                btnAddOverride,
            )
        overrideController.setup()
    }

    override fun finish() {
        modifiedWebapp?.let {
            it.settings.sanitize(asOverride = true)
            val changed = ApplyTimingRegistry.getChangedKeys(originalSettingsSnapshot, it.settings)
            val timing = ApplyTimingRegistry.getHighestTiming(changed)
            setResult(
                RESULT_OK,
                Intent().putExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING, timing.name)
            )
        }
        super.finish()
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        overrideController.onSettingSelected(setting)
    }
}
