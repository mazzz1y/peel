package wtf.mazy.peel.ui.browser

import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import wtf.mazy.peel.ui.AppLock
import wtf.mazy.peel.ui.ProtectionGate

/** The lock of one window of a web app; every window of the same web app shares [AppLock]. */
class AppLockController(
    private val activity: AppCompatActivity,
    private val ownerUuid: () -> String?,
    private val isProtected: () -> Boolean,
    private val content: () -> View?,
    private val onSuccess: () -> Unit,
    private val onFailure: () -> Unit,
) {
    var isPromptActive = false
        private set

    private var shownAs: String? = null

    private val isLocked: Boolean
        get() = isProtected() && ownerUuid()?.let(AppLock::isUnlocked) != true

    fun onStart() {
        val uuid = ownerUuid() ?: return
        AppLock.windowShown(uuid)
        shownAs = uuid
    }

    fun onStop() {
        shownAs?.let(AppLock::windowHidden)
        shownAs = null
        if (isLocked) content()?.visibility = View.INVISIBLE
    }

    fun ownerChanged() {
        isPromptActive = false
        val previous = shownAs ?: return
        val current = ownerUuid()
        if (current == previous) return
        AppLock.windowHidden(previous)
        shownAs = current?.also(AppLock::windowShown)
    }

    fun applyRecentsProtection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!isProtected())
        }
    }

    fun showPromptIfNeeded(onPromptShown: () -> Unit): PromptOutcome {
        if (isPromptActive) return PromptOutcome.SHOWN
        if (!isLocked) {
            content()?.visibility = View.VISIBLE
            return PromptOutcome.NOT_NEEDED
        }
        content()?.visibility = View.INVISIBLE
        val askedFor = ownerUuid() ?: return PromptOutcome.NOT_NEEDED
        isPromptActive = true
        onPromptShown()

        // A result for a web app this window no longer shows must not unlock or close it.
        val shown = ProtectionGate.prompt(
            activity,
            onPass = {
                if (askedFor == ownerUuid()) {
                    AppLock.unlock(askedFor)
                    isPromptActive = false
                    content()?.visibility = View.VISIBLE
                    onSuccess()
                }
            },
            onDenied = {
                if (askedFor == ownerUuid()) {
                    isPromptActive = false
                    onFailure()
                }
            },
        )
        if (!shown) isPromptActive = false
        return if (shown) PromptOutcome.SHOWN else PromptOutcome.UNAVAILABLE
    }

    enum class PromptOutcome { SHOWN, NOT_NEEDED, UNAVAILABLE }
}
