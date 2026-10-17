package wtf.mazy.peel.model

import android.content.Context
import wtf.mazy.peel.util.prettyHostLabel
import java.io.File
import java.util.Objects
import java.util.UUID

internal fun deleteAppPrefs(context: Context, uuid: String) {
    val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
    prefsDir.listFiles { f -> f.name.startsWith(uuid) }?.forEach { it.delete() }
}

data class WebApp(
    val baseUrl: String,
    override val uuid: String = UUID.randomUUID().toString(),
    override val title: String = defaultTitle(baseUrl),
    override val isolation: Isolation<ProxyChoice> = Isolation.Shared,
    val isPrivateSession: Boolean = false,
    val order: Int = 0,
    val groupUuid: String? = null,
    val settings: WebAppSettings = WebAppSettings(),
) : IconOwner, SandboxOwner<WebApp, ProxyChoice> {

    override val letterIconSeed: String
        get() = baseUrl

    override val defaultProxy: ProxyChoice
        get() = ProxyChoice.Inherit

    override fun withIsolation(isolation: Isolation<ProxyChoice>): WebApp =
        copy(isolation = isolation)

    val contentFingerprint: Int
        get() = Objects.hash(title, baseUrl, groupUuid, isolation)

    companion object {
        fun defaultTitle(baseUrl: String): String =
            baseUrl.takeIf { it.isNotEmpty() }?.let { prettyHostLabel(it) } ?: baseUrl
    }
}
