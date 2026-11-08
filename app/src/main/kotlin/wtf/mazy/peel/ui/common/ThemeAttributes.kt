package wtf.mazy.peel.ui.common

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes

fun Context.themeResource(@AttrRes attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return value.resourceId
}
