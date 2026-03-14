package wtf.mazy.peel.model

object ShareSecretsPolicy {
    fun hasSecrets(settings: WebAppSettings): Boolean {
        return settings.customHeaders?.any { (k, v) -> k.isNotBlank() || v.isNotBlank() } == true
                || !settings.basicAuthUsername.isNullOrBlank()
                || !settings.basicAuthPassword.isNullOrBlank()
    }

    fun hasSecretsInWebApps(webApps: List<WebApp>): Boolean {
        return webApps.any { hasSecrets(it.settings) }
    }

    fun hasSecretsInGroups(groups: List<WebAppGroup>): Boolean {
        return groups.any { hasSecrets(it.settings) }
    }

    fun hasSecrets(groups: List<WebAppGroup>, webApps: List<WebApp>): Boolean {
        return hasSecretsInGroups(groups) || hasSecretsInWebApps(webApps)
    }
}
