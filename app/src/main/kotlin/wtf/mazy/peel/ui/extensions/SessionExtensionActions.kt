package wtf.mazy.peel.ui.extensions

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.gecko.GeckoRuntimeProvider

class SessionExtensionActions(
    private val activity: FragmentActivity,
) {
    data class Entry(
        val extension: WebExtension,
        val display: WebExtension.Action,
        val clickable: WebExtension.Action,
    )

    private data class Default(val extension: WebExtension, val action: WebExtension.Action)

    private val defaults = mutableMapOf<String, Default>()
    private val overrides = mutableMapOf<String, WebExtension.Action>()
    private val attached = mutableListOf<Pair<WebExtension, GeckoSession>>()
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
        attachJob = activity.lifecycleScope.launch {
            val extensions = GeckoRuntimeProvider.listUserExtensions(activity)
            val liveIds = extensions.mapTo(mutableSetOf()) { it.id }
            defaults.keys.retainAll(liveIds)

            val globalDelegate = object : WebExtension.ActionDelegate {
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
                ): GeckoResult<GeckoSession>? = openPopup(extension)

                override fun onOpenPopup(
                    extension: WebExtension,
                    action: WebExtension.Action,
                ): GeckoResult<GeckoSession>? = openPopup(extension)
            }

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
                ): GeckoResult<GeckoSession>? = openPopup(extension)

                override fun onOpenPopup(
                    extension: WebExtension,
                    action: WebExtension.Action,
                ): GeckoResult<GeckoSession>? = openPopup(extension)
            }

            val controller = session.webExtensionController
            for (ext in extensions) {
                ext.setActionDelegate(globalDelegate)
                controller.setActionDelegate(ext, sessionDelegate)
                attached += ext to session
            }
        }
    }

    fun detach() {
        attachJob?.cancel()
        attachJob = null
        for ((ext, session) in attached) {
            ext.setActionDelegate(null)
            session.webExtensionController.setActionDelegate(ext, null)
        }
        attached.clear()
        defaults.clear()
        overrides.clear()
        currentContextId = null
        currentPrivateMode = false
    }

    private fun openPopup(extension: WebExtension): GeckoResult<GeckoSession> {
        val ctxId = currentContextId
        val priv = currentPrivateMode
        val runtime = GeckoRuntimeProvider.getRuntime(activity)
        val popupSettings = GeckoSessionSettings.Builder()
            .apply { ctxId?.let { contextId(it) } }
            .usePrivateMode(priv)
            .build()
        val popupSession = GeckoSession(popupSettings)
        popupSession.open(runtime)
        ExtensionPopupBottomSheet.showExistingSession(
            activity = activity,
            session = popupSession,
            title = extension.metaData.name ?: extension.id,
        )
        return GeckoResult.fromValue(popupSession)
    }
}
