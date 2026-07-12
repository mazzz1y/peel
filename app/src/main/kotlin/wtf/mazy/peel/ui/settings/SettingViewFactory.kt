package wtf.mazy.peel.ui.settings

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.geckoview.TranslationsController
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.TranslationLanguages
import wtf.mazy.peel.browser.label
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.bindDropdown
import wtf.mazy.peel.util.SameAppDomainMatcher
import java.util.WeakHashMap

class SettingViewFactory(
    private val inflater: LayoutInflater,
    private val buttonStrategy: ButtonStrategy,
    private val coroutineScope: CoroutineScope,
) {

    sealed interface ButtonStrategy {
        data object GlobalDefaults : ButtonStrategy

        class Override(val onRemove: (SettingDefinition, View) -> Unit) : ButtonStrategy
    }

    private val watchers = WeakHashMap<EditText, TextWatcher>()

    private fun EditText.replaceWatcher(action: (Editable?) -> Unit) {
        watchers.remove(this)?.let { removeTextChangedListener(it) }
        watchers[this] = doAfterTextChanged(action)
    }

    private fun resetWidgetListeners(view: View) {
        view.findViewById<MaterialSwitch?>(R.id.switchSetting)?.setOnCheckedChangeListener(null)
        view.findViewById<EditText?>(R.id.editTextNumber)?.apply {
            onFocusChangeListener = null
            setOnEditorActionListener(null)
        }
    }

    fun createView(
        container: LinearLayout,
        setting: SettingDefinition,
        settings: WebAppSettings,
    ): View {
        val layoutRes = when (setting) {
            is SettingDefinition.BooleanSetting -> R.layout.item_setting_boolean
            is SettingDefinition.ChoiceSetting -> R.layout.item_setting_dropdown
            is SettingDefinition.BooleanWithIntSetting -> R.layout.item_setting_boolean_int
            is SettingDefinition.BooleanWithCredentialsSetting -> R.layout.item_setting_boolean_credentials
            is SettingDefinition.BooleanWithStringSetting -> R.layout.item_setting_boolean_string
            is SettingDefinition.StringMapSetting -> R.layout.item_setting_string_collection
            is SettingDefinition.StringListSetting -> R.layout.item_setting_string_collection
            is SettingDefinition.LanguagePairMapSetting -> R.layout.item_setting_language_pair_map
        }
        val view = inflater.inflate(layoutRes, container, false)
        bindView(view, setting, settings)
        return view
    }

    fun bindView(view: View, setting: SettingDefinition, settings: WebAppSettings) {
        when (setting) {
            is SettingDefinition.BooleanSetting -> setupBoolean(view, setting, settings)
            is SettingDefinition.ChoiceSetting -> setupDropdown(view, setting, settings)
            is SettingDefinition.BooleanWithIntSetting -> setupBooleanWithInt(
                view,
                setting,
                settings
            )

            is SettingDefinition.BooleanWithCredentialsSetting -> setupBooleanWithCredentials(
                view,
                setting,
                settings
            )

            is SettingDefinition.BooleanWithStringSetting -> setupBooleanWithString(
                view,
                setting,
                settings
            )

            is SettingDefinition.StringMapSetting -> setupStringMap(view, setting, settings)
            is SettingDefinition.StringListSetting -> setupStringList(view, setting, settings)
            is SettingDefinition.LanguagePairMapSetting -> setupLanguagePairMap(
                view,
                setting,
                settings,
            )
        }
    }

    private fun setupBoolean(
        view: View,
        setting: SettingDefinition.BooleanSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<MaterialButton>(R.id.btnUndo)

        resetWidgetListeners(view)
        textName.text = view.context.getString(setting.displayNameResId)
        switch.isChecked = settings.getValue(setting.key) as? Boolean ?: false

        val switchListener = { _: CompoundButton?, isChecked: Boolean ->
            settings.setValue(setting.key, isChecked)
            updateUndoVisibility(btnUndo, setting, settings)
        }

        configureButtons(view, btnRemove, btnUndo, setting, settings) {
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = settings.getValue(setting.key) as? Boolean ?: false
            switch.setOnCheckedChangeListener(switchListener)
        }

        switch.setOnCheckedChangeListener(switchListener)
    }

    private fun setupDropdown(
        view: View,
        setting: SettingDefinition.ChoiceSetting,
        settings: WebAppSettings,
    ) {
        val context = view.context
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val txtValue = view.findViewById<MaterialButton>(R.id.txtDropdownValue)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<MaterialButton>(R.id.btnUndo)

        textName.text = context.getString(setting.displayNameResId)
        val labels = setting.labels.map { context.getString(it) }

        fun currentIndex(): Int {
            val current = settings.getValue(setting.key) as? Int ?: setting.values[0]
            return setting.values.indexOf(current).coerceAtLeast(0)
        }

        txtValue.bindDropdown(
            items = labels,
            currentIndex = ::currentIndex,
            onSelected = { i ->
                settings.setValue(setting.key, setting.values[i])
                updateUndoVisibility(btnUndo, setting, settings)
            },
        )

        configureButtons(view, btnRemove, btnUndo, setting, settings) {
            txtValue.text = labels[currentIndex()]
        }
    }

    private fun setupBooleanWithInt(
        view: View,
        setting: SettingDefinition.BooleanWithIntSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<MaterialButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutNumberInput)
        val editText = view.findViewById<TextInputEditText>(R.id.editTextNumber)

        val intKey = setting.intField.key
        val intDefault = setting.intField.defaultValue as? Int ?: 0
        resetWidgetListeners(view)
        textName.text = view.context.getString(setting.displayNameResId)

        fun ensureIntDefault() {
            val current = settings.getValue(intKey) as? Int ?: 0
            if (current <= 0) settings.setValue(intKey, intDefault)
        }

        fun syncUi() {
            val boolVal = settings.getValue(setting.key) as? Boolean ?: false
            switch.isChecked = boolVal
            ensureIntDefault()
            editText.setText((settings.getValue(intKey) as? Int)?.toString() ?: "")
            layout.visibility = if (boolVal) View.VISIBLE else View.GONE
            updateUndoVisibility(btnUndo, setting, settings)
        }

        var listenersActive = false

        editText.replaceWatcher { s ->
            if (!listenersActive) return@replaceWatcher
            settings.setValue(intKey, s?.toString()?.toIntOrNull() ?: intDefault)
            updateUndoVisibility(btnUndo, setting, settings)
        }

        val switchListener = { _: CompoundButton?, isChecked: Boolean ->
            if (listenersActive) {
                settings.setValue(setting.key, isChecked)
                listenersActive = false
                ensureIntDefault()
                editText.setText((settings.getValue(intKey) as? Int)?.toString() ?: "")
                layout.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (isChecked) editText.post { editText.requestFocus() }
                listenersActive = true
                updateUndoVisibility(btnUndo, setting, settings)
            }
        }

        syncUi()
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                editText.clearFocus()
                true
            } else false
        }
        editText.onFocusChangeListener =
            View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && listenersActive) {
                    listenersActive = false
                    ensureIntDefault()
                    editText.setText((settings.getValue(intKey) as? Int)?.toString() ?: "")
                    listenersActive = true
                }
            }
        switch.setOnCheckedChangeListener(switchListener)
        listenersActive = true

        configureButtons(view, btnRemove, btnUndo, setting, settings) {
            listenersActive = false
            syncUi()
            listenersActive = true
        }
    }

    private fun setupBooleanWithCredentials(
        view: View,
        setting: SettingDefinition.BooleanWithCredentialsSetting,
        settings: WebAppSettings,
    ) {
        val context = view.context
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<MaterialButton>(R.id.btnUndo)
        val btnEditValue = view.findViewById<MaterialButton>(R.id.btnEditValue)

        val usernameKey = setting.usernameField.key
        val passwordKey = setting.passwordField.key
        resetWidgetListeners(view)
        textName.text = context.getString(setting.displayNameResId)

        fun username(): String = settings.getValue(usernameKey) as? String ?: ""
        fun password(): String = settings.getValue(passwordKey) as? String ?: ""

        fun renderValue() {
            val enabled = settings.getValue(setting.key) as? Boolean ?: false
            btnEditValue.visibility = if (enabled) View.VISIBLE else View.GONE
            updateUndoVisibility(btnUndo, setting, settings)
        }

        lateinit var switchListener: (CompoundButton?, Boolean) -> Unit

        fun setChecked(checked: Boolean) {
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = checked
            switch.setOnCheckedChangeListener(switchListener)
        }

        fun openDialog(onCancel: () -> Unit = {}) {
            SettingDialogs.showCredentials(
                context,
                setting.displayNameResId,
                username(),
                password(),
                onCancel = onCancel,
            ) { credentials ->
                settings.setValue(usernameKey, credentials.username)
                settings.setValue(passwordKey, credentials.password)
                if (credentials.username.isEmpty() && credentials.password.isEmpty()) {
                    settings.setValue(setting.key, false)
                    setChecked(false)
                }
                renderValue()
            }
        }

        switchListener = { _: CompoundButton?, isChecked: Boolean ->
            settings.setValue(setting.key, isChecked)
            renderValue()
            if (isChecked && username().isEmpty() && password().isEmpty()) {
                openDialog {
                    if (username().isEmpty() && password().isEmpty()) {
                        settings.setValue(setting.key, false)
                        setChecked(false)
                        renderValue()
                    }
                }
            }
        }

        switch.isChecked = settings.getValue(setting.key) as? Boolean ?: false
        renderValue()
        switch.setOnCheckedChangeListener(switchListener)
        btnEditValue.setOnClickListener { openDialog() }

        configureButtons(view, btnRemove, btnUndo, setting, settings) {
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = settings.getValue(setting.key) as? Boolean ?: false
            renderValue()
            switch.setOnCheckedChangeListener(switchListener)
        }
    }

    private fun setupBooleanWithString(
        view: View,
        setting: SettingDefinition.BooleanWithStringSetting,
        settings: WebAppSettings,
    ) {
        val context = view.context
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<MaterialButton>(R.id.btnUndo)
        val cardValue = view.findViewById<View>(R.id.cardValue)
        val btnValue = view.findViewById<MaterialButton>(R.id.btnEntryValue)

        val stringKey = setting.stringField.key
        resetWidgetListeners(view)
        textName.text = context.getString(setting.displayNameResId)

        fun value(): String = settings.getValue(stringKey) as? String ?: ""

        fun renderValue() {
            val enabled = settings.getValue(setting.key) as? Boolean ?: false
            cardValue.visibility = if (enabled) View.VISIBLE else View.GONE
            btnValue.text = value()
            updateUndoVisibility(btnUndo, setting, settings)
        }

        lateinit var switchListener: (CompoundButton?, Boolean) -> Unit

        fun setChecked(checked: Boolean) {
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = checked
            switch.setOnCheckedChangeListener(switchListener)
        }

        fun openDialog(onCancel: () -> Unit = {}) {
            SettingDialogs.showString(
                context,
                setting.displayNameResId,
                setting.hintResId,
                value(),
                maxLines = 3,
                onCancel = onCancel,
            ) { text ->
                settings.setValue(stringKey, text)
                if (text.isEmpty()) {
                    settings.setValue(setting.key, false)
                    setChecked(false)
                }
                renderValue()
            }
        }

        switchListener = { _: CompoundButton?, isChecked: Boolean ->
            settings.setValue(setting.key, isChecked)
            renderValue()
            if (isChecked && value().isEmpty()) {
                openDialog {
                    if (value().isEmpty()) {
                        settings.setValue(setting.key, false)
                        setChecked(false)
                        renderValue()
                    }
                }
            }
        }

        switch.isChecked = settings.getValue(setting.key) as? Boolean ?: false
        renderValue()
        switch.setOnCheckedChangeListener(switchListener)
        btnValue.setOnClickListener { openDialog() }

        configureButtons(view, btnRemove, btnUndo, setting, settings) {
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = settings.getValue(setting.key) as? Boolean ?: false
            renderValue()
            switch.setOnCheckedChangeListener(switchListener)
        }
    }

    private fun setupLanguagePairMap(
        view: View,
        setting: SettingDefinition.LanguagePairMapSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btnAddEntry)
        val switchTranslator = view.findViewById<MaterialSwitch>(R.id.switchTranslator)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val container = view.findViewById<LinearLayout>(R.id.containerEntries)
        val mapKey = setting.mapField.key

        textName.text = view.context.getString(setting.displayNameResId)

        when (val strategy = buttonStrategy) {
            is ButtonStrategy.GlobalDefaults -> btnRemove.visibility = View.GONE
            is ButtonStrategy.Override -> {
                btnRemove.visibility = View.VISIBLE
                btnRemove.setOnClickListener {
                    settings.setValue(setting.key, null)
                    setMap(settings, mapKey, null)
                    strategy.onRemove(setting, view)
                }
            }
        }

        fun isEnabled(): Boolean = settings.getValue(setting.key) as? Boolean ?: false

        fun hasFreshFromAvailable(support: TranslationsController.RuntimeTranslation.TranslationSupport): Boolean {
            val used = getMap(settings, mapKey).orEmpty().keys
            val fromLanguages = support.fromLanguages ?: emptyList()
            return fromLanguages.any { it.code !in used } ||
                    TranslationLanguages.ANY_LANGUAGE !in used
        }

        fun applyAddButtonVisibility() {
            val support = TranslationLanguages.cachedSupport
            val canAdd = isEnabled() && support != null && hasFreshFromAvailable(support)
            btnAdd.visibility = if (canAdd) View.VISIBLE else View.GONE
        }

        fun applyEnabledState() {
            val enabled = isEnabled()
            container.visibility = if (enabled) View.VISIBLE else View.GONE
            applyAddButtonVisibility()
        }

        fun rebuild(support: TranslationsController.RuntimeTranslation.TranslationSupport) {
            container.removeAllViews()
            getMap(settings, mapKey)?.forEach { (from, to) ->
                container.addView(
                    buildLanguagePairEntry(
                        container, settings, setting, support, from, to,
                        onAfterChange = { applyAddButtonVisibility() },
                    ),
                )
            }
        }

        fun loadAndRebuild(then: ((TranslationsController.RuntimeTranslation.TranslationSupport) -> Unit)? = null) {
            coroutineScope.launch {
                val loaded = TranslationLanguages.listSupportedLanguages() ?: return@launch
                rebuild(loaded)
                applyAddButtonVisibility()
                then?.invoke(loaded)
            }
        }

        switchTranslator.isChecked = isEnabled()
        applyEnabledState()
        switchTranslator.setOnCheckedChangeListener { _, checked ->
            settings.setValue(setting.key, checked)
            if (!checked) {
                setMap(settings, mapKey, null)
                container.removeAllViews()
            }
            applyEnabledState()
        }

        val cached = TranslationLanguages.cachedSupport
        if (cached != null) {
            rebuild(cached)
            applyAddButtonVisibility()
        } else {
            loadAndRebuild()
        }

        btnAdd.setOnClickListener {
            if (!isEnabled()) return@setOnClickListener
            val support = TranslationLanguages.cachedSupport
            if (support != null) {
                addFreshEntry(settings, setting, support, container, ::applyAddButtonVisibility)
            } else {
                loadAndRebuild { loaded ->
                    addFreshEntry(settings, setting, loaded, container, ::applyAddButtonVisibility)
                }
            }
        }
    }

    private fun addFreshEntry(
        settings: WebAppSettings,
        setting: SettingDefinition.LanguagePairMapSetting,
        support: TranslationsController.RuntimeTranslation.TranslationSupport,
        container: LinearLayout,
        onAfterChange: () -> Unit,
    ) {
        val mapKey = setting.mapField.key
        val newFrom = pickFreshFrom(settings, setting, support)
        val newTo = pickFreshTo(support, newFrom)
        if (newFrom.isEmpty() || newTo.isEmpty() || newFrom == newTo) return
        val map = getMap(settings, mapKey).orEmpty().toMutableMap()
        map[newFrom] = newTo
        setMap(settings, mapKey, map)
        container.addView(
            buildLanguagePairEntry(
                container,
                settings,
                setting,
                support,
                newFrom,
                newTo,
                onAfterChange
            ),
        )
        onAfterChange()
    }

    private fun pickFreshFrom(
        settings: WebAppSettings,
        setting: SettingDefinition.LanguagePairMapSetting,
        support: TranslationsController.RuntimeTranslation.TranslationSupport,
    ): String {
        val used = getMap(settings, setting.mapField.key).orEmpty().keys
        val fromLanguages = (support.fromLanguages ?: emptyList()).sorted()
        fromLanguages.firstOrNull { it.code !in used }?.let { return it.code }
        if (TranslationLanguages.ANY_LANGUAGE !in used) return TranslationLanguages.ANY_LANGUAGE
        return ""
    }

    private fun pickFreshTo(
        support: TranslationsController.RuntimeTranslation.TranslationSupport,
        fromCode: String,
    ): String {
        val toLanguages = (support.toLanguages ?: emptyList()).sorted()
        return toLanguages.firstOrNull { it.code != fromCode }?.code.orEmpty()
    }

    private fun buildLanguagePairEntry(
        container: LinearLayout,
        settings: WebAppSettings,
        setting: SettingDefinition.LanguagePairMapSetting,
        support: TranslationsController.RuntimeTranslation.TranslationSupport,
        initialFrom: String,
        initialTo: String,
        onAfterChange: () -> Unit,
    ): View {
        val entryView = inflater.inflate(R.layout.item_language_pair_entry, container, false)
        val btnFrom = entryView.findViewById<MaterialButton>(R.id.btnLanguageFrom)
        val btnTo = entryView.findViewById<MaterialButton>(R.id.btnLanguageTo)
        val btnRemoveEntry = entryView.findViewById<MaterialButton>(R.id.btnRemoveEntry)

        val anyLanguageLabel =
            entryView.context.getString(R.string.setting_auto_translate_any_language)
        val realFromLanguages = (support.fromLanguages ?: emptyList()).sorted()
        val toLanguages = (support.toLanguages ?: emptyList()).sorted()
        val allFromCodes =
            listOf(TranslationLanguages.ANY_LANGUAGE) + realFromLanguages.map { it.code }
        val allFromLabels = listOf(anyLanguageLabel) + realFromLanguages.map { it.label() }

        val mapKey = setting.mapField.key
        var currentFrom = initialFrom
        var currentTo = initialTo

        fun persistEntry(previousFromCode: String, newFromCode: String, newToCode: String) {
            val map = getMap(settings, mapKey).orEmpty().toMutableMap()
            if (previousFromCode.isNotEmpty() && previousFromCode != newFromCode) {
                map.remove(previousFromCode)
            }
            if (newFromCode.isNotEmpty() && newToCode.isNotEmpty() && newFromCode != newToCode) {
                map[newFromCode] = newToCode
            }
            setMap(settings, mapKey, map)
        }

        fun fromCodesUsedByOtherRows(): Set<String> =
            getMap(settings, mapKey).orEmpty().keys - currentFrom

        fun availableFromCodes(): List<String> {
            val taken = fromCodesUsedByOtherRows()
            return allFromCodes.filter { it !in taken }
        }

        fun availableFromLabels(): List<String> {
            val taken = fromCodesUsedByOtherRows()
            return allFromCodes.zip(allFromLabels)
                .filter { (code, _) -> code !in taken }
                .map { (_, label) -> label }
        }

        fun availableToLanguages(): List<TranslationsController.Language> =
            toLanguages.filter { it.code != currentFrom }

        fun availableToLabels(): List<String> =
            availableToLanguages().map { it.label() }

        btnFrom.bindDropdown(
            itemsProvider = { availableFromLabels() },
            currentIndex = {
                availableFromCodes().indexOf(currentFrom).coerceAtLeast(0)
            },
            onSelected = { i ->
                val previousFromCode = currentFrom
                currentFrom = availableFromCodes().getOrNull(i).orEmpty()
                persistEntry(previousFromCode, currentFrom, currentTo)
                onAfterChange()
            },
        )
        btnTo.bindDropdown(
            itemsProvider = { availableToLabels() },
            currentIndex = {
                availableToLanguages().indexOfFirst { it.code == currentTo }.coerceAtLeast(0)
            },
            onSelected = { i ->
                currentTo = availableToLanguages().getOrNull(i)?.code.orEmpty()
                persistEntry(currentFrom, currentFrom, currentTo)
            },
        )

        btnRemoveEntry.setOnClickListener {
            val map = getMap(settings, mapKey).orEmpty().toMutableMap()
            map.remove(currentFrom)
            setMap(settings, mapKey, map)
            container.removeView(entryView)
            onAfterChange()
        }

        return entryView
    }

    @Suppress("UNCHECKED_CAST")
    private fun getMap(settings: WebAppSettings, key: String): Map<String, String>? =
        settings.getValue(key) as? Map<String, String>

    private fun setMap(settings: WebAppSettings, key: String, value: Map<String, String>?) {
        settings.setValue(key, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getList(settings: WebAppSettings, key: String): List<String>? =
        settings.getValue(key) as? List<String>

    private fun setList(settings: WebAppSettings, key: String, value: List<String>?) {
        settings.setValue(key, value)
    }

    private fun setupStringList(
        view: View,
        setting: SettingDefinition.StringListSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btnAddEntry)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val container = view.findViewById<LinearLayout>(R.id.containerEntries)

        textName.text = view.context.getString(setting.displayNameResId)

        when (val strategy = buttonStrategy) {
            is ButtonStrategy.GlobalDefaults -> btnRemove.visibility = View.GONE
            is ButtonStrategy.Override -> {
                btnRemove.visibility = View.VISIBLE
                btnRemove.setOnClickListener {
                    setList(settings, setting.key, null)
                    strategy.onRemove(setting, view)
                }
                if (getList(settings, setting.key) == null) {
                    setList(settings, setting.key, emptyList())
                }
            }
        }

        fun renderEntries() {
            container.removeAllViews()
            val entries = getList(settings, setting.key).orEmpty()
            container.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            entries.forEach { entry ->
                addStringListEntryView(view.context, container, settings, setting, entry) {
                    renderEntries()
                }
            }
        }

        renderEntries()

        btnAdd.setOnClickListener {
            showStringListEntryDialog(view.context, setting, "") { entry ->
                val values = getList(settings, setting.key).orEmpty()
                if (entry !in values) {
                    setList(settings, setting.key, values + entry)
                    renderEntries()
                }
            }
        }
    }

    private fun addStringListEntryView(
        context: android.content.Context,
        container: LinearLayout,
        settings: WebAppSettings,
        setting: SettingDefinition.StringListSetting,
        value: String,
        onChanged: () -> Unit,
    ) {
        val entryView = inflater.inflate(R.layout.item_string_collection_entry, container, false)
        val btnValue = entryView.findViewById<MaterialButton>(R.id.btnEntryValue)
        val btnRemoveEntry = entryView.findViewById<MaterialButton>(R.id.btnRemoveEntry)

        btnValue.text = value
        btnValue.setOnClickListener {
            showStringListEntryDialog(context, setting, value) { entry ->
                val values = getList(settings, setting.key).orEmpty()
                if (entry == value) return@showStringListEntryDialog
                if (entry !in values) {
                    setList(settings, setting.key, values.map { if (it == value) entry else it })
                    onChanged()
                }
            }
        }
        btnRemoveEntry.setOnClickListener {
            setList(settings, setting.key, getList(settings, setting.key).orEmpty() - value)
            onChanged()
        }

        container.addView(entryView)
    }

    private fun showStringListEntryDialog(
        context: android.content.Context,
        setting: SettingDefinition.StringListSetting,
        prefill: String,
        onCommit: (String) -> Unit,
    ) {
        SettingDialogs.showValidatedString(
            context = context,
            titleRes = setting.displayNameResId,
            hintRes = setting.entryHintResId,
            value = prefill,
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI,
            validate = { entry ->
                when {
                    entry.isEmpty() -> setting.entryHintResId
                    !SameAppDomainMatcher.isValid(entry) -> setting.invalidEntryResId
                    else -> null
                }
            },
            onCommit = onCommit,
        )
    }

    private fun setupStringMap(
        view: View,
        setting: SettingDefinition.StringMapSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btnAddEntry)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val container = view.findViewById<LinearLayout>(R.id.containerEntries)

        textName.text = view.context.getString(setting.displayNameResId)

        when (val strategy = buttonStrategy) {
            is ButtonStrategy.GlobalDefaults -> btnRemove.visibility = View.GONE
            is ButtonStrategy.Override -> {
                btnRemove.visibility = View.VISIBLE
                btnRemove.setOnClickListener {
                    setMap(settings, setting.key, null)
                    strategy.onRemove(setting, view)
                }
                if (getMap(settings, setting.key) == null) {
                    setMap(settings, setting.key, emptyMap())
                }
            }
        }

        fun renderEntries() {
            container.removeAllViews()
            val entries = getMap(settings, setting.key).orEmpty()
            container.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            entries.forEach { (k, v) ->
                addStringMapEntryView(container, settings, setting, k, v) { renderEntries() }
            }
        }

        renderEntries()

        btnAdd.setOnClickListener {
            showStringMapDialog(view.context, setting, "", "") { key, value ->
                val map = getMap(settings, setting.key).orEmpty().toMutableMap()
                map[key] = value
                setMap(settings, setting.key, map)
                renderEntries()
            }
        }
    }

    private fun showStringMapDialog(
        context: android.content.Context,
        setting: SettingDefinition.StringMapSetting,
        key: String,
        value: String,
        onCommit: (String, String) -> Unit,
    ) {
        SettingDialogs.showKeyValue(
            context,
            setting.displayNameResId,
            setting.keyHintResId,
            setting.valueHintResId,
            key,
            value,
            onCommit,
        )
    }

    private fun addStringMapEntryView(
        container: LinearLayout,
        settings: WebAppSettings,
        setting: SettingDefinition.StringMapSetting,
        key: String,
        value: String,
        onChanged: () -> Unit,
    ) {
        val entryView = inflater.inflate(R.layout.item_string_collection_entry, container, false)
        val btnValue = entryView.findViewById<MaterialButton>(R.id.btnEntryValue)
        val btnRemoveEntry = entryView.findViewById<MaterialButton>(R.id.btnRemoveEntry)

        btnValue.text = entryView.context.getString(R.string.setting_key_value_entry, key, value)

        fun edit() {
            showStringMapDialog(entryView.context, setting, key, value) { newKey, newValue ->
                val map = getMap(settings, setting.key).orEmpty().toMutableMap()
                if (newKey != key) map.remove(key)
                map[newKey] = newValue
                setMap(settings, setting.key, map)
                onChanged()
            }
        }

        btnValue.setOnClickListener { edit() }
        btnRemoveEntry.setOnClickListener {
            val map = getMap(settings, setting.key).orEmpty().toMutableMap()
            map.remove(key)
            setMap(settings, setting.key, map)
            onChanged()
        }

        container.addView(entryView)
    }

    private fun configureButtons(
        view: View,
        btnRemove: MaterialButton,
        btnUndo: MaterialButton,
        setting: SettingDefinition,
        settings: WebAppSettings,
        onUndoRefreshUi: () -> Unit,
    ) {
        when (val strategy = buttonStrategy) {
            is ButtonStrategy.Override -> {
                btnUndo.visibility = View.GONE
                btnRemove.setOnClickListener { strategy.onRemove(setting, view) }
            }

            is ButtonStrategy.GlobalDefaults -> {
                btnRemove.visibility = View.GONE
                updateUndoVisibility(btnUndo, setting, settings)
                btnUndo.setOnClickListener {
                    resetSettingToDefault(setting, settings)
                    onUndoRefreshUi()
                    updateUndoVisibility(btnUndo, setting, settings)
                }
            }
        }
    }

    private fun updateUndoVisibility(
        btnUndo: MaterialButton,
        setting: SettingDefinition,
        settings: WebAppSettings,
    ) {
        if (buttonStrategy !is ButtonStrategy.GlobalDefaults) return
        btnUndo.visibility = if (isSettingNonDefault(setting, settings)) View.VISIBLE else View.GONE
    }

    private fun isSettingNonDefault(setting: SettingDefinition, settings: WebAppSettings): Boolean {
        return setting.allFields.any { field ->
            settings.getValue(field.key) != WebAppSettings.DEFAULTS[field.key]
        }
    }

    private fun resetSettingToDefault(setting: SettingDefinition, settings: WebAppSettings) {
        setting.allFields.forEach { field -> settings.setValue(field.key, field.defaultValue) }
    }

}
