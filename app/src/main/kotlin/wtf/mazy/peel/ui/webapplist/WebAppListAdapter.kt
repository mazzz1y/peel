package wtf.mazy.peel.ui.webapplist

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import java.util.Collections
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.WebAppSettingsActivity
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.WebViewLauncher.startWebView

class WebAppListAdapter(private val activityOfFragment: Activity) :
    RecyclerView.Adapter<WebAppListAdapter.ViewHolder>() {

    var items: MutableList<WebApp> = mutableListOf()
        private set

    /**
     * null = show ALL apps. UNGROUPED_FILTER = show only apps with no group.
     * Otherwise = show apps matching this group UUID.
     */
    var groupFilter: String? = null

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val titleView: TextView = itemView.findViewById(R.id.btnWebAppTitle)
        val urlView: TextView = itemView.findViewById(R.id.appUrl)
        val menuButton: ImageView = itemView.findViewById(R.id.btnMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.web_app_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.titleView.text = item.title
        holder.urlView.text = item.baseUrl

        loadIcon(item, holder.appIcon)

        holder.itemView.setOnClickListener { openWebView(item) }

        holder.menuButton.setOnClickListener { view ->
            showPopupMenu(view, item, holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount() = items.size

    fun moveItem(from: Int, to: Int) {
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    private fun loadIcon(webapp: WebApp, imageView: ImageView) {
        if (webapp.hasCustomIcon) {
            val bitmap = BitmapFactory.decodeFile(webapp.iconFile.absolutePath)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                return
            }
        }
        val sizePx = imageView.context.resources.displayMetrics.density * 48
        imageView.setImageBitmap(
            LetterIconGenerator.generate(webapp.title, webapp.baseUrl, sizePx.toInt())
        )
    }

    private fun showPopupMenu(view: View, webapp: WebApp, position: Int) {
        val popup = PopupMenu(activityOfFragment, view)
        popup.menuInflater.inflate(R.menu.webapp_item_menu, popup.menu)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_settings -> {
                    openSettings(webapp)
                    true
                }

                R.id.action_add_to_home -> {
                    ShortcutHelper.createShortcut(webapp, activityOfFragment)
                    true
                }

                R.id.action_clone -> {
                    cloneWebApp(webapp, position)
                    true
                }

                R.id.action_delete -> {
                    deleteWebApp(webapp, position)
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun openWebView(webapp: WebApp) {
        startWebView(webapp, activityOfFragment)
    }

    private fun openSettings(webapp: WebApp) {
        val intent = Intent(activityOfFragment, WebAppSettingsActivity::class.java)
        intent.putExtra(Const.INTENT_WEBAPP_UUID, webapp.uuid)
        activityOfFragment.startActivity(intent)
    }

    private fun cloneWebApp(webapp: WebApp, position: Int) {
        val clonedWebApp = WebApp(webapp.baseUrl)
        clonedWebApp.title = webapp.title
        clonedWebApp.settings = webapp.settings.copy()
        clonedWebApp.order = webapp.order + 1
        clonedWebApp.groupUuid = webapp.groupUuid

        if (webapp.hasCustomIcon) {
            try {
                val destFile = clonedWebApp.iconFile
                destFile.parentFile?.mkdirs()
                webapp.iconFile.copyTo(destFile, overwrite = true)
            } catch (_: Exception) {
            }
        }

        DataManager.instance.addWebsite(clonedWebApp)
        val insertPosition = position + 1
        items.add(insertPosition, clonedWebApp)
        notifyItemInserted(insertPosition)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun deleteWebApp(webapp: WebApp, position: Int) {
        if (position < 0 || position >= items.size) return

        webapp.markInactive(activityOfFragment)
        items.removeAt(position)
        notifyItemRemoved(position)

        val message = activityOfFragment.getString(R.string.x_was_removed, webapp.title)
        val snackBar =
            Snackbar.make(
                activityOfFragment.findViewById(android.R.id.content),
                message,
                Snackbar.LENGTH_LONG,
            )

        var isUndone = false

        snackBar.setAction(activityOfFragment.getString(R.string.undo)) {
            isUndone = true
            webapp.isActiveEntry = true
            val insertPosition = minOf(position, items.size)
            items.add(insertPosition, webapp)
            notifyItemInserted(insertPosition)
        }

        snackBar.addCallback(
            object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (!isUndone) {
                        webapp.cleanupWebAppData(activityOfFragment)
                        DataManager.instance.removeWebApp(webapp)
                    }
                }
            })

        snackBar.show()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateWebAppList() {
        items =
            when (groupFilter) {
                null -> DataManager.instance.activeWebsites.toMutableList()
                WebAppListFragment.UNGROUPED_FILTER ->
                    DataManager.instance.activeWebsitesForGroup(null).toMutableList()

                else -> DataManager.instance.activeWebsitesForGroup(groupFilter).toMutableList()
            }
        notifyDataSetChanged()
    }
}
