package wtf.mazy.peel.ui.extensions

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import wtf.mazy.peel.activities.ExtensionPageActivity
import wtf.mazy.peel.browser.SessionHost
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.util.ForegroundActivityTracker

class SessionExtensionActions(
    private val activity: FragmentActivity,
    private val onExtensionsReady: ((hasExtensions: Boolean) -> Unit)? = null,
    private val onNavigateToUrl: ((String) -> Unit)? = null,
    private val onPopupDownload: ((WebResponse) -> Unit)? = null,
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
            defaults.keys.retainAll(extensions.mapTo(mutableSetOf()) { it.id })

            applyGlobalDelegates(extensions)

            val sessionDelegate = SessionActionDelegate()
            val sessionTabDelegate = SessionTabBoundDelegate()

            val controller = session.webExtensionController
            for (ext in extensions) {
                controller.setActionDelegate(ext, sessionDelegate)
                controller.setTabDelegate(ext, sessionTabDelegate)
                attachedSessions += ext to session
            }
        }
    }

    fun detach() {
        attachJob?.cancel()
        attachJob = null
        for ((ext, session) in attachedSessions) {
            session.webExtensionController.setActionDelegate(ext, null)
            session.webExtensionController.setTabDelegate(ext, null)
        }
        attachedSessions.clear()
        overrides.clear()
        currentContextId = null
        currentPrivateMode = false
        if (active === this) active = null
    }

    internal fun dismissPopup() {
        (activity.supportFragmentManager.findFragmentByTag(ExtensionPopupBottomSheet.TAG)
            as? ExtensionPopupBottomSheet)
            ?.dismissImmediately()
    }

    private fun mainSession(): GeckoSession? = attachedSessions.firstOrNull()?.second

    private fun createPopupSession(extension: WebExtension): GeckoResult<GeckoSession> {
        val runtime = GeckoRuntimeProvider.getRuntime(activity)
        val session = GeckoSession(buildAuxiliarySettings())
        session.contentDelegate = popupContentDelegate()
        session.navigationDelegate = popupNavigationDelegate()
        session.open(runtime)
        showSheet(activity, session, extension.metaData.name ?: extension.id, runtime)
        return GeckoResult.fromValue(session)
    }

    private fun buildAuxiliarySettings(): GeckoSessionSettings =
        GeckoSessionSettings.Builder()
            .apply { currentContextId?.let { contextId(it) } }
            .usePrivateMode(currentPrivateMode)
            .build()

    private fun popupContentDelegate() = object : GeckoSession.ContentDelegate {
        override fun onCloseRequest(session: GeckoSession) {
            ExtensionPopupBottomSheet.dismissFor(session)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            onPopupDownload?.invoke(response)
        }
    }

    private fun popupNavigationDelegate() = object : GeckoSession.NavigationDelegate {
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

    private fun showSheet(
        host: FragmentActivity,
        session: GeckoSession,
        title: String,
        runtime: GeckoRuntime,
    ) {
        mainSession()?.let { main ->
            ExtensionPopupBottomSheet.setOnDismissCallback(session) {
                runtime.webExtensionController.setTabActive(main, true)
            }
        }
        host.runOnUiThread {
            ExtensionPopupBottomSheet.showExistingSession(host, session, title)
        }
    }

    private inner class SessionActionDelegate : WebExtension.ActionDelegate {
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

    private inner class SessionTabBoundDelegate : WebExtension.SessionTabDelegate {
        override fun onUpdateTab(
            extension: WebExtension,
            targetSession: GeckoSession,
            details: WebExtension.UpdateTabDetails,
        ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)

        override fun onCloseTab(
            source: WebExtension?,
            targetSession: GeckoSession,
        ): GeckoResult<AllowOrDeny> {
            (activity as? SessionHost)?.let { host -> host.runOnUi { host.goBackOrFinish() } }
            return GeckoResult.fromValue(AllowOrDeny.ALLOW)
        }
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
            ): GeckoResult<GeckoSession>? = active?.createPopupSession(extension)

            override fun onOpenPopup(
                extension: WebExtension,
                action: WebExtension.Action,
            ): GeckoResult<GeckoSession>? = active?.createPopupSession(extension)
        }

        private val globalTabDelegate = object : WebExtension.TabDelegate {
            override fun onNewTab(
                source: WebExtension,
                createDetails: WebExtension.CreateTabDetails,
            ): GeckoResult<GeckoSession>? {
                createDetails.url ?: return null
                val host = active?.activity
                    ?: ForegroundActivityTracker.current as? FragmentActivity
                    ?: return null
                val owner = active
                val runtime = GeckoRuntimeProvider.getRuntime(host)
                val session = GeckoSession(buildSheetSessionSettings(owner))
                session.contentDelegate = sheetContentDelegate(owner)
                session.navigationDelegate = sheetNavigationDelegate()
                owner?.mainSession()?.let { main ->
                    ExtensionPopupBottomSheet.setOnDismissCallback(session) {
                        runtime.webExtensionController.setTabActive(main, true)
                    }
                }
                val title = source.metaData.name ?: source.id
                host.runOnUiThread {
                    ExtensionPopupBottomSheet.showExistingSession(host, session, title)
                }
                return GeckoResult.fromValue(session)
            }

            override fun onOpenOptionsPage(source: WebExtension) {
                val host = active?.activity
                    ?: ForegroundActivityTracker.current as? FragmentActivity
                    ?: return
                host.runOnUiThread {
                    active?.dismissPopup()
                    host.startActivity(
                        ExtensionPageActivity.intentForExtension(host, source.id)
                    )
                }
            }
        }

        private fun buildSheetSessionSettings(
            owner: SessionExtensionActions?,
        ): GeckoSessionSettings = GeckoSessionSettings.Builder()
            .apply { owner?.currentContextId?.let { contextId(it) } }
            .usePrivateMode(owner?.currentPrivateMode == true)
            .build()

        private fun sheetContentDelegate(
            owner: SessionExtensionActions?,
        ) = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                ExtensionPopupBottomSheet.dismissFor(session)
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                owner?.onPopupDownload?.invoke(response)
            }
        }

        private fun sheetNavigationDelegate() = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    session.loadUri(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }

        fun setActive(instance: SessionExtensionActions) {
            active = instance
        }

        fun ensureExtensionDelegatesRegistered(extensions: List<WebExtension>) {
            applyGlobalDelegates(extensions)
        }

        private fun applyGlobalDelegates(extensions: List<WebExtension>) {
            managedExtensions.keys.retainAll(extensions.mapTo(mutableSetOf()) { it.id })
            for (ext in extensions) {
                if (ext.id in managedExtensions) continue
                ext.setActionDelegate(globalActionDelegate)
                ext.tabDelegate = globalTabDelegate
                managedExtensions[ext.id] = ext
            }
        }
    }
}
