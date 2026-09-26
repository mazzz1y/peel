package wtf.mazy.peel.ui.entitylist

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.ui.common.Theming
import wtf.mazy.peel.ui.dragReorderCallback
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.applyToolbarScreenInsets
import wtf.mazy.peel.util.disableSystemBarContrastEnforcement

abstract class EntityListActivity<T : Any> : PeelActivity() {

    protected lateinit var toolbar: MaterialToolbar
    protected lateinit var fab: FloatingActionButton
    protected lateinit var list: RecyclerView
    protected lateinit var emptyStateText: TextView
    protected lateinit var adapter: EntityListAdapter<T, *>

    @get:StringRes
    protected abstract val titleRes: Int

    @get:StringRes
    protected abstract val emptyStateRes: Int

    protected open val supportsDrag: Boolean = false

    @get:ColorInt
    protected var checkIconColor: Int = 0
        private set

    protected abstract fun createAdapter(): EntityListAdapter<T, *>
    protected abstract fun loadEntities(): List<T>
    protected abstract fun rowEntityUuid(entity: T): String

    protected open fun onAddClicked() {}
    protected open suspend fun reorder(uuids: List<String>) {}

    protected open fun createSelectionHandler(): EntitySelectionHandler<T>? = null
    protected open val selectionConfig: SelectionConfig? = null

    protected var selection: EntitySelectionController<T>? = null
        private set
    private var chrome: ListChrome<T>? = null

    protected open fun subscribeDataChanges(onChange: () -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataManager.state.collect { onChange() }
            }
        }
    }

    private val backPressCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            chrome?.handleBackPress()
        }
    }

    private var itemTouchHelper: ItemTouchHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        disableSystemBarContrastEnforcement()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_base_list)
        applyToolbarScreenInsets()

        toolbar = findViewById(R.id.toolbar)
        fab = findViewById(R.id.base_fab)
        list = findViewById(R.id.base_list)
        list.applyBottomScreenInsets()
        emptyStateText = findViewById(R.id.base_empty_state)
        emptyStateText.setText(emptyStateRes)

        checkIconColor = Theming.colorPrimary(this)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(titleRes)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, backPressCallback)

        setupSelection(savedInstanceState)

        adapter = createAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        if (supportsDrag) {
            itemTouchHelper = ItemTouchHelper(
                dragReorderCallback(
                    onMove = { from, to -> adapter.moveItem(from, to) },
                    onDrop = {
                        lifecycleScope.launch {
                            reorder(adapter.currentList.map { rowEntityUuid(it.entity) })
                        }
                    },
                ),
            )
        }

        fab.setOnClickListener { if (chrome?.onFabClicked() != true) onAddClicked() }
        EntityListAnimations.bindFabResizeOnRotation(this, fab)

        refreshList()
        renderChrome(animated = false)
        subscribeDataChanges(::refreshList)
    }

    private fun setupSelection(savedInstanceState: Bundle?) {
        val handler = createSelectionHandler() ?: return
        val config = selectionConfig ?: return
        val controller = EntitySelectionController(
            activity = this,
            toolbar = toolbar,
            actions = handler,
            resolveItems = { ids -> loadEntities().filter { rowEntityUuid(it) in ids } },
            onChanged = {
                renderChrome(animated = true)
                refreshList()
            },
            config = config,
        )
        selection = controller
        chrome = ListChrome(
            activity = this,
            toolbar = toolbar,
            fab = fab,
            selection = controller,
            applyNormalToolbar = { bar ->
                bar.setNavigationIcon(R.drawable.ic_symbols_arrow_back_24)
                bar.title = getString(titleRes)
            },
        )
        val existing = loadEntities().mapTo(HashSet(), ::rowEntityUuid)
        chrome?.restoreState(savedInstanceState) { it in existing }
    }

    private fun renderChrome(animated: Boolean) {
        val active = selection?.isActive == true
        backPressCallback.isEnabled = active
        setDragEnabled(!active)
        chrome?.render(animated = animated)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        chrome?.saveState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean =
        chrome?.onCreateOptionsMenu(menu) == true || super.onCreateOptionsMenu(menu)

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        chrome?.onOptionsItemSelected(item) == true || super.onOptionsItemSelected(item)

    protected fun buildRow(entity: T): EntityRow<T> = EntityRow(
        entity = entity,
        selected = selection?.isSelected(rowEntityUuid(entity)) == true,
        inSelectionMode = selection?.isActive == true,
    )

    protected fun refreshList() {
        val rows = loadEntities().map(::buildRow)
        adapter.submitRows(rows)
        val isEmpty = rows.isEmpty()
        emptyStateText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun setDragEnabled(enabled: Boolean) {
        val helper = itemTouchHelper ?: return
        helper.attachToRecyclerView(if (enabled) list else null)
    }
}
