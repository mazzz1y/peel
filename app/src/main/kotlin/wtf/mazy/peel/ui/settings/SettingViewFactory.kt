package wtf.mazy.peel.ui.settings

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import wtf.mazy.peel.R
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebAppSettings

class SettingViewFactory(
    private val inflater: LayoutInflater,
    private val buttonStrategy: ButtonStrategy,
    private val onSettingChanged: ((String) -> Unit)? = null,
) {
    sealed interface ButtonStrategy {
        data object GlobalDefaults : ButtonStrategy

        class Override(val onRemove: (SettingDefinition) -> Unit) : ButtonStrategy
    }

    fun createView(
        container: LinearLayout,
        setting: SettingDefinition,
        settings: WebAppSettings,
    ): View {
        return when (setting) {
            is SettingDefinition.BooleanSetting ->
                inflate(R.layout.item_setting_boolean, container) {
                    setupBoolean(it, setting, settings)
                }

            is SettingDefinition.TriStateSetting ->
                inflate(R.layout.item_setting_dropdown, container) {
                    setupDropdown(it, setting, settings)
                }

            is SettingDefinition.BooleanWithIntSetting ->
                inflate(R.layout.item_setting_boolean_int, container) {
                    setupBooleanWithInt(it, setting, settings)
                }

            is SettingDefinition.StringMapSetting ->
                inflate(R.layout.item_setting_header_map, container) {
                    setupHeaderMap(it, setting, settings)
                }

            is SettingDefinition.BooleanWithCredentialsSetting ->
                inflate(R.layout.item_setting_boolean_credentials, container) {
                    setupBooleanWithCredentials(it, setting, settings)
                }
        }
    }

    private inline fun inflate(
        layoutRes: Int,
        container: LinearLayout,
        setup: (View) -> Unit
    ): View {
        val view = inflater.inflate(layoutRes, container, false)
        setup(view)
        return view
    }

    private fun setupBoolean(
        view: View,
        setting: SettingDefinition.BooleanSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)

        textName.text = view.context.getString(setting.displayNameResId)
        switch.isChecked = settings.getValue(setting.key) as? Boolean ?: false

        val switchListener = { _: CompoundButton?, isChecked: Boolean ->
            settings.setValue(setting.key, isChecked)
            updateUndoVisibility(btnUndo, setting, settings)
            onSettingChanged?.invoke(setting.key)
            Unit
        }

        configureButtons(btnRemove, btnUndo, setting, settings) {
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = settings.getValue(setting.key) as? Boolean ?: false
            switch.setOnCheckedChangeListener(switchListener)
        }

        switch.setOnCheckedChangeListener(switchListener)
    }

    private fun setupDropdown(
        view: View,
        setting: SettingDefinition.TriStateSetting,
        settings: WebAppSettings,
    ) {
        val context = view.context
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val txtValue = view.findViewById<TextView>(R.id.txtDropdownValue)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)

        textName.text = context.getString(setting.displayNameResId)

        val labels = arrayOf(
            context.getString(setting.labelOff),
            context.getString(setting.labelMid),
            context.getString(setting.labelOn),
        )
        val values = intArrayOf(
            setting.valueOff,
            setting.valueMid,
            setting.valueOn,
        )

        fun labelForValue(value: Int): String =
            labels[values.indexOf(value).coerceAtLeast(0)]

        fun syncUi() {
            val current = settings.getValue(setting.key) as? Int ?: setting.valueOff
            txtValue.text = labelForValue(current)
        }

        syncUi()

        txtValue.setOnClickListener { anchor ->
            val popup = PopupMenu(context, anchor, Gravity.END)
            labels.forEachIndexed { index, label -> popup.menu.add(0, index, index, label) }
            popup.setOnMenuItemClickListener { item ->
                settings.setValue(setting.key, values[item.itemId])
                syncUi()
                updateUndoVisibility(btnUndo, setting, settings)
                onSettingChanged?.invoke(setting.key)
                true
            }
            popup.show()
        }

        configureButtons(btnRemove, btnUndo, setting, settings) {
            syncUi()
        }
    }

    private fun setupBooleanWithInt(
        view: View,
        setting: SettingDefinition.BooleanWithIntSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutNumberInput)
        val editText = view.findViewById<TextInputEditText>(R.id.editTextNumber)

        val intKey = setting.intField.key
        val intDefault = setting.intField.defaultValue as? Int ?: 0
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

        val textWatcher =
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    if (!listenersActive) return
                    settings.setValue(intKey, s?.toString()?.toIntOrNull() ?: intDefault)
                    updateUndoVisibility(btnUndo, setting, settings)
                }
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
                onSettingChanged?.invoke(setting.key)
            }
            Unit
        }

        syncUi()
        editText.addTextChangedListener(textWatcher)
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

        configureButtons(btnRemove, btnUndo, setting, settings) {
            listenersActive = false
            syncUi()
            listenersActive = true
        }
    }


    private fun setupHeaderMap(
        view: View,
        setting: SettingDefinition.StringMapSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val btnAdd = view.findViewById<ImageButton>(R.id.btnAddHeader)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val container = view.findViewById<LinearLayout>(R.id.containerHeaders)

        textName.text = view.context.getString(setting.displayNameResId)

        when (val strategy = buttonStrategy) {
            is ButtonStrategy.GlobalDefaults -> btnRemove.visibility = View.GONE
            is ButtonStrategy.Override -> {
                btnRemove.visibility = View.VISIBLE
                btnRemove.setOnClickListener {
                    settings.customHeaders = null
                    strategy.onRemove(setting)
                }
                if (settings.customHeaders == null) {
                    settings.customHeaders = mutableMapOf()
                }
            }
        }

        fun refreshHeaders() {
            container.removeAllViews()
            settings.customHeaders?.forEach { (key, value) ->
                addHeaderEntryView(container, settings, key, value)
            }
        }
        refreshHeaders()

        btnAdd.setOnClickListener {
            if (settings.customHeaders == null) {
                settings.customHeaders = mutableMapOf()
            }
            settings.customHeaders?.put("", "")
            addHeaderEntryView(container, settings, "", "")
        }
    }

    private fun addHeaderEntryView(
        container: LinearLayout,
        settings: WebAppSettings,
        initialKey: String,
        initialValue: String,
    ) {
        val entryView = inflater.inflate(R.layout.item_header_entry, container, false)
        val editName = entryView.findViewById<TextInputEditText>(R.id.editHeaderName)
        val editValue = entryView.findViewById<TextInputEditText>(R.id.editHeaderValue)
        val btnRemoveHeader = entryView.findViewById<ImageButton>(R.id.btnRemoveHeader)

        editName.setText(initialKey)
        editValue.setText(initialValue)

        var currentKey = initialKey

        editName.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val newKey = s?.toString() ?: ""
                    if (newKey != currentKey) {
                        settings.customHeaders?.remove(currentKey)
                        if (newKey.isNotEmpty()) {
                            settings.customHeaders?.put(newKey, editValue.text?.toString() ?: "")
                        }
                        currentKey = newKey
                    }
                }
            })

        editValue.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val key = editName.text?.toString() ?: ""
                    if (key.isNotEmpty()) {
                        settings.customHeaders?.put(key, s?.toString() ?: "")
                    }
                }
            })

        btnRemoveHeader.setOnClickListener {
            settings.customHeaders?.remove(currentKey)
            container.removeView(entryView)
        }

        container.addView(entryView)
        if (initialKey.isEmpty()) editName.requestFocus()
    }

    private fun setupBooleanWithCredentials(
        view: View,
        setting: SettingDefinition.BooleanWithCredentialsSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutCredentials)
        val editUsername = view.findViewById<TextInputEditText>(R.id.editUsername)
        val editPassword = view.findViewById<TextInputEditText>(R.id.editPassword)

        val usernameKey = setting.usernameField.key
        val passwordKey = setting.passwordField.key
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

        val usernameWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!listenersActive) return
                settings.setValue(usernameKey, s?.toString() ?: "")
                updateUndoVisibility(btnUndo, setting, settings)
            }
        }

        val passwordWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!listenersActive) return
                settings.setValue(passwordKey, s?.toString() ?: "")
                updateUndoVisibility(btnUndo, setting, settings)
            }
        }

        val switchListener = { _: CompoundButton?, isChecked: Boolean ->
            if (listenersActive) {
                settings.setValue(setting.key, isChecked)
                layout.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (isChecked) editUsername.post { editUsername.requestFocus() }
                updateUndoVisibility(btnUndo, setting, settings)
                onSettingChanged?.invoke(setting.key)
            }
            Unit
        }

        syncUi()
        editUsername.addTextChangedListener(usernameWatcher)
        editPassword.addTextChangedListener(passwordWatcher)
        switch.setOnCheckedChangeListener(switchListener)
        listenersActive = true

        configureButtons(btnRemove, btnUndo, setting, settings) {
            listenersActive = false
            syncUi()
            listenersActive = true
        }
    }

    private fun configureButtons(
        btnRemove: ImageButton,
        btnUndo: ImageButton,
        setting: SettingDefinition,
        settings: WebAppSettings,
        onUndoRefreshUi: () -> Unit,
    ) {
        when (val strategy = buttonStrategy) {
            is ButtonStrategy.Override -> {
                btnUndo.visibility = View.GONE
                btnRemove.setOnClickListener { strategy.onRemove(setting) }
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
        btnUndo: ImageButton,
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
