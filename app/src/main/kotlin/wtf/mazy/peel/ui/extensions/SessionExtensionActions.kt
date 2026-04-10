package wtf.mazy.peel.ui.extensions

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.activities.ExtensionPageActivity
import wtf.mazy.peel.gecko.GeckoRuntimeProvider

class SessionExtensionActions(
    private val activity: FragmentActivity,
    private val onExtensionsReady: ((hasExtensions: Boolean) -> Unit)? = null,
    private val onNavigateToUrl: ((String) -> Unit)? = null,
) {
    data class Entry(
        val extension: WebExtension,
        val display: WebExtension.Action,
        val clickable: WebExtension.Action,
    )

    private data class Default(val extension: WebExtension, val action: WebExtension.Action)

    private val overrides = mutableMapOf<String, WebExtension.Action>()
    private val attachedSessions = mutableListOf<Pair<WebExtension, GeckoSession>>()
    private var currentContextId: String? = null
    private var currentPrivateMode: Boolean = false
    private var attachJob: Job? = null

    fun snapshot(): List<Entry> {
        return defaults.values.mapNotNull { (ext, defaultAction) ->
            val override = overrides[ext.id]
            val merged = override?.withDefault(defaultAction) ?: defaultAction
            if (merged.enabled == false) null else Entry(
                extension = ext,
                display = merged,
                clickable = override ?: defaultAction,
            )
        }.sortedBy { it.extension.metaData.name ?: it.extension.id }
    }

    fun attach(session: GeckoSession) {
        detach()
        currentContextId = session.settings.contextId
        currentPrivateMode = session.settings.usePrivateMode
        active = this

        attachJob = activity.lifecycleScope.launch {
            val extensions = GeckoRuntimeProvider.listUserExtensions(activity)
            hasExtensions = extensions.isNotEmpty()
            onExtensionsReady?.invoke(hasExtensions)
            val liveIds = extensions.mapTo(mutableSetOf()) { it.id }
            defaults.keys.retainAll(liveIds)

            applyGlobalDelegates(extensions)

            val sessionDelegate = object : WebExtension.ActionDelegate {
                override fun onBrowserAction(
                    extension: WebExtension,
                    session: GeckoSession?,
                    action: WebExtension.Action,
                ) {
                    overrides[extension.id] = action
                }

                override fun onPageAction(
                    extension: WebExtension,
                    session: GeckoSession?,
                    action: WebExtension.Action,
                ) {
                    overrides[extension.id] = action
                }

                override fun onTogglePopup(
                    extension: WebExtension,
                    action: WebExtension.Action,
                ): GeckoResult<GeckoSession> = createPopupSession(extension)

                override fun onOpenPopup(
                    extension: WebExtension,
                    action: WebExtension.Action,
                ): GeckoResult<GeckoSession> = createPopupSession(extension)
            }

            val controller = session.webExtensionController
            for (ext in extensions) {
                controller.setActionDelegate(ext, sessionDelegate)
                attachedSessions += ext to session
            }
        }
    }

    fun detach() {
        attachJob?.cancel()
        attachJob = null
        for ((ext, session) in attachedSessions) {
            session.webExtensionController.setActionDelegate(ext, null)
        }
        attachedSessions.clear()
        overrides.clear()
        currentContextId = null
        currentPrivateMode = false
        if (active === this) active = null
    }

    internal fun dismissPopup() {
        activity.supportFragmentManager
            .findFragmentByTag(ExtensionPopupBottomSheet.TAG)
            ?.let { (it as? ExtensionPopupBottomSheet)?.dismiss() }
    }

    private fun createPopupSession(extension: WebExtension): GeckoResult<GeckoSession> {
        val ctxId = currentContextId
        val priv = currentPrivateMode
        val runtime = GeckoRuntimeProvider.getRuntime(activity)
        val popupSettings = GeckoSessionSettings.Builder()
            .apply { ctxId?.let { contextId(it) } }
            .usePrivateMode(priv)
            .build()
        val popupSession = GeckoSession(popupSettings)
        popupSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                dismissPopup()
            }
        }
        popupSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    dismissPopup()
                    onNavigateToUrl?.invoke(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }
        popupSession.open(runtime)
        ExtensionPopupBottomSheet.showExistingSession(
            activity = activity,
            session = popupSession,
            title = extension.metaData.name ?: extension.id,
        )
        return GeckoResult.fromValue(popupSession)
    }

    companion object {
        @Volatile
        private var active: SessionExtensionActions? = null

        @Volatile
        var hasExtensions: Boolean = false
            private set

        @Volatile
        var extensionsChanged: Boolean = false

        private val defaults = mutableMapOf<String, Default>()
        private val managedExtensions = mutableMapOf<String, WebExtension>()

        private val globalActionDelegate = object : WebExtension.ActionDelegate {
            override fun onBrowserAction(
                extension: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action,
            ) {
                defaults[extension.id] = Default(extension, action)
            }

            override fun onPageAction(
                extension: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action,
            ) {
                defaults[extension.id] = Default(extension, action)
            }

            override fun onTogglePopup(
                extension: WebExtension,
                action: WebExtension.Action,
            ): GeckoResult<GeckoSession>? {
                return active?.createPopupSession(extension)
            }

            override fun onOpenPopup(
                extension: WebExtension,
                action: WebExtension.Action,
            ): GeckoResult<GeckoSession>? {
                return active?.createPopupSession(extension)
            }
        }

        private val globalTabDelegate = object : WebExtension.TabDelegate {
            override fun onNewTab(
                source: WebExtension,
                createDetails: WebExtension.CreateTabDetails,
            ): GeckoResult<GeckoSession>? {
                val url = createDetails.url ?: return null
                val host = active ?: return null
                val title = source.metaData.name ?: source.id
                host.activity.runOnUiThread {
                    host.dismissPopup()
                    host.activity.startActivity(
                        ExtensionPageActivity.intentForUrl(host.activity, url, title)
                    )
                }
                return null
            }

            override fun onOpenOptionsPage(source: WebExtension) {
                val host = active ?: return
                host.activity.runOnUiThread {
                    host.dismissPopup()
                    host.activity.startActivity(
                        ExtensionPageActivity.intentForExtension(host.activity, source.id)
                    )
                }
            }
        }

        fun setActive(instance: SessionExtensionActions) {
            active = instance
        }

        private fun applyGlobalDelegates(extensions: List<WebExtension>) {
            val liveIds = extensions.mapTo(mutableSetOf()) { it.id }
            managedExtensions.keys.retainAll(liveIds)
            for (ext in extensions) {
                if (ext.id !in managedExtensions) {
                    ext.setActionDelegate(globalActionDelegate)
                    ext.tabDelegate = globalTabDelegate
                    managedExtensions[ext.id] = ext
                }
            }
        }
    }
}
