package wtf.mazy.peel.ui.entitylist

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
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
import wtf.mazy.peel.ui.common.Theming
import wtf.mazy.peel.ui.dragReorderCallback

abstract class EntityListActivity<T : Any> : AppCompatActivity(), EntityListHost {

    final override lateinit var toolbar: MaterialToolbar
    final override lateinit var fab: FloatingActionButton
    final override val hostActivity: AppCompatActivity get() = this

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

    protected open fun buildRow(entity: T): EntityRow<T> =
        EntityRow(entity = entity, selected = false, inSelectionMode = false)

    protected open fun onAddClicked() {}
    protected open suspend fun reorder(uuids: List<String>) {}

    protected open fun subscribeDataChanges(onChange: () -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataManager.instance.state.collect { onChange() }
            }
        }
    }

    protected val backPressCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = handleBackPress()
    }

    protected open fun shouldHandleBackPress(): Boolean = false

    protected open fun handleBackPress() {
        finish()
    }

    private var itemTouchHelper: ItemTouchHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_base_list)

        toolbar = findViewById(R.id.toolbar)
        fab = findViewById(R.id.base_fab)
        list = findViewById(R.id.base_list)
        emptyStateText = findViewById(R.id.base_empty_state)
        emptyStateText.setText(emptyStateRes)

        checkIconColor = Theming.colorPrimary(this)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(titleRes)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, backPressCallback)

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
            itemTouchHelper?.attachToRecyclerView(list)
        }

        fab.setOnClickListener { onFabClicked() }
        EntityListAnimations.bindFabResizeOnRotation(this, fab)

        refreshList()
        subscribeDataChanges(::refreshList)
    }

    protected open fun onFabClicked() {
        onAddClicked()
    }

    protected fun refreshList() {
        val rows = loadEntities().map(::buildRow)
        adapter.submitRows(rows)
        val isEmpty = rows.isEmpty()
        emptyStateText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    protected fun setDragEnabled(enabled: Boolean) {
        val helper = itemTouchHelper ?: return
        if (enabled) helper.attachToRecyclerView(list) else helper.attachToRecyclerView(null)
    }

    override fun crossfadeToolbar(swap: () -> Unit) =
        EntityListAnimations.crossfadeToolbar(toolbar, swap)

    override fun animateFabSwap(iconRes: Int) =
        EntityListAnimations.animateFabSwap(fab, iconRes)

    override fun applyNormalToolbar() {
        crossfadeToolbar {
            removeSearchViewFromToolbar()
            toolbar.menu.clear()
            toolbar.setOnMenuItemClickListener(null)
            toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            toolbar.title = getString(titleRes)
        }
    }

    override fun updateBackPressEnabled() {
        backPressCallback.isEnabled = shouldHandleBackPress()
    }
}
