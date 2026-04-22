package wtf.mazy.peel.ui.settings

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import wtf.mazy.peel.R
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebAppSettings
import java.util.WeakHashMap

class SettingViewFactory(
    private val inflater: LayoutInflater,
    private val buttonStrategy: ButtonStrategy,
) {
    sealed interface ButtonStrategy {
        data object GlobalDefaults : ButtonStrategy

        class Override(val onRemove: (SettingDefinition) -> Unit) : ButtonStrategy
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
        }
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

        resetWidgetListeners(view)
        textName.text = view.context.getString(setting.displayNameResId)
        switch.isChecked = settings.getValue(setting.key) as? Boolean ?: false

        val switchListener = { _: CompoundButton?, isChecked: Boolean ->
            settings.setValue(setting.key, isChecked)
            updateUndoVisibility(btnUndo, setting, settings)
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
        setting: SettingDefinition.ChoiceSetting,
        settings: WebAppSettings,
    ) {
        val context = view.context
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val txtValue = view.findViewById<MaterialButton>(R.id.txtDropdownValue)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)

        textName.text = context.getString(setting.displayNameResId)

        val labels = setting.labels.map { context.getString(it) }.toTypedArray()

        fun currentIndex(): Int {
            val current = settings.getValue(setting.key) as? Int ?: setting.values[0]
            return setting.values.indexOf(current).coerceAtLeast(0)
        }

        fun syncUi() {
            txtValue.text = labels[currentIndex()]
        }

        syncUi()

        txtValue.setOnClickListener {
            val popup = PopupMenu(context, txtValue, Gravity.END)
            labels.forEachIndexed { i, label -> popup.menu.add(0, i, i, label) }
            popup.setOnMenuItemClickListener { item ->
                settings.setValue(setting.key, setting.values[item.itemId])
                syncUi()
                updateUndoVisibility(btnUndo, setting, settings)
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

        configureButtons(btnRemove, btnUndo, setting, settings) {
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
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)
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

        configureButtons(btnRemove, btnUndo, setting, settings) {
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
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)
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
