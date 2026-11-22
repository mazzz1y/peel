package wtf.mazy.peel.ui.webapplist

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.entitylist.EntityListViewHolder
import wtf.mazy.peel.util.prettyBaseUrl

class WebAppRowHolder(itemView: View) : EntityListViewHolder(itemView) {
    private val webAppIndicators = WebAppIndicators(itemView)
    override val indicators: List<ImageView> = webAppIndicators.views
    private val titleView: TextView = itemView.findViewById(R.id.item_primary)
    private val urlView: TextView = itemView.findViewById(R.id.item_secondary)
    private val groupLabel: TextView = itemView.findViewById(R.id.item_tertiary)

    fun bind(app: WebApp, groupTitle: String?) {
        titleView.text = app.title
        urlView.text = prettyBaseUrl(app.baseUrl)
        groupLabel.text = groupTitle
        groupLabel.isVisible = groupTitle != null
        webAppIndicators.bind(app)
    }
}
