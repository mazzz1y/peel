package wtf.mazy.peel.activities

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ReplacementSpan
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import wtf.mazy.peel.BuildConfig
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.ApplyTiming
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.runWithLoader
import wtf.mazy.peel.ui.dialog.ImportDialogHelper
import wtf.mazy.peel.ui.dialog.showSandboxInputDialog
import wtf.mazy.peel.ui.toolbar.SearchModeController
import wtf.mazy.peel.ui.toolbar.SelectionModeController
import wtf.mazy.peel.ui.toolbar.ToolbarModeHost
import wtf.mazy.peel.ui.webapplist.GroupPagerAdapter
import wtf.mazy.peel.ui.webapplist.SelectionModeHost
import wtf.mazy.peel.ui.webapplist.WebAppListFragment
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.restartApp

class MainActivity :
    AppCompatActivity(),
    ToolbarModeHost,
    SelectionModeHost {

    override lateinit var toolbar: MaterialToolbar
    override lateinit var fab: FloatingActionButton
    override var tabLayout: TabLayout? = null
    override var viewPager: ViewPager2? = null
    override val hostActivity: AppCompatActivity get() = this

    private var pagerAdapter: GroupPagerAdapter? = null
    private var lastGroupKeys: List<Pair<String, String>> = emptyList()
    private var lastShowUngrouped: Boolean = true
    private lateinit var exportLoader: LoadingDialogController

    private lateinit var searchController: SearchModeController
    private lateinit var selectionController: SelectionModeController

    private val fragmentRegistry = mutableMapOf<String?, WebAppListFragment>()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)) { uri ->
            uri?.let { performFullBackupExport(it) }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val importDialogHelper = ImportDialogHelper(this)

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importDialogHelper.showForUri(it) }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleSettingsResult(result.data)
        }

    private val backPressCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                searchController.isActive -> searchController.exit()
                selectionController.isActive -> selectionController.exit()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        fab = findViewById(R.id.fab)
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        exportLoader = LoadingDialogController(this)

        findViewById<View>(android.R.id.content).addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) fab.requestLayout()
        }

        searchController = SearchModeController(this)
        selectionController = SelectionModeController(this) { searchController.isActive }
        searchController.searchResultsList = findViewById(R.id.searchResultsList)
        searchController.searchEmptyState = findViewById(R.id.searchEmptyState)

        toolbar.setTitle(R.string.app_name)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { onFabClicked() }
        onBackPressedDispatcher.addCallback(this, backPressCallback)

        setupViewPager()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataManager.instance.state.collect {
                    if (searchController.isActive) {
                        searchController.onDataChanged()
                    } else {
                        refreshCurrentPages()
                    }
                }
            }
        }
        handleIncomingBackupIntent(intent)
        requestNotificationPermission()
        GeckoRuntimeProvider.initAsync(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("peel_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("notification_permission_asked", false)) return
        prefs.edit().putBoolean("notification_permission_asked", true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Const.INTENT_BACKUP_RESTORED, false)) {
            setupViewPager()
            intent.putExtra(Const.INTENT_BACKUP_RESTORED, false)
        }
        handleIncomingBackupIntent(intent)
    }

    override fun onDestroy() {
        importDialogHelper.onHostDestroy()
        exportLoader.dismiss()
        super.onDestroy()
    }

    fun launchSettings(intent: Intent) {
        settingsLauncher.launch(intent)
    }

    private fun handleSettingsResult(data: Intent?) {
        val timingName = data?.getStringExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING) ?: return
        val timing = ApplyTiming.valueOf(timingName)
        ApplyTimingRegistry.showSnackbarForTiming(
            timing,
            findViewById(android.R.id.content),
        ) { restartApp(this) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                searchController.enter()
                true
            }

            R.id.action_groups -> {
                startActivity(Intent(this, GroupListActivity::class.java))
                true
            }

            R.id.action_extensions -> {
                startActivity(Intent(this, ExtensionsActivity::class.java))
                true
            }

            R.id.action_global_settings -> {
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                true
            }

            R.id.action_import -> {
                try {
                    importLauncher.launch("*/*")
                } catch (e: ActivityNotFoundException) {
                    NotificationUtils.showToast(
                        this,
                        getString(R.string.no_filemanager),
                        Toast.LENGTH_LONG
                    )
                    Log.e("MainActivity", "No file manager available for import", e)
                }
                true
            }

            R.id.action_export -> {
                try {
                    exportLauncher.launch(BackupManager.buildExportFilename())
                } catch (e: ActivityNotFoundException) {
                    NotificationUtils.showToast(
                        this,
                        getString(R.string.no_filemanager),
                        Toast.LENGTH_LONG
                    )
                    Log.e("MainActivity", "No file manager available for export", e)
                }
                true
            }

            R.id.action_about -> {
                buildAboutDialog()
                true
            }

            R.id.action_clear_data -> {
                showClearDataConfirmDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun refreshCurrentPages() {
        val groups = DataManager.instance.sortedGroups
        val newGroupKeys = groups.map { it.uuid to it.title }
        val newShowUngrouped =
            if (groups.isEmpty()) true
            else DataManager.instance.activeWebsitesForGroup(null).isNotEmpty()

        if (lastGroupKeys != newGroupKeys || lastShowUngrouped != newShowUngrouped) {
            setupViewPager()
            return
        }

        fragmentRegistry.values.forEach { it.updateWebAppList() }
    }

    fun registerFragment(groupFilter: String?, fragment: WebAppListFragment) {
        fragmentRegistry[groupFilter] = fragment
    }

    fun unregisterFragment(groupFilter: String?) {
        fragmentRegistry.remove(groupFilter)
    }

    private fun forEachFragment(action: (WebAppListFragment) -> Unit) {
        fragmentRegistry.values.forEach(action)
    }

    private fun setupViewPager() {
        val groups = DataManager.instance.sortedGroups

        if (groups.isEmpty()) {
            tabLayout?.visibility = View.GONE
            pagerAdapter = GroupPagerAdapter(this, emptyList(), showUngrouped = true)
            viewPager?.adapter = pagerAdapter
            viewPager?.isUserInputEnabled = false
            lastGroupKeys = emptyList()
            lastShowUngrouped = true
        } else {
            val hasUngrouped = DataManager.instance.activeWebsitesForGroup(null).isNotEmpty()
            tabLayout?.visibility = View.VISIBLE
            pagerAdapter = GroupPagerAdapter(this, groups, showUngrouped = hasUngrouped)
            viewPager?.adapter = pagerAdapter
            viewPager?.isUserInputEnabled = true
            lastGroupKeys = groups.map { it.uuid to it.title }
            lastShowUngrouped = hasUngrouped

            TabLayoutMediator(tabLayout!!, viewPager!!) { tab, position ->
                tab.text = pagerAdapter?.getPageTitle(position)
            }.attach()
        }
    }

    override val isInSelectionMode: Boolean get() = selectionController.isActive

    override fun isSelected(uuid: String) = selectionController.isSelected(uuid)

    override fun enterSelectionMode(uuid: String) = selectionController.enter(uuid)

    override fun toggleSelection(uuid: String) = selectionController.toggle(uuid)

    override fun dispatchSelectionEntered(toggledUuid: String) {
        if (searchController.isActive) {
            searchController.notifySelectionEntered(toggledUuid)
        } else {
            forEachFragment { it.animateEnterSelection(toggledUuid) }
        }
        updateTabSelectionDots()
    }

    override fun dispatchSelectionToggled(uuid: String) {
        if (searchController.isActive) {
            searchController.notifySelectionToggled(uuid)
        } else {
            forEachFragment { it.animateSelectionToggled(uuid) }
        }
        updateTabSelectionDots()
    }

    override fun addPendingDeletes(uuids: Collection<String>) {
        forEachFragment { it.addPendingDeletes(uuids) }
    }

    override fun clearPendingDeletes(uuids: Collection<String>) {
        forEachFragment { it.clearPendingDeletes(uuids) }
    }

    override fun dispatchSelectionExited(previouslySelected: Set<String>) {
        if (searchController.isActive) {
            searchController.notifySelectionExited(previouslySelected)
        } else {
            forEachFragment { it.animateExitSelection(previouslySelected) }
        }
        updateTabSelectionDots()
    }

    private fun updateTabSelectionDots() {
        val tabs = tabLayout ?: return
        val adapter = pagerAdapter ?: return
        val selected = selectionController.selectedIds
        if (selected.isEmpty()) {
            for (i in 0 until tabs.tabCount) {
                tabs.getTabAt(i)?.text = adapter.getPageTitle(i)
            }
            return
        }
        val appsByGroup = DataManager.instance.activeWebsites
            .filter { it.uuid in selected }
            .groupBy { it.groupUuid }
        val badgeBg = MaterialColors.getColor(
            window.decorView, androidx.appcompat.R.attr.colorPrimary, 0,
        )
        val badgeFg = MaterialColors.getColor(
            window.decorView, com.google.android.material.R.attr.colorOnPrimary, 0,
        )
        for (i in 0 until tabs.tabCount) {
            val groupUuid = if (i < adapter.groups.size) adapter.groups[i].uuid else null
            val title = adapter.getPageTitle(i)
            val count = appsByGroup[groupUuid]?.size ?: 0
            tabs.getTabAt(i)?.text = if (count > 0) {
                val badge = " $count"
                SpannableString("$title$badge").apply {
                    setSpan(
                        BadgeSpan(count.toString(), badgeBg, badgeFg),
                        title.length, length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            } else {
                title
            }
        }
    }

    override fun onSearchModeExited() {
        if (selectionController.isActive) {
            selectionController.reapplyToolbar()
            animateFabSwap(R.drawable.ic_baseline_share_24)
            forEachFragment { it.refreshSelectionState() }
            updateTabSelectionDots()
        } else {
            applyNormalToolbar()
            animateFabSwap(R.drawable.ic_add_24dp)
            refreshCurrentPages()
        }
    }

    override fun applyNormalToolbar() {
        crossfadeToolbar {
            removeSearchViewFromToolbar()
            toolbar.menu.clear()
            menuInflater.inflate(R.menu.menu_main, toolbar.menu)
            toolbar.setOnMenuItemClickListener { onOptionsItemSelected(it) }
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
            toolbar.setTitle(R.string.app_name)
        }
    }

    override fun crossfadeToolbar(swap: () -> Unit) {
        toolbar.animate().cancel()
        toolbar.animate()
            .alpha(0f)
            .setDuration(Const.ANIM_DURATION_FAST)
            .withEndAction {
                swap()
                toolbar.animate()
                    .alpha(1f)
                    .setDuration(Const.ANIM_DURATION_FAST)
                    .start()
            }
            .start()
    }

    override fun animateFabSwap(iconRes: Int) {
        if (!fab.isOrWillBeShown) {
            fab.setImageResource(iconRes)
            fab.show()
            return
        }
        fab.hide(object : FloatingActionButton.OnVisibilityChangedListener() {
            override fun onHidden(fab: FloatingActionButton) {
                fab.setImageResource(iconRes)
                fab.show()
            }
        })
    }

    override fun updateBackPressEnabled() {
        backPressCallback.isEnabled = searchController.isActive || selectionController.isActive
    }

    override fun shareApps(webApps: List<WebApp>, includeSecrets: Boolean) {
        runWithLoader(
            activity = this,
            loader = exportLoader,
            showLoader = webApps.size >= BackupManager.LOADER_THRESHOLD,
            loadingRes = R.string.preparing_export,
            ioTask = { BackupManager.buildShareFile(webApps, includeSecrets) },
        ) { file ->
            if (file == null || !BackupManager.launchShareChooser(this, file)) {
                NotificationUtils.showToast(
                    this,
                    getString(R.string.export_share_failed),
                    Toast.LENGTH_LONG
                )
            }
        }
    }

    private fun onFabClicked() {
        if (selectionController.isActive) {
            selectionController.onFabClicked()
        } else {
            buildAddWebsiteDialog()
        }
    }

    private fun handleIncomingBackupIntent(intent: Intent?) {
        val uri = extractBackupUri(intent) ?: return
        intent?.action = null
        intent?.data = null
        intent?.removeExtra(Intent.EXTRA_STREAM)
        importDialogHelper.showForUri(uri)
    }

    private fun extractBackupUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            else -> null
        }
    }

    private fun performFullBackupExport(uri: Uri) {
        runWithLoader(
            activity = this,
            loader = exportLoader,
            showLoader = DataManager.instance.getWebsites().size >= BackupManager.LOADER_THRESHOLD,
            loadingRes = R.string.preparing_export,
            ioTask = { BackupManager.exportFullBackup(uri) },
        ) { success ->
            NotificationUtils.showToast(
                this,
                getString(if (success) R.string.backup_saved else R.string.backup_save_failed),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private fun buildAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)
        view.findViewById<TextView>(R.id.aboutEngine).text =
            getString(R.string.about_engine, BuildConfig.GECKOVIEW_VERSION)
        view.findViewById<View>(R.id.aboutGithub).setOnClickListener {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, GITHUB_URL)
                    },
                    null,
                )
            )
        }
        view.findViewById<View>(R.id.aboutLicense).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LICENSE_URL)))
        }
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .show()
    }

    companion object {
        private const val GITHUB_URL = "https://github.com/mazzz1y/peel"
        private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.txt"
    }

    private fun showClearDataConfirmDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_clear_data, null)
        val switchBrowsing =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchClearBrowsingData
            )
        val switchSandbox =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchClearSandboxData
            )
        val switchFactory =
            dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.switchFactoryReset
            )

        switchBrowsing.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && switchFactory.isChecked) switchFactory.isChecked = false
            switchSandbox.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) switchSandbox.isChecked = false
        }

        switchFactory.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && switchBrowsing.isChecked) switchBrowsing.isChecked = false
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data)
            .setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (switchFactory.isChecked) {
                    performFactoryReset()
                } else if (switchBrowsing.isChecked) {
                    clearBrowsingData(switchSandbox.isChecked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearBrowsingData(includeSandbox: Boolean) {
        BrowserActivity.finishAll()
        SandboxManager.clearNonSandboxData()
        if (includeSandbox) {
            SandboxManager.clearAllSandboxData(this)
        }
    }

    private fun performFactoryReset() {
        BrowserActivity.finishAll()
        SandboxManager.clearNonSandboxData()
        SandboxManager.clearAllSandboxData(this)

        lifecycleScope.launch {
            DataManager.instance.getWebsites().forEach { webapp ->
                DataManager.instance.cleanupAndRemoveWebApp(webapp.uuid, this@MainActivity)
            }
            DataManager.instance.getGroups().forEach { group ->
                DataManager.instance.removeGroup(group, ungroupApps = false)
            }

            DataManager.instance.setDefaultSettings(
                DataManager.instance.defaultSettings.also {
                    it.settings = WebAppSettings.createWithDefaults()
                }
            )
        }
    }

    private fun buildAddWebsiteDialog() {
        showSandboxInputDialog(
            titleRes = R.string.add_webapp,
            hintRes = R.string.url,
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI,
        ) { result ->
            val url = result.text
            val urlWithProtocol =
                if (url.startsWith("https://") || url.startsWith("http://")) url
                else "https://$url"
            val newSite = WebApp(urlWithProtocol)
            newSite.order = DataManager.instance.incrementedOrder
            newSite.isUseContainer = result.sandbox
            newSite.isEphemeralSandbox = result.ephemeral

            val currentPage = viewPager?.currentItem ?: 0
            val groups = DataManager.instance.sortedGroups
            if (groups.isNotEmpty() && currentPage < groups.size) {
                newSite.groupUuid = groups[currentPage].uuid
            }

            lifecycleScope.launch {
                DataManager.instance.addWebsite(newSite)

                val settingsIntent = Intent(this@MainActivity, WebAppSettingsActivity::class.java)
                settingsIntent.putExtra(Const.INTENT_WEBAPP_UUID, newSite.uuid)
                settingsIntent.putExtra(Const.INTENT_AUTO_FETCH, true)
                settingsLauncher.launch(settingsIntent)
            }
        }
    }

    private class BadgeSpan(
        private val label: String,
        private val bgColor: Int,
        private val fgColor: Int,
    ) : ReplacementSpan() {

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val textSize = paint.textSize * 0.7f
            val badgePaint =
                Paint(paint).apply { this.textSize = textSize; typeface = Typeface.DEFAULT_BOLD }
            val textWidth = badgePaint.measureText(label)
            val diameter = maxOf(textWidth + textSize * 0.6f, textSize * 1.3f)
            return (diameter + textSize * 0.4f).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val textSize = paint.textSize * 0.7f
            val badgePaint =
                Paint(paint).apply { this.textSize = textSize; typeface = Typeface.DEFAULT_BOLD }
            val textWidth = badgePaint.measureText(label)
            val diameter = maxOf(textWidth + textSize * 0.6f, textSize * 1.3f)
            val radius = diameter / 2f
            val centerX = x + textSize * 0.4f + radius
            val centerY = (top + bottom) / 2f

            paint.color = bgColor
            canvas.drawCircle(centerX, centerY, radius, paint)

            badgePaint.color = fgColor
            val labelX = centerX - textWidth / 2f
            val labelY = centerY - (badgePaint.descent() + badgePaint.ascent()) / 2f
            canvas.drawText(label, labelX, labelY, badgePaint)
        }
    }
}
