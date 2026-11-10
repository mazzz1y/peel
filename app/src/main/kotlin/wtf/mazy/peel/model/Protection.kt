package wtf.mazy.peel.model

object Protection {

    fun of(webApp: WebApp): Boolean = DataManager.effectiveSettings(webApp).biometricProtection

    fun of(group: WebAppGroup): Boolean = DataManager.effectiveSettings(group).biometricProtection

    val global: Boolean
        get() = DataManager.globalEffectiveSettings.biometricProtection

    val anywhere: Boolean
        get() = global || DataManager.groups.any(::of) || DataManager.webApps.any(::of)
}
