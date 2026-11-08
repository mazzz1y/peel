package wtf.mazy.peel.ui.dialog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import wtf.mazy.peel.ui.common.showIcon
import wtf.mazy.peel.ui.common.themeResource

object MenuDialogViews {

    private const val TITLE_EXPANDED_MAX_LINES = 15

    fun buildHeader(context: Context, title: String?, url: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val headerTitle = title?.takeIf { it.isNotBlank() }
            setPadding(
                dpToPx(context, 16f),
                dpToPx(context, 16f),
                dpToPx(context, 8f),
                dpToPx(context, 16f)
            )
            addView(TextView(context).apply {
                text = headerTitle ?: url
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setTextAppearance(androidx.appcompat.R.style.Base_DialogWindowTitle_AppCompat)
                setTypeface(typeface, Typeface.BOLD)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    14,
                    24,
                    2,
                    TypedValue.COMPLEX_UNIT_SP,
                )
                setOnClickListener { maxLines = TITLE_EXPANDED_MAX_LINES }
            })
            if (headerTitle != null) {
                addView(TextView(context).apply {
                    text = url
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                    )
                    setTextColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            0,
                        )
                    )
                    setPadding(0, dpToPx(context, 2f), 0, 0)
                })
            }
        }

    fun buildDivider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(context, 1f),
        )
        setBackgroundColor(
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0),
        )
    }

    fun buildActionRow(
        context: Context,
        label: String,
        icon: Bitmap? = null,
        onIconClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ): View {
        val ripple = context.themeResource(android.R.attr.selectableItemBackground)
        val textColor =
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)

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
                setBackgroundResource(ripple)
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
                setBackgroundResource(ripple)
                setOnClickListener { onClick() }
            })

            val iconSize = dpToPx(context, 24f)
            val iconPad = dpToPx(context, 16f)
            addView(ImageView(context).apply {
                showIcon(icon)
                layoutParams = LinearLayout.LayoutParams(
                    iconSize + iconPad * 2,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
                setPadding(iconPad, 0, iconPad, 0)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true
                setBackgroundResource(ripple)
                setOnClickListener { onIconClick() }
            })
        }
    }

    fun prettyDataUrl(url: String): String =
        if (url.startsWith("data:")) url.substringBefore(",").substringAfter("data:") else url

    fun dpToPx(context: Context, dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics,
    ).toInt()
}
