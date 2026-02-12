package wtf.mazy.peel.activities

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.WebappSettingsBinding
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.ShortcutIconUtils.getWidthFromIcon
import wtf.mazy.peel.util.Utility
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup

class WebAppSettingsActivity : ToolbarBaseActivity<WebappSettingsBinding>(),
    OverridePickerDialog.OnSettingSelectedListener {
    var webappUuid: String? = null
    var webapp: WebApp? = null
    private var modifiedWebapp: WebApp? = null
    private var isGlobalWebApp: Boolean = false
    private var customIconBitmap: Bitmap? = null
    private lateinit var iconPickerLauncher: ActivityResultLauncher<String>
    private var executorService: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isFetchingIcon = false

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        iconPickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let { handleSelectedIcon(it) }
            }
        executorService = Executors.newSingleThreadExecutor()
        super.onCreate(savedInstanceState)

        setToolbarTitle(getString(R.string.web_app_settings))

        webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        Utility.assert(webappUuid != null, "WebApp UUID could not be retrieved.")
        isGlobalWebApp = webappUuid == DataManager.instance.defaultSettings.uuid

        if (isGlobalWebApp) {
            webapp = DataManager.instance.defaultSettings
            prepareGlobalWebAppScreen()
        } else
            webapp =
                webappUuid?.let { DataManager.instance.getWebApp(it) }

        if (webapp == null) {
            finish()
            return
        }
        val baseWebapp = webapp ?: run {
            finish()
            return
        }
        modifiedWebapp = WebApp(baseWebapp)
        val editableWebapp = modifiedWebapp ?: run {
            finish()
            return
        }
        binding.webapp = editableWebapp
        binding.activity = this@WebAppSettingsActivity

        setupIconButton()
        setupFetchButton(editableWebapp)
        setupOverridePicker(editableWebapp)
        setupSandboxSwitch(editableWebapp)

        loadCurrentIcon(editableWebapp)

        if (intent.getBooleanExtra(Const.INTENT_AUTO_FETCH, false)) {
            binding.root.post { fetchIconAndName(editableWebapp) }
        }

        setupKeyboardListener()
    }

    private fun setupKeyboardListener() {
        val rootView = binding.root
        val contentContainer = findViewById<android.widget.LinearLayout>(R.id.contentContainer)

        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keyboardHeight = screenHeight - rect.bottom

            if (keyboardHeight > screenHeight * 0.15) {
                contentContainer?.setPadding(0, 0, 0, keyboardHeight)
            } else {
                contentContainer?.setPadding(0, 0, 0, 0)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        modifiedWebapp?.let { webapp ->
            if (isGlobalWebApp) {
                DataManager.instance.defaultSettings = webapp
            } else {
                DataManager.instance.replaceWebApp(webapp)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executorService?.shutdownNow()
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): WebappSettingsBinding {
        return WebappSettingsBinding.inflate(layoutInflater)
    }

    private fun prepareGlobalWebAppScreen() {
        binding.sectionMainSettings.visibility = View.GONE
        binding.sandboxRow.visibility = View.GONE
        binding.sectionOverrideHeader.visibility = View.GONE
        binding.linearLayoutOverrides.visibility = View.GONE
        binding.globalSettingsInfoText.visibility = View.VISIBLE
        setToolbarTitle(getString(R.string.global_web_app_settings))
    }

    private fun setupSandboxSwitch(modifiedWebapp: WebApp) {
        updateClearSandboxButtonVisibility(modifiedWebapp)

        binding.switchSandbox.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && modifiedWebapp.isUseContainer) {
                SandboxManager.releaseSandbox(this, modifiedWebapp.uuid)
            }
            modifiedWebapp.isUseContainer = isChecked
            updateClearSandboxButtonVisibility(modifiedWebapp)
        }

        binding.btnClearSandbox.setOnClickListener { showClearSandboxConfirmDialog(modifiedWebapp) }
    }

    private fun updateClearSandboxButtonVisibility(webapp: WebApp) {
        val sandboxDir = SandboxManager.getSandboxDataDir(webapp.uuid)
        binding.btnClearSandbox.visibility = if (sandboxDir.exists()) View.VISIBLE else View.GONE
    }

    private fun showClearSandboxConfirmDialog(webapp: WebApp) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(R.string.clear_sandbox_data_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> clearSandboxData(webapp) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearSandboxData(webapp: WebApp) {
        if (SandboxManager.clearSandboxData(this, webapp.uuid)) {
            showToast(this, getString(R.string.clear_sandbox_data), Toast.LENGTH_SHORT)
        }
        updateClearSandboxButtonVisibility(webapp)
    }

    private fun setupIconButton() {
        val launchIconPicker = {
            try {
                iconPickerLauncher.launch("image/*")
            } catch (e: Exception) {
                showToast(this, getString(R.string.icon_not_found), Toast.LENGTH_SHORT)
                Log.e("WebAppSettings", "Failed to launch icon picker", e)
            }
        }
        binding.iconContainer.setOnClickListener { launchIconPicker() }
        binding.btnEditIcon.setOnClickListener { launchIconPicker() }
    }

    private fun handleSelectedIcon(uri: Uri) {
        try {
            @Suppress("DEPRECATION")
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
            if (bitmap != null) {
                customIconBitmap = bitmap
                binding.imgWebAppIcon.setImageBitmap(bitmap)
                binding.imgWebAppIconPlaceholder.visibility = View.GONE
                binding.webapp?.let { modifiedWebapp -> saveIconToFile(modifiedWebapp, bitmap) }
            }
        } catch (e: IOException) {
            showToast(this, getString(R.string.icon_not_found), Toast.LENGTH_SHORT)
            Log.e("WebAppSettings", "Failed to load icon from URI", e)
        }
    }

    private fun loadCurrentIcon(modifiedWebapp: WebApp) {
        if (modifiedWebapp.hasCustomIcon) {
            try {
                val bitmap = BitmapFactory.decodeFile(modifiedWebapp.iconFile.absolutePath)
                if (bitmap != null) {
                    binding.imgWebAppIcon.setImageBitmap(bitmap)
                    binding.imgWebAppIconPlaceholder.visibility = View.GONE
                    customIconBitmap = bitmap
                }
            } catch (e: Exception) {
                Log.w("WebAppSettings", "Failed to load custom icon", e)
            }
        }
    }

    private fun saveIconToFile(webApp: WebApp, bitmap: Bitmap) {
        try {
            val iconFile = webApp.iconFile
            iconFile.parentFile?.mkdirs()
            FileOutputStream(iconFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } catch (e: Exception) {
            Log.w("WebAppSettings", "Failed to save icon for webapp ${webApp.uuid}", e)
        }
    }

    private fun setupFetchButton(modifiedWebapp: WebApp) {
        binding.btnFetch.setOnClickListener {
            if (!isFetchingIcon) {
                fetchIconAndName(modifiedWebapp)
            }
        }
    }

    private fun fetchIconAndName(modifiedWebapp: WebApp) {
        if (isFetchingIcon) return

        val urlToFetch = binding.textBaseUrl.text.toString().trim()
        if (urlToFetch.isEmpty()) {
            showToast(this, getString(R.string.enter_valid_url), Toast.LENGTH_SHORT)
            return
        }

        isFetchingIcon = true
        val rotateAnimation = AnimationUtils.loadAnimation(this, R.anim.rotate_indefinitely)
        binding.btnFetch.startAnimation(rotateAnimation)

        executorService?.submit {
            try {
                val webappData = fetchWebappDataFromUrl(urlToFetch)
                val fetchedTitle = webappData["title"]
                val fetchedIconUrl = webappData["iconUrl"]
                val fetchedIcon =
                    if (fetchedIconUrl != null) {
                        loadBitmapFromUrl(fetchedIconUrl)
                    } else null

                mainHandler.post {
                    isFetchingIcon = false
                    binding.btnFetch.clearAnimation()

                    if (fetchedTitle != null && fetchedTitle.isNotEmpty()) {
                        binding.txtWebAppName.setText(fetchedTitle)
                        modifiedWebapp.title = fetchedTitle
                    }

                    if (fetchedIcon != null) {
                        customIconBitmap = fetchedIcon
                        binding.imgWebAppIcon.setImageBitmap(fetchedIcon)
                        binding.imgWebAppIconPlaceholder.visibility = View.GONE
                        saveIconToFile(modifiedWebapp, fetchedIcon)
                    }

                    if (fetchedTitle == null && fetchedIcon == null) {
                        showToast(this, getString(R.string.fetch_failed), Toast.LENGTH_SHORT)
                    }
                }
            } catch (e: Exception) {
                Log.e("WebAppSettings", "Failed to fetch data", e)
                mainHandler.post {
                    isFetchingIcon = false
                    binding.btnFetch.clearAnimation()
                    showToast(this, getString(R.string.fetch_failed), Toast.LENGTH_SHORT)
                }
            }
        }
    }

    private fun httpGet(
        url: String,
        timeout: Int = 5000,
        ignoreContentType: Boolean = false,
    ): org.jsoup.Connection.Response? {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url)

        return try {
            val connection =
                Jsoup.connect(url)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(ignoreContentType)
                    .userAgent(Const.DESKTOP_USER_AGENT)
                    .followRedirects(true)
                    .timeout(timeout)

            if (cookies != null) {
                connection.header("Cookie", cookies)
            }

            val response = connection.execute()
            response.headers("Set-Cookie").forEach { cookie ->
                cookieManager.setCookie(url, cookie)
            }
            cookieManager.flush()
            response
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchWebappDataFromUrl(baseUrl: String): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        val foundIcons = TreeMap<Int, String>()

        try {
            val response = httpGet(baseUrl) ?: return result
            val doc = response.parse()

            val htmlTitle = doc.select("title")
            val titleElement = htmlTitle.first()
            if (titleElement != null) {
                result["title"] = titleElement.text()
            }

            var manifestUrl: String? = null
            val manifestLink = doc.select("link[rel=manifest]").first()
            if (manifestLink != null) {
                manifestUrl = manifestLink.absUrl("href")
            }

            if (manifestUrl.isNullOrEmpty()) {
                val baseUrlObj = URL(baseUrl)
                val baseHost = "${baseUrlObj.protocol}://${baseUrlObj.host}"
                val manifestPaths =
                    listOf("/manifest.json", "/manifest.webmanifest", "/site.webmanifest")
                for (path in manifestPaths) {
                    val testUrl = "$baseHost$path"
                    val testResponse = httpGet(testUrl, timeout = 2000, ignoreContentType = true)
                    if (testResponse?.statusCode() == 200) {
                        manifestUrl = testUrl
                        break
                    }
                }
            }

            if (!manifestUrl.isNullOrEmpty()) {
                try {
                    val manifestResponse = httpGet(manifestUrl, ignoreContentType = true)
                    if (manifestResponse != null) {
                        val json = JSONObject(manifestResponse.body())

                        try {
                            result["title"] = json.getString("name")
                        } catch (_: JSONException) {}

                        val manifestIcons = json.optJSONArray("icons")
                        for (i in 0 until (manifestIcons?.length() ?: 0)) {
                            val iconObj = manifestIcons?.optJSONObject(i) ?: continue
                            val iconHref = iconObj.optString("src") ?: continue
                            if (iconHref.endsWith(".svg")) continue
                            var width = getWidthFromIcon(iconObj.optString("sizes", ""))
                            if (iconObj.optString("purpose", "").contains("maskable")) {
                                width += 20000
                            }
                            foundIcons[width] = URL(URL(manifestUrl), iconHref).toString()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("WebAppSettings", "Failed to parse manifest", e)
                }
            }

            if (foundIcons.isEmpty()) {
                val icons = doc.select("link[rel*=icon]")
                val supportedExtensions = listOf(".png", ".jpg", ".jpeg", ".ico", ".webp")

                for (icon in icons) {
                    val iconHref = icon.absUrl("href")
                    if (iconHref.isEmpty()) continue
                    val lowerHref = iconHref.lowercase()
                    if (supportedExtensions.none { lowerHref.endsWith(it) }) continue
                    val rel = icon.attr("rel").lowercase()
                    val sizes = icon.attr("sizes")
                    var width = if (sizes.isNotEmpty()) getWidthFromIcon(sizes) else 1
                    if (rel.contains("apple-touch-icon")) {
                        width += 10000
                    }
                    foundIcons[width] = iconHref
                }
            }

            if (foundIcons.isEmpty()) {
                val baseUrlObj = URL(baseUrl)
                val baseHost = "${baseUrlObj.protocol}://${baseUrlObj.host}"
                val fallbackPaths =
                    listOf(
                        "/favicon.ico",
                        "/favicon.png",
                        "/assets/img/favicon.png",
                        "/assets/favicon.png",
                        "/static/favicon.png",
                        "/images/favicon.png",
                    )
                for (path in fallbackPaths) {
                    val testBitmap = loadBitmapFromUrl("$baseHost$path")
                    if (testBitmap != null) {
                        foundIcons[0] = "$baseHost$path"
                        break
                    }
                }
            }

            if (foundIcons.isNotEmpty()) {
                result["iconUrl"] = foundIcons.lastEntry()?.value
            }
        } catch (e: Exception) {
            Log.e("WebAppSettings", "Failed to fetch webapp data", e)
        }

        return result
    }

    private fun loadBitmapFromUrl(strUrl: String): Bitmap? {
        return try {
            val response = httpGet(strUrl, ignoreContentType = true) ?: return null
            val bytes = response.bodyAsBytes()
            val options =
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (bitmap == null || bitmap.width < Const.FAVICON_MIN_WIDTH) null else bitmap
        } catch (e: Exception) {
            Log.e("WebAppSettings", "Failed to load bitmap from URL", e)
            null
        }
    }

    private fun setupOverridePicker(modifiedWebapp: WebApp) {
        updateOverridesList(modifiedWebapp)

        binding.btnAddOverride.setOnClickListener { showOverridePickerDialog(modifiedWebapp) }
    }

    private fun updateOverridesList(modifiedWebapp: WebApp) {
        val allOverriddenKeys = modifiedWebapp.settings.getOverriddenKeys()
        val allSettings = SettingRegistry.getAllSettings()
        val secondaryKeys = allSettings.mapNotNull { it.secondaryKey }.toSet()
        val tertiaryKeys = allSettings.mapNotNull { it.tertiaryKey }.toSet()
        val compoundKeys = secondaryKeys + tertiaryKeys
        val overriddenKeys = allOverriddenKeys.filter { it !in compoundKeys }

        renderOverrides(modifiedWebapp, overriddenKeys)
    }

    private fun renderOverrides(modifiedWebapp: WebApp, overriddenKeys: List<String>) {
        val container = binding.linearLayoutOverrides
        container.removeAllViews()

        overriddenKeys.forEach { key ->
            val setting = SettingRegistry.getSettingByKey(key) ?: return@forEach
            createOverrideView(setting, modifiedWebapp)?.let { container.addView(it) }
        }
    }

    private fun createOverrideView(
        setting: wtf.mazy.peel.model.SettingDefinition,
        webapp: WebApp,
    ): View? {
        val container = binding.linearLayoutOverrides
        val inflater = LayoutInflater.from(this)

        return when (setting.type) {
            wtf.mazy.peel.model.SettingType.BOOLEAN -> {
                inflater.inflate(R.layout.item_setting_boolean, container, false).apply {
                    setupBooleanOverride(this, setting, webapp)
                }
            }

            wtf.mazy.peel.model.SettingType.BOOLEAN_WITH_INT -> {
                inflater.inflate(R.layout.item_setting_boolean_int, container, false).apply {
                    setupBooleanWithIntOverride(this, setting, webapp)
                }
            }

            wtf.mazy.peel.model.SettingType.TIME_RANGE -> {
                inflater.inflate(R.layout.item_setting_time_range, container, false).apply {
                    setupTimeRangeOverride(this, setting, webapp)
                }
            }

            wtf.mazy.peel.model.SettingType.STRING_MAP -> {
                inflater.inflate(R.layout.item_setting_header_map, container, false).apply {
                    setupHeaderMapOverride(this, setting, webapp)
                }
            }
        }
    }

    private fun addOverrideView(
        setting: wtf.mazy.peel.model.SettingDefinition,
        webapp: WebApp,
    ) {
        createOverrideView(setting, webapp)?.let { binding.linearLayoutOverrides.addView(it) }
    }

    private fun removeOverride(webapp: WebApp, key: String) {
        val setting = SettingRegistry.getSettingByKey(key)
        webapp.settings.setValue(key, null)
        setting?.secondaryKey?.let { webapp.settings.setValue(it, null) }
        setting?.tertiaryKey?.let { webapp.settings.setValue(it, null) }
    }

    private fun setupBooleanOverride(
        view: View,
        setting: wtf.mazy.peel.model.SettingDefinition,
        webapp: WebApp,
    ) {
        val textName = view.findViewById<android.widget.TextView>(R.id.textSettingName)
        val switch =
            view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchSetting)
        val btnRemove = view.findViewById<android.widget.ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<android.widget.ImageButton>(R.id.btnUndo)

        btnUndo?.visibility = View.GONE
        textName.text = setting.displayName
        switch.isChecked = webapp.settings.getValue(setting.key) as? Boolean ?: false
        switch.setOnCheckedChangeListener { _, isChecked ->
            webapp.settings.setValue(setting.key, isChecked)
        }

        btnRemove.setOnClickListener {
            removeOverride(webapp, setting.key)
            updateOverridesList(webapp)
        }
    }

    private fun setupBooleanWithIntOverride(
        view: View,
        setting: wtf.mazy.peel.model.SettingDefinition,
        webapp: WebApp,
    ) {
        val textName = view.findViewById<android.widget.TextView>(R.id.textSettingName)
        val switch =
            view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchSetting)
        val btnRemove = view.findViewById<android.widget.ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<android.widget.ImageButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutNumberInput)
        val editText =
            view.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.editTextNumber)

        btnUndo?.visibility = View.GONE
        textName.text = setting.displayName
        val boolValue = webapp.settings.getValue(setting.key) as? Boolean ?: false
        val intValue =
            if (setting.secondaryKey != null) webapp.settings.getValue(setting.secondaryKey) as? Int
            else null

        switch.isChecked = boolValue
        editText.setText(intValue?.toString() ?: "")
        layout.visibility = if (boolValue) View.VISIBLE else View.GONE

        switch.setOnCheckedChangeListener { _, isChecked ->
            webapp.settings.setValue(setting.key, isChecked)
            layout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        editText.addTextChangedListener(
            object : android.text.TextWatcher {
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
                    }
                }
            })

        btnRemove.setOnClickListener {
            removeOverride(webapp, setting.key)
            updateOverridesList(webapp)
        }
    }

    private fun setupTimeRangeOverride(
        view: View,
        setting: wtf.mazy.peel.model.SettingDefinition,
        webapp: WebApp,
    ) {
        val textName = view.findViewById<android.widget.TextView>(R.id.textSettingName)
        val switch =
            view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchSetting)
        val btnRemove = view.findViewById<android.widget.ImageButton>(R.id.btnRemoveOverride)
        val btnUndo = view.findViewById<android.widget.ImageButton>(R.id.btnUndo)
        val layout = view.findViewById<View>(R.id.layoutTimeRange)
        val btnStart = view.findViewById<android.widget.Button>(R.id.btnTimeStart)
        val btnEnd = view.findViewById<android.widget.Button>(R.id.btnTimeEnd)

        btnUndo?.visibility = View.GONE
        textName.text = setting.displayName
        val boolValue = webapp.settings.getValue(setting.key) as? Boolean ?: false
        val startTime =
            if (setting.secondaryKey != null)
                webapp.settings.getValue(setting.secondaryKey) as? String
            else null
        val endTime =
            if (setting.tertiaryKey != null)
                webapp.settings.getValue(setting.tertiaryKey) as? String
            else null

        switch.isChecked = boolValue
        btnStart.text = startTime ?: "00:00"
        btnEnd.text = endTime ?: "00:00"
        layout.visibility = if (boolValue) View.VISIBLE else View.GONE

        switch.setOnCheckedChangeListener { _, isChecked ->
            webapp.settings.setValue(setting.key, isChecked)
            layout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnStart.setOnClickListener {
            val parts = btnStart.text.toString().split(":")
            android.app
                .TimePickerDialog(
                    this,
                    { _, h, m ->
                        val time = String.format(java.util.Locale.ROOT, "%02d:%02d", h, m)
                        btnStart.text = time
                        setting.secondaryKey?.let { webapp.settings.setValue(it, time) }
                    },
                    parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    parts.getOrNull(1)?.toIntOrNull() ?: 0,
                    true,
                )
                .show()
        }

        btnEnd.setOnClickListener {
            val parts = btnEnd.text.toString().split(":")
            android.app
                .TimePickerDialog(
                    this,
                    { _, h, m ->
                        val time = String.format(java.util.Locale.ROOT, "%02d:%02d", h, m)
                        btnEnd.text = time
                        setting.tertiaryKey?.let { webapp.settings.setValue(it, time) }
                    },
                    parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    parts.getOrNull(1)?.toIntOrNull() ?: 0,
                    true,
                )
                .show()
        }

        btnRemove.setOnClickListener {
            removeOverride(webapp, setting.key)
            updateOverridesList(webapp)
        }
    }

    private fun setupHeaderMapOverride(
        view: View,
        setting: wtf.mazy.peel.model.SettingDefinition,
        webapp: WebApp,
    ) {
        val textName = view.findViewById<android.widget.TextView>(R.id.textSettingName)
        val btnAdd = view.findViewById<android.widget.ImageButton>(R.id.btnAddHeader)
        val btnRemove = view.findViewById<android.widget.ImageButton>(R.id.btnRemoveOverride)
        val container = view.findViewById<android.widget.LinearLayout>(R.id.containerHeaders)

        textName.text = setting.displayName
        btnRemove.visibility = android.view.View.VISIBLE

        if (webapp.settings.customHeaders == null) {
            webapp.settings.customHeaders = mutableMapOf()
        }

        fun refreshHeaders() {
            container.removeAllViews()
            webapp.settings.customHeaders?.forEach { (key, value) ->
                addHeaderEntryView(container, webapp, key, value)
            }
        }

        refreshHeaders()

        btnAdd.setOnClickListener {
            webapp.settings.customHeaders?.put("", "")
            addHeaderEntryView(container, webapp, "", "")
        }

        btnRemove.setOnClickListener {
            webapp.settings.customHeaders = null
            updateOverridesList(webapp)
        }
    }

    private fun addHeaderEntryView(
        container: android.widget.LinearLayout,
        webapp: WebApp,
        initialKey: String,
        initialValue: String,
    ) {
        val entryView =
            LayoutInflater.from(this).inflate(R.layout.item_header_entry, container, false)
        val editName =
            entryView.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.editHeaderName)
        val editValue =
            entryView.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.editHeaderValue)
        val btnRemoveHeader =
            entryView.findViewById<android.widget.ImageButton>(R.id.btnRemoveHeader)

        editName.setText(initialKey)
        editValue.setText(initialValue)

        var currentKey = initialKey

        editName.addTextChangedListener(
            object : android.text.TextWatcher {
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
            object : android.text.TextWatcher {
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

    override fun onSettingSelected(setting: wtf.mazy.peel.model.SettingDefinition) {
        val webapp = modifiedWebapp ?: return
        if (setting.type == wtf.mazy.peel.model.SettingType.STRING_MAP) {
            webapp.settings.customHeaders = mutableMapOf()
        } else {
            val defaultValue =
                DataManager.instance.defaultSettings.settings.getValue(setting.key)
            webapp.settings.setValue(setting.key, defaultValue)
        }
        addOverrideView(setting, webapp)
    }

    private fun showOverridePickerDialog(modifiedWebapp: WebApp) {
        val dialog =
            OverridePickerDialog.newInstance(
                modifiedWebapp.settings,
                DataManager.instance.defaultSettings.settings,
                this,
            )
        dialog.show(supportFragmentManager, "OverridePickerDialog")
    }
}
