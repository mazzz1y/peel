package wtf.mazy.peel.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.Toast
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.dialog.ImportDialogHelper
import wtf.mazy.peel.ui.dialog.showSandboxInputDialog
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.webapplist.GroupPagerAdapter
import wtf.mazy.peel.ui.webapplist.SelectionModeHost
import wtf.mazy.peel.ui.webapplist.WebAppListFragment
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils

class MainActivity : AppCompatActivity(), SelectionModeHost {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var fab: FloatingActionButton
    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null
    private var pagerAdapter: GroupPagerAdapter? = null
    private var lastGroupKeys: List<Pair<String, String>> = emptyList()
    private var lastShowUngrouped: Boolean = true

    private val selectedUuids = mutableSetOf<String>()
    private var _selectionMode = false
    private val pendingExitRunnable = Runnable { exitSelectionMode() }
    private lateinit var exportLoader: LoadingDialogController

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)) { uri ->
            uri?.let { performFullBackupExport(it) }
        }

    private val importDialogHelper = ImportDialogHelper(this) { refreshCurrentPages() }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importDialogHelper.showForUri(it) }
        }

    private val backPressCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectionMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        DataManager.instance.loadAppData()

        toolbar = findViewById(R.id.toolbar)
        fab = findViewById(R.id.fab)
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        exportLoader = LoadingDialogController(this)

        toolbar.setTitle(R.string.app_name)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { onFabClicked() }
        onBackPressedDispatcher.addCallback(this, backPressCallback)

        setupViewPager()
        handleIncomingBackupIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        DataManager.instance.loadAppData()
        refreshCurrentPages()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Const.INTENT_BACKUP_RESTORED, false)) {
            setupViewPager()
            intent.putExtra(Const.INTENT_BACKUP_RESTORED, false)
            intent.putExtra(Const.INTENT_REFRESH_NEW_THEME, false)
        }
        if (intent.getBooleanExtra(Const.INTENT_WEBAPP_CHANGED, false)) {
            refreshCurrentPages()
            intent.putExtra(Const.INTENT_WEBAPP_CHANGED, false)
        }
        handleIncomingBackupIntent(intent)
    }

    override fun onDestroy() {
        importDialogHelper.onHostDestroy()
        exportLoader.dismiss()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_groups -> {
                startActivity(Intent(this, GroupListActivity::class.java))
                true
            }

            R.id.action_global_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            R.id.action_import -> {
                try {
                    importLauncher.launch("*/*")
                } catch (e: ActivityNotFoundException) {
                    NotificationUtils.showToast(
                        this,
                        getString(R.string.no_filemanager),
                        Toast.LENGTH_LONG,
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
                        Toast.LENGTH_LONG,
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

    fun refreshCurrentPages() {
        val groups = DataManager.instance.sortedGroups
        val newGroupKeys = groups.map { it.uuid to it.title }
        val newShowUngrouped =
            if (groups.isEmpty()) true
            else DataManager.instance.activeWebsitesForGroup(null).isNotEmpty()

        if (lastGroupKeys != newGroupKeys || lastShowUngrouped != newShowUngrouped) {
            setupViewPager()
            return
        }

        for (i in 0 until (pagerAdapter?.itemCount ?: 0)) {
            val fragment = supportFragmentManager.findFragmentByTag("f$i")
            (fragment as? WebAppListFragment)?.updateWebAppList()
        }
    }

    private fun forEachFragment(action: (WebAppListFragment) -> Unit) {
        for (i in 0 until (pagerAdapter?.itemCount ?: 0)) {
            val fragment = supportFragmentManager.findFragmentByTag("f$i")
            (fragment as? WebAppListFragment)?.let(action)
        }
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

    override val isInSelectionMode: Boolean get() = _selectionMode

    override fun isSelected(uuid: String) = uuid in selectedUuids

    override fun enterSelectionMode(uuid: String) {
        fab.removeCallbacks(pendingExitRunnable)
        if (_selectionMode) {
            toggleSelection(uuid)
            return
        }
        _selectionMode = true
        selectedUuids.clear()
        selectedUuids.add(uuid)
        backPressCallback.isEnabled = true

        applySelectionToolbar()
        animateFabSwap(R.drawable.ic_baseline_share_24)
        forEachFragment { it.animateEnterSelection(uuid) }
    }

    override fun toggleSelection(uuid: String) {
        if (uuid in selectedUuids) selectedUuids.remove(uuid) else selectedUuids.add(uuid)
        if (selectedUuids.isEmpty()) {
            forEachFragment { it.animateSelectionToggled(uuid) }
            fab.postDelayed(pendingExitRunnable, EXIT_DELAY_MS)
            return
        }
        toolbar.title = getString(R.string.n_apps_selected, selectedUuids.size)
        forEachFragment { it.animateSelectionToggled(uuid) }
    }

    private fun exitSelectionMode() {
        fab.removeCallbacks(pendingExitRunnable)
        val previouslySelected = selectedUuids.toSet()
        _selectionMode = false
        selectedUuids.clear()
        backPressCallback.isEnabled = false

        applyNormalToolbar()
        animateFabSwap(R.drawable.ic_add_24dp)
        forEachFragment { it.animateExitSelection(previouslySelected) }
    }

    private fun applySelectionToolbar() {
        crossfadeToolbar {
            toolbar.menu.clear()
            menuInflater.inflate(R.menu.menu_selection, toolbar.menu)
            toolbar.menu.findItem(R.id.action_move_selected)?.isVisible =
                DataManager.instance.sortedGroups.size > 1
            toolbar.setOnMenuItemClickListener { onSelectionMenuItemClicked(it) }
            toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener { exitSelectionMode() }
            toolbar.title = getString(R.string.n_apps_selected, selectedUuids.size)
        }
    }

    private fun applyNormalToolbar() {
        crossfadeToolbar {
            toolbar.menu.clear()
            menuInflater.inflate(R.menu.menu_main, toolbar.menu)
            toolbar.setOnMenuItemClickListener { onOptionsItemSelected(it) }
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
            toolbar.setTitle(R.string.app_name)
        }
    }

    private fun crossfadeToolbar(swap: () -> Unit) {
        toolbar.animate().cancel()
        toolbar.animate()
            .alpha(0f)
            .setDuration(TOOLBAR_FADE_DURATION)
            .withEndAction {
                swap()
                toolbar.animate()
                    .alpha(1f)
                    .setDuration(TOOLBAR_FADE_DURATION)
                    .start()
            }
            .start()
    }

    private fun animateFabSwap(iconRes: Int) {
        fab.hide(object : FloatingActionButton.OnVisibilityChangedListener() {
            override fun onHidden(fab: FloatingActionButton) {
                fab.setImageResource(iconRes)
                fab.show()
            }
        })
    }

    private fun onFabClicked() {
        if (_selectionMode) {
            performShareSelected()
        } else {
            buildAddWebsiteDialog()
        }
    }

    private fun onSelectionMenuItemClicked(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_move_selected -> {
                showMoveSelectedPopup()
                true
            }
            R.id.action_delete_selected -> {
                confirmDeleteSelected()
                true
            }
            else -> false
        }
    }

    private fun performShareSelected() {
        if (selectedUuids.isEmpty()) {
            NotificationUtils.showToast(
                this,
                getString(R.string.share_no_selection),
                Toast.LENGTH_SHORT,
            )
            return
        }

        val webApps = resolveSelectedWebApps()
        if (webApps.isEmpty()) {
            NotificationUtils.showToast(
                this,
                getString(R.string.share_no_selection),
                Toast.LENGTH_SHORT,
            )
            return
        }

        if (containsSecrets(webApps)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_secrets_title)
                .setMessage(R.string.share_secrets_description)
                .setPositiveButton(R.string.share_secrets_exclude) { _, _ ->
                    exitSelectionMode()
                    shareSelectedApps(webApps, includeSecrets = false)
                }
                .setNegativeButton(R.string.share_secrets_include) { _, _ ->
                    exitSelectionMode()
                    shareSelectedApps(webApps, includeSecrets = true)
                }
                .show()
        } else {
            exitSelectionMode()
            shareSelectedApps(webApps, includeSecrets = true)
        }
    }

    private fun containsSecrets(webApps: List<WebApp>): Boolean {
        return webApps.any { app ->
            val settings = app.settings
            settings.customHeaders?.any { (k, v) -> k.isNotBlank() || v.isNotBlank() } == true
                || !settings.basicAuthUsername.isNullOrBlank()
                || !settings.basicAuthPassword.isNullOrBlank()
        }
    }

    private fun showMoveSelectedPopup() {
        if (selectedUuids.isEmpty()) return

        val anchor = toolbar.findViewById<View>(R.id.action_move_selected) ?: toolbar
        val popup = PopupMenu(this, anchor)

        val groups = DataManager.instance.sortedGroups
        groups.forEachIndexed { index, group ->
            popup.menu.add(0, MENU_MOVE_GROUP_BASE + index, index, group.title)
        }
        popup.menu.add(
            0,
            MENU_MOVE_GROUP_BASE + groups.size,
            groups.size,
            getString(R.string.none),
        )

        popup.setOnMenuItemClickListener { menuItem ->
            val groupIndex = menuItem.itemId - MENU_MOVE_GROUP_BASE
            if (groupIndex in 0..groups.size) {
                val targetGroupUuid =
                    if (groupIndex < groups.size) groups[groupIndex].uuid else null
                performMoveSelected(targetGroupUuid)
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun performMoveSelected(targetGroupUuid: String?) {
        val webApps = resolveSelectedWebApps()
        webApps.forEach { webapp ->
            webapp.groupUuid = targetGroupUuid
            DataManager.instance.replaceWebApp(webapp)
        }
        exitSelectionMode()
        refreshCurrentPages()
    }

    private fun confirmDeleteSelected() {
        if (selectedUuids.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_apps_title)
            .setMessage(getString(R.string.remove_apps_confirm, selectedUuids.size))
            .setPositiveButton(R.string.delete) { _, _ -> performDeleteSelected() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performDeleteSelected() {
        val webApps = resolveSelectedWebApps()
        val count = webApps.size

        webApps.forEach { it.markInactiveOnly() }
        exitSelectionMode()
        refreshCurrentPages()

        NotificationUtils.showUndoSnackBar(
            activity = this,
            message = getString(R.string.n_apps_removed, count),
            onUndo = {
                webApps.forEach { it.isActiveEntry = true }
                refreshCurrentPages()
            },
            onCommit = {
                webApps.forEach { webapp ->
                    webapp.deleteShortcuts(this)
                    webapp.cleanupWebAppData(this)
                    DataManager.instance.removeWebApp(webapp)
                }
            },
        )
    }

    private fun resolveSelectedWebApps(): List<WebApp> {
        val byUuid = DataManager.instance.getWebsites().associateBy { it.uuid }
        return selectedUuids.mapNotNull(byUuid::get)
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
                @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        }
    }

    private fun performFullBackupExport(uri: Uri) {
        runWithLoader(
            DataManager.instance.getWebsites().size,
            ioTask = { BackupManager.exportFullBackup(uri) },
        ) { success ->
            NotificationUtils.showToast(
                this,
                getString(if (success) R.string.backup_saved else R.string.backup_save_failed),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private fun shareSelectedApps(webApps: List<WebApp>, includeSecrets: Boolean) {
        runWithLoader(
            webApps.size,
            ioTask = { BackupManager.buildShareFile(webApps, includeSecrets) },
        ) { file ->
            if (file == null || !BackupManager.launchShareChooser(this, file)) {
                NotificationUtils.showToast(
                    this,
                    getString(R.string.export_share_failed),
                    Toast.LENGTH_LONG,
                )
            }
        }
    }

    private fun <T> runWithLoader(
        elementCount: Int,
        ioTask: () -> T,
        onResult: (T) -> Unit,
    ) {
        val showLoader = elementCount >= BackupManager.LOADER_THRESHOLD
        if (showLoader) exportLoader.show(R.string.preparing_export)
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { ioTask() }
            } finally {
                if (showLoader) exportLoader.dismiss()
            }
            onResult(result)
        }
    }

    private fun buildAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.gnu_license))
            .setPositiveButton(R.string.ok, null)
            .show()
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
        SandboxManager.clearNonSandboxData(this)
        if (includeSandbox) {
            SandboxManager.clearAllSandboxData(this)
        }
    }

    private fun performFactoryReset() {
        SandboxManager.clearNonSandboxData(this)
        SandboxManager.clearAllSandboxData(this)

        DataManager.instance.getWebsites().toList().forEach { webapp ->
            webapp.cleanupWebAppData(this)
            DataManager.instance.removeWebApp(webapp)
        }
        DataManager.instance.getGroups().toList().forEach { group ->
            SandboxManager.wipeSandboxStorage(group.uuid)
            DataManager.instance.removeGroup(group, ungroupApps = false)
        }

        DataManager.instance.defaultSettings =
            DataManager.instance.defaultSettings.also {
                it.settings = wtf.mazy.peel.model.WebAppSettings.createWithDefaults()
            }

        setupViewPager()
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

            DataManager.instance.addWebsite(newSite)
            refreshCurrentPages()

            val settingsIntent = Intent(this, WebAppSettingsActivity::class.java)
            settingsIntent.putExtra(Const.INTENT_WEBAPP_UUID, newSite.uuid)
            settingsIntent.putExtra(Const.INTENT_AUTO_FETCH, true)
            startActivity(settingsIntent)
        }
    }

    companion object {
        private const val MENU_MOVE_GROUP_BASE = 20000
        private const val EXIT_DELAY_MS = 200L
        private const val TOOLBAR_FADE_DURATION = 100L
    }
}
