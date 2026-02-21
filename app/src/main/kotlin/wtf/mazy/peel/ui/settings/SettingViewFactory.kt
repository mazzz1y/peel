package wtf.mazy.peel.ui.settings

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Locale
import wtf.mazy.peel.R
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebAppSettings

class SettingViewFactory(
    private val inflater: LayoutInflater,
    private val buttonStrategy: ButtonStrategy,
) {
    sealed interface ButtonStrategy {
        class GlobalDefaults : ButtonStrategy

        class Override(val onRemove: (SettingDefinition) -> Unit) : ButtonStrategy
    }

    fun createView(
        container: LinearLayout,
        setting: SettingDefinition,
        settings: WebAppSettings,
    ): View {
        return when (setting) {
            is SettingDefinition.BooleanSetting ->
                inflate(
                    R.layout.item_setting_boolean,
                    container,
                ) {
                    setupBoolean(it, setting, settings)
                }

            is SettingDefinition.TriStateSetting ->
                inflate(
                    R.layout.item_setting_tristate,
                    container,
                ) {
                    setupTriState(it, setting, settings)
                }

            is SettingDefinition.BooleanWithIntSetting ->
                inflate(
                    R.layout.item_setting_boolean_int,
                    container,
                ) {
                    setupBooleanWithInt(it, setting, settings)
                }

            is SettingDefinition.TimeRangeSetting ->
                inflate(
                    R.layout.item_setting_time_range,
                    container,
                ) {
                    setupTimeRange(it, setting, settings)
                }

            is SettingDefinition.StringMapSetting ->
                inflate(
                    R.layout.item_setting_header_map,
                    container,
                ) {
                    setupHeaderMap(it, setting, settings)
                }
        }
    }

    private inline fun inflate(
        layoutRes: Int,
        container: LinearLayout,
        setup: (View) -> Unit,
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

        val switchListener = { _: android.widget.CompoundButton?, isChecked: Boolean ->
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

    private fun setupTriState(
        view: View,
        setting: SettingDefinition.TriStateSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val toggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleGroup)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)

        textName.text = view.context.getString(setting.displayNameResId)

        fun checkedIdForValue(value: Int): Int = when (value) {
            WebAppSettings.PERMISSION_ASK -> R.id.btnAsk
            WebAppSettings.PERMISSION_ON -> R.id.btnAllow
            else -> R.id.btnDeny
        }

        fun valueForCheckedId(id: Int): Int = when (id) {
            R.id.btnAsk -> WebAppSettings.PERMISSION_ASK
            R.id.btnAllow -> WebAppSettings.PERMISSION_ON
            else -> WebAppSettings.PERMISSION_OFF
        }

        val current = settings.getValue(setting.key) as? Int ?: WebAppSettings.PERMISSION_OFF
        toggleGroup.check(checkedIdForValue(current))

        val listener = MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                settings.setValue(setting.key, valueForCheckedId(checkedId))
                updateUndoVisibility(btnUndo, setting, settings)
            }
        }

        configureButtons(btnRemove, btnUndo, setting, settings) {
            toggleGroup.removeOnButtonCheckedListener(listener)
            val newValue = settings.getValue(setting.key) as? Int ?: WebAppSettings.PERMISSION_OFF
            toggleGroup.check(checkedIdForValue(newValue))
            toggleGroup.addOnButtonCheckedListener(listener)
        }

        toggleGroup.addOnButtonCheckedListener(listener)
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
        textName.text = view.context.getString(setting.displayNameResId)
        val boolValue = settings.getValue(setting.key) as? Boolean ?: false
        val intValue = settings.getValue(intKey) as? Int

        switch.isChecked = boolValue
        editText.setText(intValue?.toString() ?: "")
        layout.visibility = if (boolValue) View.VISIBLE else View.GONE

        val textWatcher =
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val intVal = s?.toString()?.toIntOrNull()
                    if (intVal != null) {
                        settings.setValue(intKey, intVal)
                        updateUndoVisibility(btnUndo, setting, settings)
                    }
                }
            }

        val switchListener = { _: android.widget.CompoundButton?, isChecked: Boolean ->
            settings.setValue(setting.key, isChecked)
            layout.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateUndoVisibility(btnUndo, setting, settings)
        }

        editText.addTextChangedListener(textWatcher)

        configureButtons(btnRemove, btnUndo, setting, settings) {
            switch.setOnCheckedChangeListener(null)
            editText.removeTextChangedListener(textWatcher)

            val newBool = settings.getValue(setting.key) as? Boolean ?: false
            val newInt = settings.getValue(intKey) as? Int
            switch.isChecked = newBool
            editText.setText(newInt?.toString() ?: "")
            layout.visibility = if (newBool) View.VISIBLE else View.GONE

            switch.setOnCheckedChangeListener(switchListener)
            editText.addTextChangedListener(textWatcher)
        }

        switch.setOnCheckedChangeListener(switchListener)
    }

    private fun setupTimeRange(
        view: View,
        setting: SettingDefinition.TimeRangeSetting,
        settings: WebAppSettings,
    ) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutTimeRange)
        val btnStart = view.findViewById<Button>(R.id.btnTimeStart)
        val btnEnd = view.findViewById<Button>(R.id.btnTimeEnd)

        val startKey = setting.start.key
        val endKey = setting.end.key

        textName.text = view.context.getString(setting.displayNameResId)
        val boolValue = settings.getValue(setting.key) as? Boolean ?: false
        val startTime = settings.getValue(startKey) as? String
        val endTime = settings.getValue(endKey) as? String

        switch.isChecked = boolValue
        btnStart.text = startTime ?: "00:00"
        btnEnd.text = endTime ?: "00:00"
        layout.visibility = if (boolValue) View.VISIBLE else View.GONE

        val switchListener = { _: android.widget.CompoundButton?, isChecked: Boolean ->
            settings.setValue(setting.key, isChecked)
            layout.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateUndoVisibility(btnUndo, setting, settings)
        }

        configureButtons(btnRemove, btnUndo, setting, settings) {
            switch.setOnCheckedChangeListener(null)

            val newBool = settings.getValue(setting.key) as? Boolean ?: false
            val newStart = settings.getValue(startKey) as? String
            val newEnd = settings.getValue(endKey) as? String
            switch.isChecked = newBool
            btnStart.text = newStart ?: "00:00"
            btnEnd.text = newEnd ?: "00:00"
            layout.visibility = if (newBool) View.VISIBLE else View.GONE

            switch.setOnCheckedChangeListener(switchListener)
        }

        switch.setOnCheckedChangeListener(switchListener)

        val activity = view.context as? AppCompatActivity ?: return

        btnStart.setOnClickListener {
            val parts = btnStart.text.toString().split(":")
            showMaterialTimePicker(
                activity, parts.getOrNull(0)?.toIntOrNull() ?: 0,
                parts.getOrNull(1)?.toIntOrNull() ?: 0
            ) { h, m ->
                val time = String.format(Locale.ROOT, "%02d:%02d", h, m)
                btnStart.text = time
                settings.setValue(startKey, time)
                updateUndoVisibility(btnUndo, setting, settings)
            }
        }

        btnEnd.setOnClickListener {
            val parts = btnEnd.text.toString().split(":")
            showMaterialTimePicker(
                activity, parts.getOrNull(0)?.toIntOrNull() ?: 0,
                parts.getOrNull(1)?.toIntOrNull() ?: 0
            ) { h, m ->
                val time = String.format(Locale.ROOT, "%02d:%02d", h, m)
                btnEnd.text = time
                settings.setValue(endKey, time)
                updateUndoVisibility(btnUndo, setting, settings)
            }
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
                ) {}

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
                ) {}

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

    private fun showMaterialTimePicker(
        activity: AppCompatActivity,
        hour: Int,
        minute: Int,
        onTimeSelected: (Int, Int) -> Unit,
    ) {
        val picker =
            MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .build()
        picker.addOnPositiveButtonClickListener { onTimeSelected(picker.hour, picker.minute) }
        picker.show(activity.supportFragmentManager, "time_picker")
    }
}
