package wtf.mazy.peel.ui.settings

import wtf.mazy.peel.model.SettingCategory
import wtf.mazy.peel.model.SettingDefinition

sealed interface SettingsListItem {
    data class Header(val category: SettingCategory) : SettingsListItem
    data class Setting(val definition: SettingDefinition) : SettingsListItem
    data object Divider : SettingsListItem
}
