package wtf.mazy.peel.ui.importmapping

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSurrogate
import wtf.mazy.peel.shortcut.LetterIconGenerator

class ImportMappingAdapter(
    private val items: List<WebAppSurrogate>,
    private val icons: Map<String, Bitmap>,
    val selectedUuids: MutableSet<String>,
) : RecyclerView.Adapter<ImportMappingAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.app_icon)
        val name: TextView = itemView.findViewById(R.id.app_name)
        val url: TextView = itemView.findViewById(R.id.app_url)
        val switch: MaterialSwitch = itemView.findViewById(R.id.app_switch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_import_mapping, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.name.text = item.title.ifBlank { item.baseUrl }
        holder.url.text = item.baseUrl

        val bitmap = icons[item.uuid]
        if (bitmap != null) {
            holder.icon.setImageBitmap(bitmap)
        } else {
            val fallback =
                LetterIconGenerator.generate(
                    item.title.ifBlank { item.baseUrl },
                    item.title.ifBlank { item.baseUrl },
                    ICON_SIZE_PX,
                )
            holder.icon.setImageBitmap(fallback)
        }

        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = item.uuid in selectedUuids
        holder.switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedUuids.add(item.uuid) else selectedUuids.remove(item.uuid)
        }

        holder.itemView.setOnClickListener { holder.switch.toggle() }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private const val ICON_SIZE_PX = 72
    }
}
