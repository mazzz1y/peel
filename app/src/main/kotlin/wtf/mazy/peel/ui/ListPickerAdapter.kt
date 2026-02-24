package wtf.mazy.peel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import wtf.mazy.peel.R

class ListPickerAdapter<T>(
    private val items: List<T>,
    private val bind: (item: T, icon: ImageView, name: TextView, detail: TextView) -> Unit,
) : BaseAdapter() {
    override fun getCount() = items.size

    override fun getItem(position: Int) = items[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view =
            convertView
                ?: LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_share_picker, parent, false)
        bind(
            items[position],
            view.findViewById(R.id.appIcon),
            view.findViewById(R.id.appName),
            view.findViewById(R.id.groupName),
        )
        return view
    }
}
