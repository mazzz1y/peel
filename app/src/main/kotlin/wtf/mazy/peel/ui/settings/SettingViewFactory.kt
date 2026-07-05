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
            is SettingDefinition.StringMapSetting -> R.layout.item_setting_string_map
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
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<MaterialButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutCredentials)
        val editUsername = view.findViewById<TextInputEditText>(R.id.editUsername)
        val editPassword = view.findViewById<TextInputEditText>(R.id.editPassword)

        val usernameKey = setting.usernameField.key
        val passwordKey = setting.passwordField.key
        resetWidgetListeners(view)
        textName.text = view.context.getString(setting.displayNameResId)

        fun syncUi() {
            val boolVal = settings.getValue(setting.key) as? Boolean ?: false
            switch.isChecked = boolVal
            editUsername.setText(settings.getValue(usernameKey) as? String ?: "")
            editPassword.setText(settings.getValue(passwordKey) as? String ?: "")
            layout.visibility = if (boolVal) View.VISIBLE else View.GONE
            updateUndoVisibility(btnUndo, setting, settings)
        }

        var listenersActive = false

        val switchListener = { _: CompoundButton?, isChecked: Boolean ->
            if (listenersActive) {
                settings.setValue(setting.key, isChecked)
                layout.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (isChecked) editUsername.post { editUsername.requestFocus() }
                updateUndoVisibility(btnUndo, setting, settings)
            }
        }

        syncUi()
        editUsername.replaceWatcher { s ->
            if (!listenersActive) return@replaceWatcher
            settings.setValue(usernameKey, s?.toString() ?: "")
            updateUndoVisibility(btnUndo, setting, settings)
        }
        editPassword.replaceWatcher { s ->
            if (!listenersActive) return@replaceWatcher
            settings.setValue(passwordKey, s?.toString() ?: "")
            updateUndoVisibility(btnUndo, setting, settings)
        }
        switch.setOnCheckedChangeListener(switchListener)
        listenersActive = true

        configureButtons(view, btnRemove, btnUndo, setting, settings) {
            listenersActive = false
            syncUi()
            listenersActive = true
        }
    }

    private fun setupBooleanWithString(
        view: View,
        setting: SettingDefinition.BooleanWithStringSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<MaterialButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutStringInput)
        val editText = view.findViewById<TextInputEditText>(R.id.editStringValue)

        val stringKey = setting.stringField.key
        resetWidgetListeners(view)
        textName.text = view.context.getString(setting.displayNameResId)
        editText.hint = view.context.getString(setting.hintResId)

        fun syncUi() {
            val boolVal = settings.getValue(setting.key) as? Boolean ?: false
            switch.isChecked = boolVal
            editText.setText(settings.getValue(stringKey) as? String ?: "")
            layout.visibility = if (boolVal) View.VISIBLE else View.GONE
            updateUndoVisibility(btnUndo, setting, settings)
        }

        var listenersActive = false

        val switchListener = { _: CompoundButton?, isChecked: Boolean ->
            if (listenersActive) {
                settings.setValue(setting.key, isChecked)
                layout.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (isChecked) editText.post { editText.requestFocus() }
                updateUndoVisibility(btnUndo, setting, settings)
            }
        }

        syncUi()
        editText.replaceWatcher { s ->
            if (!listenersActive) return@replaceWatcher
            settings.setValue(stringKey, s?.toString() ?: "")
            updateUndoVisibility(btnUndo, setting, settings)
        }
        switch.setOnCheckedChangeListener(switchListener)
        listenersActive = true

        configureButtons(view, btnRemove, btnUndo, setting, settings) {
            listenersActive = false
            syncUi()
            listenersActive = true
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

        container.removeAllViews()
        getMap(settings, setting.key)?.forEach { (k, v) ->
            addStringMapEntryView(container, settings, setting, k, v)
        }

        btnAdd.setOnClickListener {
            addStringMapEntryView(container, settings, setting, "", "")
        }
    }

    private fun addStringMapEntryView(
        container: LinearLayout,
        settings: WebAppSettings,
        setting: SettingDefinition.StringMapSetting,
        initialKey: String,
        initialValue: String,
    ) {
        val entryView = inflater.inflate(R.layout.item_string_map_entry, container, false)
        val keyLayout =
            entryView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutEntryKey)
        val valueLayout =
            entryView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutEntryValue)
        val editKey = entryView.findViewById<TextInputEditText>(R.id.editEntryKey)
        val editValue = entryView.findViewById<TextInputEditText>(R.id.editEntryValue)
        val btnRemoveEntry = entryView.findViewById<MaterialButton>(R.id.btnRemoveEntry)

        val context = entryView.context
        keyLayout.hint = context.getString(setting.keyHintResId)
        valueLayout.hint = context.getString(setting.valueHintResId)

        editKey.setText(initialKey)
        editValue.setText(initialValue)

        var currentKey = initialKey

        editKey.replaceWatcher { s ->
            val newKey = s?.toString() ?: ""
            if (newKey == currentKey) return@replaceWatcher
            val map = getMap(settings, setting.key).orEmpty().toMutableMap()
            map.remove(currentKey)
            if (newKey.isNotEmpty()) {
                map[newKey] = editValue.text?.toString() ?: ""
            }
            setMap(settings, setting.key, map)
            currentKey = newKey
        }

        editValue.replaceWatcher { s ->
            val key = editKey.text?.toString() ?: ""
            if (key.isEmpty()) return@replaceWatcher
            val map = getMap(settings, setting.key).orEmpty().toMutableMap()
            map[key] = s?.toString() ?: ""
            setMap(settings, setting.key, map)
        }

        btnRemoveEntry.setOnClickListener {
            val map = getMap(settings, setting.key).orEmpty().toMutableMap()
            map.remove(currentKey)
            setMap(settings, setting.key, map)
            container.removeView(entryView)
        }

        container.addView(entryView)
        if (initialKey.isEmpty()) editKey.requestFocus()
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
