package wtf.mazy.peel.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.GlobalSettingsBinding
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.model.SettingSection
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.common.GroupPosition
import wtf.mazy.peel.ui.settings.SettingRowFactory
import wtf.mazy.peel.ui.settings.SettingsAdapter
import wtf.mazy.peel.ui.settings.SettingsListItem
import wtf.mazy.peel.util.CertificatePem
import wtf.mazy.peel.util.toast

class SettingsActivity : ToolbarBaseActivity<GlobalSettingsBinding>() {

    private lateinit var editableSettings: WebApp
    private lateinit var originalSnapshot: WebAppSettings
    private lateinit var section: SettingSection

    private var pendingCertificateConsumer: ((String) -> Unit)? = null

    private val certificatePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val consumer = pendingCertificateConsumer ?: return@registerForActivityResult
        pendingCertificateConsumer = null
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            val pem = text?.let { CertificatePem.normalize(it) }
            if (pem == null) {
                val reason = when {
                    text == null -> R.string.setting_trusted_certificates_read_failed
                    CertificatePem.validate(text) == CertificatePem.Result.NotCa ->
                        R.string.setting_trusted_certificates_not_ca

                    else -> R.string.setting_trusted_certificates_invalid
                }
                toast(reason)
                return@launch
            }
            consumer(pem)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = intent.getStringExtra(EXTRA_SECTION)
            ?.let { runCatching { SettingSection.valueOf(it) }.getOrNull() }
            ?: SettingSection.GLOBAL
        setToolbarTitle(getString(section.displayNameResId))
        editableSettings =
            DataManager.globalSettings.let { it.copy(settings = it.settings.deepCopy()) }
        originalSnapshot = editableSettings.settings.deepCopy().apply { sanitize() }
        setupDefaultSettingsUI()
    }

    override fun onPause() {
        super.onPause()
        editableSettings.settings.sanitize()
        lifecycleScope.launch {
            withContext(NonCancellable) {
                DataManager.setGlobalSettings(editableSettings)
            }
        }
    }

    override fun finish() {
        editableSettings.settings.sanitize()
        val changed =
            ApplyTimingRegistry.changedKeys(originalSnapshot, editableSettings.settings)
        val timing = ApplyTimingRegistry.highestTiming(changed)
        setResult(
            RESULT_OK,
            Intent().putExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING, timing.name)
        )
        super.finish()
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): GlobalSettingsBinding {
        return GlobalSettingsBinding.inflate(layoutInflater)
    }

    private fun setupDefaultSettingsUI() {
        val settings = editableSettings.settings
        val factory = SettingRowFactory(
            layoutInflater,
            SettingRowFactory.ButtonStrategy.GlobalDefaults,
            lifecycleScope,
            certificateImporter = { consumer ->
                pendingCertificateConsumer = consumer
                certificatePickerLauncher.launch(CERTIFICATE_MIME_TYPES)
            },
        )

        val settingsGrouped =
            SettingRegistry.forSection(section)
                .groupBy { it.category }
                .toSortedMap(compareBy { it.ordinal })

        val items = buildList {
            settingsGrouped.forEach { (category, definitions) ->
                add(SettingsListItem.Header(category))
                definitions.forEachIndexed { index, definition ->
                    add(
                        SettingsListItem.Setting(
                            definition,
                            GroupPosition.of(index, definitions.size),
                        )
                    )
                }
            }
        }

        binding.recyclerSettings.layoutManager = LinearLayoutManager(this)
        binding.recyclerSettings.adapter = SettingsAdapter(items, settings, factory)
        setupScrollInsets(binding.recyclerSettings)
    }

    companion object {
        const val EXTRA_SECTION = "section"

        private val CERTIFICATE_MIME_TYPES = arrayOf(
            "application/x-pem-file",
            "application/x-x509-ca-cert",
            "text/plain",
            "application/octet-stream",
        )

        fun intentForSection(context: Context, section: SettingSection): Intent =
            Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_SECTION, section.name)
    }
}
