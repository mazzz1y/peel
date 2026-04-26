package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
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
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.settings.OverridePickerController
import wtf.mazy.peel.ui.settings.SandboxSwitchController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.displayUrl
import wtf.mazy.peel.util.withBoldSpan
import wtf.mazy.peel.util.withMonoSpan

class WebAppSettingsActivity :
    ToolbarBaseActivity<WebappSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {
    var webappUuid: String? = null
    var originalWebapp: WebApp? = null
    private var modifiedWebapp: WebApp? = null
    private var isEditingDefaults: Boolean = false
    private lateinit var iconEditor: IconEditorController
    private var fetchDialog: AlertDialog? = null
    private var fetchDialogText: TextView? = null
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
    private val btnClearSandbox get() = binding.root.findViewById<ImageButton>(R.id.btnClearSandbox)
    private val btnAddOverride get() = binding.root.findViewById<ImageButton>(R.id.btnAddOverride)
    private val linearLayoutOverrides get() = binding.root.findViewById<LinearLayout>(R.id.linearLayoutOverrides)
    private val sectionOverrideHeader get() = binding.root.findViewById<View>(R.id.sectionOverrideHeader)

    override fun onCreate(savedInstanceState: Bundle?) {
        iconEditor = IconEditorController(this, { imgWebAppIcon }) { modifiedWebapp }
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
        originalSettingsSnapshot = baseWebapp.settings.deepCopy()
        val editableWebapp =
            modifiedWebapp
                ?: run {
                    finish()
                    return
                }
        txtWebAppName.text = editableWebapp.title
        textBaseUrl.text = displayUrl(editableWebapp.baseUrl)
        sandboxLabel.setText(R.string.enable_sandbox)
        switchSandbox.isChecked = editableWebapp.isUseContainer
        switchEphemeralSandbox.isChecked = editableWebapp.isEphemeralSandbox

        imgWebAppIcon.setOnClickListener { iconEditor.onIconTap() }
        titleUrlBlock.setOnClickListener { showEditDialog(editableWebapp) }
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
        binding.groupRow.visibility = View.GONE
        binding.groupDivider.visibility = View.GONE
        sectionOverrideHeader.visibility = View.GONE
        linearLayoutOverrides.visibility = View.GONE
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
            txtWebAppName.text = webapp.title
            textBaseUrl.text = displayUrl(webapp.baseUrl)
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
        SandboxSwitchController(
            this,
            modifiedWebapp,
            switchSandbox,
            switchEphemeralSandbox,
            ephemeralSandboxRow,
            btnClearSandbox,
        ).setup()
    }

    private fun setupFetchButton(modifiedWebapp: WebApp) {
        btnFetch.setOnClickListener {
            if (fetchDialog == null) fetchIconAndName(modifiedWebapp)
        }
    }

    private fun showFetchProgress() {
        val dp = resources.displayMetrics.density
        val text =
            TextView(this).apply {
                setText(R.string.fetch_step_loading)
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
                    if (generation == fetchGeneration) fetchDialogText?.text = text
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
            dismissFetchProgress()
            showToast(this, getString(R.string.fetch_failed), Toast.LENGTH_SHORT)
            return
        }
        val urlSuggestion =
            resolveUrlSuggestion(webapp.baseUrl, result.startUrl, result.redirectedUrl)
        val withIcon = candidates.filter { it.icon != null }
        if (!webapp.hasCustomIcon && webapp.title.isEmpty() && withIcon.size == 1) {
            val candidate = withIcon.first()
            val titled = if (result.title != null) FetchCandidate(
                result.title,
                candidate.icon,
                candidate.source
            ) else candidate
            applyFetchResult(webapp, titled, urlSuggestion)
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

        PickerDialog.show(
            activity = this,
            title = getString(R.string.choose_icon),
            items = candidates,
            onPick = { candidate -> applyFetchResult(webapp, candidate, urlSuggestion) },
            configure = {
                setOnCancelListener {
                    urlSuggestion?.let { (url, messageResId) ->
                        promptUrlUpdate(webapp, url, messageResId)
                    }
                }
            },
        ) { candidate, iconView, nameView, detailView ->
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
    }

    private fun applyFetchResult(
        webapp: WebApp,
        candidate: FetchCandidate,
        urlSuggestion: Pair<String, Int>?,
    ) {
        dismissFetchProgress()
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
        urlSuggestion?.let { (url, messageResId) -> promptUrlUpdate(webapp, url, messageResId) }
    }

    private fun promptUrlUpdate(webapp: WebApp, suggestedUrl: String, messageResId: Int) {
        if (suggestedUrl.trimEnd('/') == webapp.baseUrl.trimEnd('/')) return
        val message = getString(messageResId, suggestedUrl)
            .withMonoSpan(suggestedUrl)
            .withBoldSpan(suggestedUrl)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manifest_start_url_title)
            .setMessage(message)
            .setPositiveButton(R.string.manifest_start_url_update) { _, _ ->
                webapp.baseUrl = suggestedUrl
                textBaseUrl.text = displayUrl(suggestedUrl)
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
                linearLayoutOverrides,
                btnAddOverride,
            )
        overrideController.setup()
    }

    override fun finish() {
        modifiedWebapp?.let {
            it.settings.sanitize(asOverride = !isEditingDefaults)
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
