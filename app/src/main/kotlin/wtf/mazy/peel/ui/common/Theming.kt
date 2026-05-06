package wtf.mazy.peel.ui.common

import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors

object Theming {
    @ColorInt
    fun colorPrimary(activity: AppCompatActivity): Int =
        resolveColor(activity, androidx.appcompat.R.attr.colorPrimary)

    @ColorInt
    fun colorOnPrimary(activity: AppCompatActivity): Int =
        resolveColor(activity, com.google.android.material.R.attr.colorOnPrimary)

    @ColorInt
    private fun resolveColor(activity: AppCompatActivity, attrRes: Int): Int =
        MaterialColors.getColor(activity.window.decorView, attrRes, 0)
}
