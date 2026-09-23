package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import wtf.mazy.peel.databinding.WebAppSettingsBinding
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.IconCache
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.shortcut.FetchCandidate
import wtf.mazy.peel.shortcut.FetchResult
import wtf.mazy.peel.shortcut.HeadlessFetcher
import wtf.mazy.peel.shortcut.Shortcuts
import wtf.mazy.peel.ui.IconEditorController
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.ui.bindDropdown
import wtf.mazy.peel.ui.common.Draft
import wtf.mazy.peel.ui.common.GroupPosition
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.SettingsSurface
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.ui.dialog.ScopeExtensionsPrompt
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.settings.OverridePickerController
import wtf.mazy.peel.ui.settings.ProxyDropdownController
import wtf.mazy.peel.ui.settings.SandboxSwitchController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.LetterIconGenerator
import wtf.mazy.peel.util.SameAppDomainMatcher
import wtf.mazy.peel.util.isSameHost
import wtf.mazy.peel.util.prettyBaseUrl
import wtf.mazy.peel.util.toast
import wtf.mazy.peel.util.withBoldSpan
import wtf.mazy.peel.util.withMonoSpan

class WebAppSettingsActivity :
    ToolbarBaseActivity<WebAppSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {
    private var draft: Draft<WebApp>? = null
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
        iconEditor = IconEditorController(this, { imgWebAppIcon }) { draft?.value }
        super.onCreate(savedInstanceState)

        setToolbarTitle(getString(R.string.web_app_settings))

        val stored = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
            ?.let { DataManager.webApp(it) }
        if (stored == null) {
            toast(R.string.webapp_not_found)
            finish()
            return
        }
        val draft = Draft(stored.copy(settings = stored.settings.deepCopy()))
        this.draft = draft
        originalSettingsSnapshot =
            stored.settings.deepCopy().apply { sanitize(asOverride = true) }
        txtWebAppName.text = stored.title
        textBaseUrl.text = prettyBaseUrl(stored.baseUrl)
        sandboxLabel.setText(R.string.enable_sandbox)
        switchSandbox.isChecked = stored.isUseContainer
        switchEphemeralSandbox.isChecked = stored.isEphemeralSandbox

        imgWebAppIcon.setOnClickListener { iconEditor.onIconTap() }
        titleUrlBlock.setOnClickListener { showEditDialog(draft) }
        setupFetchButton(draft)
        setupOverridePicker(draft.value.settings)
        setupSandboxSwitch(draft)
        setupGroupPicker(draft)
        SettingsSurface.apply(binding.root.findViewById(R.id.identityBlock), GroupPosition.ONLY)
        SettingsSurface.bindGroup(binding.settingsRowsGroup)

        iconEditor.refreshIcon()

        if (intent.getBooleanExtra(Const.INTENT_AUTO_FETCH, false)) {
            binding.root.post {
                if (!isDestroyed && !isFinishing) fetchIconAndName(draft)
            }
        }

        setupScrollInsets(binding.scrollView)
    }

    override fun onPause() {
        super.onPause()
        val webApp = draft?.value ?: return
        webApp.settings.sanitize(asOverride = true)
        lifecycleScope.launch {
            withContext(NonCancellable) {
                DataManager.replaceWebApp(webApp)
                Shortcuts.updatePinnedShortcut(webApp, this@WebAppSettingsActivity)
            }
        }
    }

    override fun onDestroy() {
        cancelActiveFetch()
        fetchDialog.dismiss()
        super.onDestroy()
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): WebAppSettingsBinding {
        return WebAppSettingsBinding.inflate(layoutInflater)
    }

    private fun showEditDialog(draft: Draft<WebApp>) {
        val webApp = draft.value
        var urlInput: TextInputEditText? = null
        showInputDialogRaw(
            InputDialogConfig(
                hintRes = R.string.name,
                prefill = webApp.title,
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
                        setText(webApp.baseUrl)
                        inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                        isSingleLine = true
                    }
                    urlLayout.addView(urlInput)
                    container.addView(urlLayout)
                },
            ),
        ) { nameInput, _ ->
            val title = nameInput.text.toString().trim()
            val baseUrl = urlInput?.text.toString().trim()
            draft.update { it.copy(title = title, baseUrl = baseUrl) }
            txtWebAppName.text = title
            textBaseUrl.text = prettyBaseUrl(baseUrl)
            iconEditor.refreshIcon()
        }
    }

    private fun setupGroupPicker(draft: Draft<WebApp>) {
        val groups = DataManager.sortedGroups
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
                val uuid = draft.value.groupUuid
                if (uuid == null) ungroupedIndex
                else groups.indexOfFirst { it.uuid == uuid }
                    .takeIf { it >= 0 } ?: ungroupedIndex
            },
            onSelected = { i ->
                val groupUuid = if (i < groups.size) groups[i].uuid else null
                draft.update { it.copy(groupUuid = groupUuid) }
            },
        )
    }

    private fun setupSandboxSwitch(draft: Draft<WebApp>) {
        val proxyController = ProxyDropdownController(
            activity = this,
            draft = draft,
            proxyRow = proxyRow,
            proxyButton = btnProxyPicker,
        )
        SandboxSwitchController(
            this,
            draft,
            switchSandbox,
            switchEphemeralSandbox,
            ephemeralSandboxRow,
            btnClearSandbox,
            onSandboxChanged = { proxyController.refresh() },
        ).setup()
        proxyController.setup()
    }

    private fun setupFetchButton(draft: Draft<WebApp>) {
        btnFetch.setOnClickListener {
            if (!fetchDialog.isShowing) fetchIconAndName(draft)
        }
    }

    private fun cancelActiveFetch() {
        fetchGeneration += 1
        activeFetcher?.cancel()
        activeFetcher = null
    }

    private fun fetchIconAndName(draft: Draft<WebApp>) {
        val webApp = draft.value
        val url = webApp.baseUrl.trim()
        if (url.isEmpty()) {
            toast(R.string.enter_valid_url)
            return
        }

        fetchDialog.show(R.string.fetch_step_loading, onCancel = ::cancelActiveFetch)
        fetchGeneration += 1
        val generation = fetchGeneration

        val contextId = webApp.resolveContextId()
        val usePrivateMode = webApp.resolvePrivateMode()

        val fetcher = HeadlessFetcher(
            activity = this,
            scope = lifecycleScope,
            url = url,
            settings = DataManager.effectiveSettings(webApp),
            contextId = contextId,
            usePrivateMode = usePrivateMode,
            onProgress = { text ->
                runOnUiThread {
                    if (generation == fetchGeneration) fetchDialog.setMessage(text)
                }
            },
            onResult = { result ->
                runOnUiThread { handleFetchResult(draft, result, generation) }
            },
        )
        activeFetcher = fetcher
        fetcher.start()
    }

    private fun handleFetchResult(
        draft: Draft<WebApp>,
        result: FetchResult,
        generation: Int,
    ) {
        if (generation != fetchGeneration) return
        if (isFinishing || isDestroyed) return
        activeFetcher = null
        val webApp = draft.value
        val candidates = result.candidates
        if (candidates.isEmpty()) {
            fetchDialog.dismiss()
            toast(R.string.fetch_failed)
            return
        }
        val urlSuggestion =
            resolveUrlSuggestion(webApp.baseUrl, result.startUrl, result.redirectedUrl)
        val scopeDomains = result.scopeExtensionDomains
        val withIcon = candidates.filter { it.icon != null }
        if (!webApp.hasCustomIcon && webApp.title.isEmpty() && withIcon.size == 1) {
            val candidate = withIcon.first()
            val titled = if (result.title != null) FetchCandidate(
                result.title,
                candidate.icon,
                candidate.source
            ) else candidate
            applyFetchResult(draft, titled, urlSuggestion, scopeDomains)
            return
        }
        showFetchPickerDialog(draft, candidates, urlSuggestion, scopeDomains)
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
        draft: Draft<WebApp>,
        candidates: List<FetchCandidate>,
        urlSuggestion: Pair<String, Int>?,
        scopeDomains: List<String>,
    ) {
        fetchDialog.dismiss()
        val defaultIconSizePx = (resources.displayMetrics.density * 48).toInt()
        val colorSeed = draft.value.letterIconSeed

        PickerDialog.show(
            activity = this,
            title = getString(R.string.choose_icon),
            items = candidates,
            onPick = { candidate ->
                applyFetchResult(draft, candidate, urlSuggestion, scopeDomains)
            },
            configure = {
                setOnCancelListener {
                    promptUrlUpdate(draft, urlSuggestion) {
                        promptScopeExtensions(draft, scopeDomains)
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
        draft: Draft<WebApp>,
        candidate: FetchCandidate,
        urlSuggestion: Pair<String, Int>?,
        scopeDomains: List<String>,
    ) {
        fetchDialog.dismiss()
        if (!candidate.title.isNullOrEmpty()) {
            txtWebAppName.text = candidate.title
            draft.update { it.copy(title = candidate.title) }
        }
        if (candidate.icon != null) {
            draft.value.saveIcon(candidate.icon)
        } else {
            IconCache.evict(draft.value)
        }
        iconEditor.refreshIcon()
        promptUrlUpdate(draft, urlSuggestion) { promptScopeExtensions(draft, scopeDomains) }
    }

    private fun promptUrlUpdate(
        draft: Draft<WebApp>,
        urlSuggestion: Pair<String, Int>?,
        onDone: () -> Unit,
    ) {
        val (suggestedUrl, messageResId) = urlSuggestion ?: return onDone()
        if (suggestedUrl.trimEnd('/') == draft.value.baseUrl.trimEnd('/')) return onDone()
        val message = getString(messageResId, suggestedUrl)
            .withMonoSpan(suggestedUrl)
            .withBoldSpan(suggestedUrl)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manifest_start_url_title)
            .setMessage(message)
            .setPositiveButton(R.string.manifest_start_url_update) { _, _ ->
                draft.update { it.copy(baseUrl = suggestedUrl) }
                textBaseUrl.text = prettyBaseUrl(suggestedUrl)
                onDone()
            }
            .setNegativeButton(R.string.manifest_start_url_keep) { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }

    private fun promptScopeExtensions(draft: Draft<WebApp>, domains: List<String>) {
        if (domains.isEmpty()) return
        val webApp = draft.value
        val effective = DataManager.effectiveSettings(webApp).sameAppDomains
        val proposed = domains.filterNot { host ->
            val probe = "https://$host"
            isSameHost(webApp.baseUrl, probe) || SameAppDomainMatcher.matches(probe, effective)
        }
        if (proposed.isEmpty()) return
        lifecycleScope.launch {
            val accepted = ScopeExtensionsPrompt.confirm(
                activity = this@WebAppSettingsActivity,
                appName = webApp.title.ifEmpty { prettyBaseUrl(webApp.baseUrl) },
                domains = proposed,
            )
            if (!accepted) return@launch
            webApp.settings.sameAppDomains = effective + proposed
            overrideController.refresh()
        }
    }

    private lateinit var overrideController: OverridePickerController

    private fun setupOverridePicker(settings: WebAppSettings) {
        overrideController =
            OverridePickerController(
                this,
                settings,
                linearLayoutOverrides,
                btnAddOverride,
            )
        overrideController.setup()
    }

    override fun finish() {
        draft?.value?.let {
            it.settings.sanitize(asOverride = true)
            val changed = ApplyTimingRegistry.changedKeys(originalSettingsSnapshot, it.settings)
            val timing = ApplyTimingRegistry.highestTiming(changed)
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
