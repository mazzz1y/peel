package wtf.mazy.peel.activities

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.GlobalSettingsBinding
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.model.SettingType
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : ToolbarBaseActivity<GlobalSettingsBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setToolbarTitle(getString(R.string.global_settings))

        setupDefaultSettingsUI()
        setupKeyboardListener()
    }

    private fun setupKeyboardListener() {
        val rootView = binding.root
        val contentContainer = binding.linearLayoutGlobalSettings

        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keyboardHeight = screenHeight - rect.bottom

            if (keyboardHeight > screenHeight * 0.15) {
                contentContainer.setPadding(0, 0, 0, keyboardHeight)
            } else {
                contentContainer.setPadding(0, 0, 0, 0)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        DataManager.instance.saveDefaultSettings()
    }

    private fun setupDefaultSettingsUI() {
        val defaultSettings = DataManager.instance.defaultSettings
        val container = binding.linearLayoutGlobalSettings
        val inflater = LayoutInflater.from(this)

        val settingsGrouped =
            SettingRegistry.getAllSettings()
                .groupBy { it.category }
                .toSortedMap(compareBy { it.ordinal })

        val categories = settingsGrouped.keys.toList()
        settingsGrouped.forEach { (category, settings) ->
            val headerView =
                inflater.inflate(R.layout.item_setting_category_header, container, false)
                    as TextView
            headerView.text = getString(category.displayNameResId)
            container.addView(headerView)

            settings.forEach { setting ->
                val view =
                    when (setting.type) {
                        SettingType.BOOLEAN ->
                            inflater
                                .inflate(R.layout.item_setting_boolean, container, false)
                                .apply { setupBoolean(this, setting, defaultSettings) }

                        SettingType.BOOLEAN_WITH_INT ->
                            inflater
                                .inflate(R.layout.item_setting_boolean_int, container, false)
                                .apply { setupBooleanWithInt(this, setting, defaultSettings) }

                        SettingType.TIME_RANGE ->
                            inflater
                                .inflate(R.layout.item_setting_time_range, container, false)
                                .apply { setupTimeRange(this, setting, defaultSettings) }

                        SettingType.STRING_MAP ->
                            inflater
                                .inflate(R.layout.item_setting_header_map, container, false)
                                .apply { setupHeaderMap(this, setting, defaultSettings) }
                    }
                view?.let { container.addView(it) }
            }

            if (category != categories.last()) {
                val divider =
                    View(this).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    resources.getDimensionPixelSize(R.dimen.divider_height),
                                )
                                .apply {
                                    topMargin =
                                        resources.getDimensionPixelSize(
                                            R.dimen.settings_divider_margin_top)
                                    bottomMargin =
                                        resources.getDimensionPixelSize(
                                            R.dimen.settings_divider_margin_bottom)
                                }
                        val attrs = intArrayOf(android.R.attr.listDivider)
                        withStyledAttributes(null, attrs) { background = getDrawable(0) }
                    }
                container.addView(divider)
            }
        }
    }

    private fun isSettingNonDefault(setting: SettingDefinition, webapp: WebApp): Boolean {
        val currentValue = webapp.settings.getValue(setting.key)
        val defaultValue = WebAppSettings.DEFAULTS[setting.key]
        if (currentValue != defaultValue) return true

        setting.secondaryKey?.let { key ->
            val currentSecondary = webapp.settings.getValue(key)
            val defaultSecondary = WebAppSettings.DEFAULTS[key]
            if (currentSecondary != defaultSecondary) return true
        }

        setting.tertiaryKey?.let { key ->
            val currentTertiary = webapp.settings.getValue(key)
            val defaultTertiary = WebAppSettings.DEFAULTS[key]
            if (currentTertiary != defaultTertiary) return true
        }

        return false
    }

    private fun updateUndoVisibility(
        btnUndo: ImageButton,
        setting: SettingDefinition,
        webapp: WebApp,
    ) {
        btnUndo.visibility = if (isSettingNonDefault(setting, webapp)) View.VISIBLE else View.GONE
    }

    private fun resetSettingToDefault(setting: SettingDefinition, webapp: WebApp) {
        webapp.settings.setValue(setting.key, WebAppSettings.DEFAULTS[setting.key])
        setting.secondaryKey?.let { webapp.settings.setValue(it, WebAppSettings.DEFAULTS[it]) }
        setting.tertiaryKey?.let { webapp.settings.setValue(it, WebAppSettings.DEFAULTS[it]) }
    }

    private fun setupBoolean(view: View, setting: SettingDefinition, webapp: WebApp) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)

        btnRemove?.visibility = View.GONE

        textName.text = setting.displayName
        switch.isChecked = webapp.settings.getValue(setting.key) as? Boolean ?: false

        updateUndoVisibility(btnUndo, setting, webapp)

        switch.setOnCheckedChangeListener { _, isChecked ->
            webapp.settings.setValue(setting.key, isChecked)
            updateUndoVisibility(btnUndo, setting, webapp)
        }

        btnUndo.setOnClickListener {
            resetSettingToDefault(setting, webapp)
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = webapp.settings.getValue(setting.key) as? Boolean ?: false
            switch.setOnCheckedChangeListener { _, isChecked ->
                webapp.settings.setValue(setting.key, isChecked)
                updateUndoVisibility(btnUndo, setting, webapp)
            }
            updateUndoVisibility(btnUndo, setting, webapp)
        }
    }

    private fun setupBooleanWithInt(view: View, setting: SettingDefinition, webapp: WebApp) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutNumberInput)
        val editText = view.findViewById<TextInputEditText>(R.id.editTextNumber)

        btnRemove?.visibility = View.GONE

        textName.text = setting.displayName
        val boolValue = webapp.settings.getValue(setting.key) as? Boolean ?: false
        val intValue = setting.secondaryKey?.let { webapp.settings.getValue(it) as? Int }

        switch.isChecked = boolValue
        editText.setText(intValue?.toString() ?: "")
        layout.visibility = if (boolValue) View.VISIBLE else View.GONE

        updateUndoVisibility(btnUndo, setting, webapp)

        val textWatcher =
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: android.text.Editable?) {
                    val intVal = s?.toString()?.toIntOrNull()
                    if (intVal != null && setting.secondaryKey != null) {
                        webapp.settings.setValue(setting.secondaryKey, intVal)
                        updateUndoVisibility(btnUndo, setting, webapp)
                    }
                }
            }

        switch.setOnCheckedChangeListener { _, isChecked ->
            webapp.settings.setValue(setting.key, isChecked)
            layout.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateUndoVisibility(btnUndo, setting, webapp)
        }

        editText.addTextChangedListener(textWatcher)

        btnUndo.setOnClickListener {
            resetSettingToDefault(setting, webapp)
            switch.setOnCheckedChangeListener(null)
            editText.removeTextChangedListener(textWatcher)

            val newBoolValue = webapp.settings.getValue(setting.key) as? Boolean ?: false
            val newIntValue = setting.secondaryKey?.let { webapp.settings.getValue(it) as? Int }
            switch.isChecked = newBoolValue
            editText.setText(newIntValue?.toString() ?: "")
            layout.visibility = if (newBoolValue) View.VISIBLE else View.GONE

            switch.setOnCheckedChangeListener { _, isChecked ->
                webapp.settings.setValue(setting.key, isChecked)
                layout.visibility = if (isChecked) View.VISIBLE else View.GONE
                updateUndoVisibility(btnUndo, setting, webapp)
            }
            editText.addTextChangedListener(textWatcher)
            updateUndoVisibility(btnUndo, setting, webapp)
        }
    }

    private fun setupTimeRange(view: View, setting: SettingDefinition, webapp: WebApp) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val switch = view.findViewById<MaterialSwitch>(R.id.switchSetting)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<ImageButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutTimeRange)
        val btnStart = view.findViewById<android.widget.Button>(R.id.btnTimeStart)
        val btnEnd = view.findViewById<android.widget.Button>(R.id.btnTimeEnd)

        btnRemove?.visibility = View.GONE

        textName.text = setting.displayName
        val boolValue = webapp.settings.getValue(setting.key) as? Boolean ?: false
        val startTime = setting.secondaryKey?.let { webapp.settings.getValue(it) as? String }
        val endTime = setting.tertiaryKey?.let { webapp.settings.getValue(it) as? String }

        switch.isChecked = boolValue
        btnStart.text = startTime ?: "00:00"
        btnEnd.text = endTime ?: "00:00"
        layout.visibility = if (boolValue) View.VISIBLE else View.GONE

        updateUndoVisibility(btnUndo, setting, webapp)

        switch.setOnCheckedChangeListener { _, isChecked ->
            webapp.settings.setValue(setting.key, isChecked)
            layout.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateUndoVisibility(btnUndo, setting, webapp)
        }

        btnStart.setOnClickListener {
            val parts = btnStart.text.toString().split(":")
            TimePickerDialog(
                    this,
                    { _, h, m ->
                        val time = String.format(java.util.Locale.ROOT, "%02d:%02d", h, m)
                        btnStart.text = time
                        setting.secondaryKey?.let { webapp.settings.setValue(it, time) }
                        updateUndoVisibility(btnUndo, setting, webapp)
                    },
                    parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    parts.getOrNull(1)?.toIntOrNull() ?: 0,
                    true,
                )
                .show()
        }

        btnEnd.setOnClickListener {
            val parts = btnEnd.text.toString().split(":")
            TimePickerDialog(
                    this,
                    { _, h, m ->
                        val time = String.format(java.util.Locale.ROOT, "%02d:%02d", h, m)
                        btnEnd.text = time
                        setting.tertiaryKey?.let { webapp.settings.setValue(it, time) }
                        updateUndoVisibility(btnUndo, setting, webapp)
                    },
                    parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    parts.getOrNull(1)?.toIntOrNull() ?: 0,
                    true,
                )
                .show()
        }

        btnUndo.setOnClickListener {
            resetSettingToDefault(setting, webapp)
            switch.setOnCheckedChangeListener(null)

            val newBoolValue = webapp.settings.getValue(setting.key) as? Boolean ?: false
            val newStartTime = setting.secondaryKey?.let { webapp.settings.getValue(it) as? String }
            val newEndTime = setting.tertiaryKey?.let { webapp.settings.getValue(it) as? String }
            switch.isChecked = newBoolValue
            btnStart.text = newStartTime ?: "00:00"
            btnEnd.text = newEndTime ?: "00:00"
            layout.visibility = if (newBoolValue) View.VISIBLE else View.GONE

            switch.setOnCheckedChangeListener { _, isChecked ->
                webapp.settings.setValue(setting.key, isChecked)
                layout.visibility = if (isChecked) View.VISIBLE else View.GONE
                updateUndoVisibility(btnUndo, setting, webapp)
            }
            updateUndoVisibility(btnUndo, setting, webapp)
        }
    }

    private fun setupHeaderMap(view: View, setting: SettingDefinition, webapp: WebApp) {
        val textName = view.findViewById<TextView>(R.id.textSettingName)
        val btnAdd = view.findViewById<ImageButton>(R.id.btnAddHeader)
        val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveOverride)
        val container = view.findViewById<LinearLayout>(R.id.containerHeaders)

        btnRemove?.visibility = View.GONE
        textName.text = setting.displayName

        fun refreshHeaders() {
            container.removeAllViews()
            webapp.settings.customHeaders?.forEach { (key, value) ->
                addHeaderEntryView(container, webapp, key, value)
            }
        }

        refreshHeaders()

        btnAdd.setOnClickListener {
            if (webapp.settings.customHeaders == null) {
                webapp.settings.customHeaders = mutableMapOf()
            }
            webapp.settings.customHeaders?.put("", "")
            addHeaderEntryView(container, webapp, "", "")
        }
    }

    private fun addHeaderEntryView(
        container: LinearLayout,
        webapp: WebApp,
        initialKey: String,
        initialValue: String,
    ) {
        val entryView =
            LayoutInflater.from(this).inflate(R.layout.item_header_entry, container, false)
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

                override fun afterTextChanged(s: android.text.Editable?) {
                    val newKey = s?.toString() ?: ""
                    if (newKey != currentKey) {
                        webapp.settings.customHeaders?.remove(currentKey)
                        if (newKey.isNotEmpty()) {
                            webapp.settings.customHeaders?.put(
                                newKey, editValue.text?.toString() ?: "")
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

                override fun afterTextChanged(s: android.text.Editable?) {
                    val key = editName.text?.toString() ?: ""
                    if (key.isNotEmpty()) {
                        webapp.settings.customHeaders?.put(key, s?.toString() ?: "")
                    }
                }
            })

        btnRemoveHeader.setOnClickListener {
            webapp.settings.customHeaders?.remove(currentKey)
            container.removeView(entryView)
        }

        container.addView(entryView)
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): GlobalSettingsBinding {
        return GlobalSettingsBinding.inflate(layoutInflater)
    }
}
