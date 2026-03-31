package wtf.mazy.peel.browser

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object MenuDialogHelper {

    fun buildHeader(context: Context, title: String?, url: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(context, 16f),
                dpToPx(context, 20f),
                dpToPx(context, 16f),
                dpToPx(context, 14f)
            )
            if (title != null) {
                addView(TextView(context).apply {
                    text = title
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(
                        resolveThemeColor(
                            context,
                            com.google.android.material.R.attr.colorOnSurface
                        )
                    )
                })
            }
            addView(TextView(context).apply {
                text = url
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(
                    resolveThemeColor(
                        context,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                if (title != null) setPadding(0, dpToPx(context, 2f), 0, 0)
            })
        }

    fun buildDivider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(context, 1f),
        )
        setBackgroundColor(
            resolveThemeColor(
                context,
                com.google.android.material.R.attr.colorOutlineVariant
            )
        )
    }

    fun buildActionRow(
        context: Context,
        label: String,
        icon: Bitmap? = null,
        onIconClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ): View {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        val textColor =
            resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface)

        if (icon == null || onIconClick == null) {
            return TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(textColor)
                setPadding(
                    dpToPx(context, 16f),
                    dpToPx(context, 14f),
                    dpToPx(context, 16f),
                    dpToPx(context, 14f)
                )
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(textColor)
                setPadding(
                    dpToPx(context, 16f),
                    dpToPx(context, 14f),
                    dpToPx(context, 12f),
                    dpToPx(context, 14f)
                )
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            })

            val iconSize = dpToPx(context, 24f)
            val iconPad = dpToPx(context, 16f)
            addView(ImageView(context).apply {
                setImageBitmap(icon)
                layoutParams = LinearLayout.LayoutParams(
                    iconSize + iconPad * 2,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
                setPadding(iconPad, 0, iconPad, 0)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onIconClick() }
            })
        }
    }

    fun displayUrl(url: String): String {
        if (url.startsWith("data:")) return url.substringBefore(",").substringAfter("data:")
        val q = url.indexOf('?')
        return if (q > 0) url.substring(0, q) else url
    }

    fun dpToPx(context: Context, dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics,
    ).toInt()

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
