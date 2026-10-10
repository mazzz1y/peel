package wtf.mazy.peel.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.SessionHostRegistry
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.BackupManager
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.effective
import wtf.mazy.peel.ui.common.ListEmptyState
import wtf.mazy.peel.ui.common.LoadingDialogController
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.ui.common.animateReflow
import wtf.mazy.peel.ui.common.runWithLoader
import wtf.mazy.peel.ui.dialog.ImportFlowController
import wtf.mazy.peel.ui.dialog.showSandboxInputDialog
import wtf.mazy.peel.ui.entitylist.EntityListAnimations
import wtf.mazy.peel.ui.entitylist.EntitySelectionController
import wtf.mazy.peel.ui.entitylist.ListChrome
import wtf.mazy.peel.ui.entitylist.SelectionConfig
import wtf.mazy.peel.ui.settings.showApplyTimingSnackbar
import wtf.mazy.peel.ui.webapplist.AppBarAction
import wtf.mazy.peel.ui.webapplist.BottomSearchSurface
import wtf.mazy.peel.ui.webapplist.ContainedSearchSurface
import wtf.mazy.peel.ui.webapplist.GroupPagerAdapter
import wtf.mazy.peel.ui.webapplist.SearchModeController
import wtf.mazy.peel.ui.webapplist.SearchSurface
import wtf.mazy.peel.ui.webapplist.WebAppListFragment
import wtf.mazy.peel.ui.webapplist.WebAppListHost
import wtf.mazy.peel.ui.webapplist.WebAppSelectionHandler
import wtf.mazy.peel.ui.webapplist.WebAppShareHost
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.TypedUrl
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.applyToolbarScreenInsets
import wtf.mazy.peel.util.disableSystemBarContrastEnforcement
import wtf.mazy.peel.util.toast

class MainActivity :
    PeelActivity(),
    WebAppListHost,
    WebAppShareHost {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var topSearch: SearchSurface
    private lateinit var bottomSearch: SearchSurface
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2

    private var pagerAdapter: GroupPagerAdapter? = null
    private var tabMediator: TabLayoutMediator? = null
    private var lastGroupKeys: List<Pair<String, String>> = emptyList()
    private var lastShowUngrouped: Boolean = true
    private lateinit var exportLoader: LoadingDialogController

    private lateinit var searchController: SearchModeController
    override lateinit var selectionController: EntitySelectionController<WebApp>
        private set
    private lateinit var chrome: ListChrome<WebApp>

    private val fragmentRegistry = mutableMapOf<String?, WebAppListFragment>()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val importFlow = ImportFlowController(this, ImportActivity::class.java)

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleSettingsResult(result.data)
        }

    private val backPressCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (searchController.isActive) searchController.exit() else chrome.handleBackPress()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        disableSystemBarContrastEnforcement()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyToolbarScreenInsets()

        toolbar = findViewById(R.id.toolbar)
        val fab = findViewById<FloatingActionButton>(R.id.fab)
        val bottomLine = findViewById<ViewGroup>(R.id.bottomLine)
        val bottomLineFab = findViewById<FloatingActionButton>(R.id.bottomLineFab)
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        // ViewPager2's inner RecyclerView is focusable and traps D-pad focus with no highlight,
        // making the FAB unreachable
        viewPager.getChildAt(0).isFocusable = false
        exportLoader = LoadingDialogController(this)

        EntityListAnimations.bindFabResizeOnRotation(this, fab)
        EntityListAnimations.bindFabResizeOnRotation(this, bottomLineFab)
        bottomLine.animateReflow()
        bottomLine.applyBottomScreenInsets()

        toolbar.setTitle(R.string.app_name)
        setSupportActionBar(toolbar)

        selectionController = EntitySelectionController(
            activity = this,
            toolbar = toolbar,
            actions = WebAppSelectionHandler(this, this),
            resolveItems = { ids -> DataManager.webApps.filter { it.uuid in ids } },
            onChanged = {
                renderChrome(animated = true)
                refreshSelectionAdapters()
                updateTabBadges()
            },
            config = SelectionConfig(
                titleResForCount = R.plurals.n_apps_selected,
                selectionMenuRes = R.menu.menu_selection,
                moveActionId = R.id.action_move_selected,
                deleteActionId = R.id.action_delete_selected,
            ),
        )
        topSearch = ContainedSearchSurface(
            activity = this,
            searchView = findViewById(R.id.searchView),
            content = findViewById(R.id.searchContent),
            fab = fab,
            resultsList = findViewById(R.id.searchResultsList),
            emptyState = ListEmptyState(findViewById(R.id.searchEmptyState)),
            suggestions = findViewById(R.id.searchSuggestions),
            onAdd = { buildAddWebsiteDialog() },
        )
        bottomSearch = BottomSearchSurface(
            activity = this,
            line = bottomLine,
            fab = bottomLineFab,
            bar = findViewById(R.id.searchBar),
            listPage = findViewById(R.id.listPage),
            results = findViewById(R.id.bottomSearchResults),
            resultsList = findViewById(R.id.bottomSearchResultsList),
            emptyState = ListEmptyState(findViewById(R.id.bottomSearchEmptyState)),
            suggestions = findViewById(R.id.bottomSearchSuggestions),
        )
        val initialSearch = surfaceFor(DataManager.globalEffectiveSettings.bottomSearch)
        chrome = ListChrome(
            activity = this,
            toolbar = toolbar,
            fab = initialSearch.fab,
            idleAction = initialSearch.idleAction,
            selection = selectionController,
            applyNormalToolbar = { bar ->
                bar.navigationIcon = null
                bar.setTitle(R.string.app_name)
            },
        )
        searchController = SearchModeController(
            activity = this,
            selection = selectionController,
            chrome = chrome,
            initial = initialSearch,
            onChanged = {
                renderChrome(animated = true)
                applyBottomClearance()
                if (!searchController.isActive) refreshCurrentPages()
            },
            onSurfaceChanged = {
                invalidateOptionsMenu()
                applyBottomClearance()
                renderChrome(animated = false)
            },
            onAddUrl = { url, onConfirmed, onCancelled ->
                buildAddWebsiteDialog(prefill = url, onConfirmed = onConfirmed, onCancelled = onCancelled)
            },
            onOpenPrivate = { url -> BrowserLauncher.launchIncognito(this, url) },
        )

        val onFabClicked = View.OnClickListener { chrome.onFabClicked() }
        fab.setOnClickListener(onFabClicked)
        bottomLineFab.setOnClickListener(onFabClicked)
        onBackPressedDispatcher.addCallback(this, backPressCallback)

        setupViewPager()
        restoreSelection(savedInstanceState)
        renderChrome(animated = false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataManager.state.collect {
                    if (searchController.isActive) {
                        searchController.onDataChanged()
                    } else {
                        refreshCurrentPages()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataManager.state
                    .map { it.globalSettings.settings.effective().bottomSearch }
                    .distinctUntilChanged()
                    .collect { searchController.attach(surfaceFor(it)) }
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
        prefs.edit { putBoolean("notification_permission_asked", true) }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingBackupIntent(intent)
    }

    override fun onPause() {
        searchController.onHostPaused()
        super.onPause()
    }

    override fun onDestroy() {
        importFlow.onHostDestroy()
        exportLoader.dismiss()
        super.onDestroy()
    }

    override fun launchSettings(intent: Intent) {
        settingsLauncher.launch(intent)
    }

    private fun handleSettingsResult(data: Intent?) {
        showApplyTimingSnackbar(this, data, SessionHostRegistry.hasLiveBrowsers)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (chrome.onCreateOptionsMenu(menu)) return true
        menuInflater.inflate(R.menu.menu_main, menu)
        val offered = searchController.appBarAction
        menu.findItem(R.id.action_search)?.isVisible = offered == AppBarAction.SEARCH
        menu.findItem(R.id.action_add)?.isVisible = offered == AppBarAction.ADD
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (chrome.onOptionsItemSelected(item)) return true
        return when (item.itemId) {
            R.id.action_search -> {
                searchController.enter()
                true
            }

            R.id.action_add -> {
                buildAddWebsiteDialog()
                true
            }

            R.id.action_settings -> {
                settingsLauncher.launch(Intent(this, SettingsHubActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun refreshWebAppList() = refreshCurrentPages()

    override val listBottomClearance: Int
        get() = searchController.listBottomClearance

    private fun surfaceFor(bottom: Boolean): SearchSurface = if (bottom) bottomSearch else topSearch

    fun refreshCurrentPages() {
        val groups = DataManager.sortedGroups
        val newGroupKeys = groups.map { it.uuid to it.title }
        val newShowUngrouped =
            if (groups.isEmpty()) true
            else DataManager.webAppsInGroup(null).isNotEmpty()

        if (lastGroupKeys != newGroupKeys || lastShowUngrouped != newShowUngrouped) {
            setupViewPager()
            return
        }

        fragmentRegistry.values.forEach { it.updateWebAppList() }
    }

    override fun registerFragment(groupFilter: String?, fragment: WebAppListFragment) {
        fragmentRegistry[groupFilter] = fragment
    }

    override fun unregisterFragment(groupFilter: String?) {
        fragmentRegistry.remove(groupFilter)
    }

    private fun setupViewPager() {
        tabMediator?.detach()
        tabMediator = null
        val groups = DataManager.sortedGroups
        val newAdapter: GroupPagerAdapter
        if (groups.isEmpty()) {
            tabLayout.visibility = View.GONE
            newAdapter = GroupPagerAdapter(this, emptyList(), showUngrouped = true)
            viewPager.adapter = newAdapter
            viewPager.isUserInputEnabled = false
            lastGroupKeys = emptyList()
            lastShowUngrouped = true
        } else {
            val hasUngrouped = DataManager.webAppsInGroup(null).isNotEmpty()
            tabLayout.visibility = View.VISIBLE
            newAdapter = GroupPagerAdapter(this, groups, showUngrouped = hasUngrouped)
            viewPager.adapter = newAdapter
            viewPager.isUserInputEnabled = true
            lastGroupKeys = groups.map { it.uuid to it.title }
            lastShowUngrouped = hasUngrouped

            tabMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = newAdapter.getPageTitle(position)
            }.also { it.attach() }
        }
        pagerAdapter = newAdapter
        updateTabBadges()
    }

    private fun refreshSelectionAdapters() {
        if (searchController.isActive) {
            searchController.onDataChanged()
        } else {
            fragmentRegistry.values.forEach { it.updateWebAppList() }
        }
    }

    private fun updateTabBadges() {
        val adapter = pagerAdapter ?: return
        val selected = selectionController.selectedIds
        val countByGroup = DataManager.webApps
            .filter { it.uuid in selected }
            .groupingBy { it.groupUuid }
            .eachCount()
        for (i in 0 until tabLayout.tabCount) {
            val tab = tabLayout.getTabAt(i) ?: continue
            val groupUuid = adapter.groups.getOrNull(i)?.uuid
            val count = countByGroup[groupUuid] ?: 0
            if (count > 0) {
                tab.orCreateBadge.number = count
            } else {
                tab.removeBadge()
            }
        }
    }

    private fun renderChrome(animated: Boolean) {
        backPressCallback.isEnabled = searchController.isActive || selectionController.isActive
        chrome.render(searching = searchController.isActive, animated = animated)
    }

    private fun applyBottomClearance() {
        fragmentRegistry.values.forEach { it.applyBottomClearance() }
    }

    private fun restoreSelection(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        val existing = DataManager.webApps.mapTo(HashSet()) { it.uuid }
        chrome.restoreState(savedInstanceState) { it in existing }
        updateTabBadges()
    }

    // The view hierarchy restores search's own views with the state — the contained view's
    // query and visibility, the bar's query and focus — after onCreate, so the surface is told
    // here whether search was open and decides what to do with that.
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        searchController.onRestoredState(savedInstanceState.getBoolean(STATE_SEARCHING))
        renderChrome(animated = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        chrome.saveState(outState)
        outState.putBoolean(STATE_SEARCHING, searchController.isActive)
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
                toast(R.string.export_share_failed, long = true)
            }
        }
    }

    private fun handleIncomingBackupIntent(intent: Intent?) {
        val uri = extractBackupUri(intent) ?: return
        intent?.action = null
        intent?.data = null
        intent?.removeExtra(Intent.EXTRA_STREAM)
        importFlow.showForUri(uri)
    }

    private fun extractBackupUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }

            else -> null
        }
    }

    private fun buildAddWebsiteDialog(
        prefill: String = "",
        onConfirmed: () -> Unit = {},
        onCancelled: () -> Unit = {},
    ) {
        showSandboxInputDialog(
            titleRes = R.string.add_webapp,
            hintRes = R.string.url,
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI,
            prefill = prefill,
            onCancel = onCancelled,
        ) { result ->
            val url = TypedUrl.normalize(result.text)
            val currentPage = viewPager.currentItem
            val groups = DataManager.sortedGroups
            val newSite = WebApp(
                baseUrl = url,
                isUseContainer = result.sandbox,
                isEphemeralSandbox = result.ephemeral,
                proxyUuid = result.proxyUuid,
                groupUuid = groups.getOrNull(currentPage)?.uuid,
            )

            onConfirmed()
            lifecycleScope.launch {
                DataManager.addWebApp(newSite, appendOrder = true)

                val settingsIntent = Intent(this@MainActivity, WebAppSettingsActivity::class.java)
                settingsIntent.putExtra(Const.INTENT_WEBAPP_UUID, newSite.uuid)
                settingsIntent.putExtra(Const.INTENT_AUTO_FETCH, true)
                settingsLauncher.launch(settingsIntent)
            }
        }
    }

    companion object {
        private const val STATE_SEARCHING = "searching"
    }
}
