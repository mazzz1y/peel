package wtf.mazy.peel.ui.entitylist

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class IdleAction(
    @get:DrawableRes val icon: Int,
    @get:StringRes val description: Int,
    val action: () -> Unit,
)
