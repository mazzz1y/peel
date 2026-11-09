package wtf.mazy.peel.ui.browser

import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.AppLock
import wtf.mazy.peel.ui.BiometricPrompter
import wtf.mazy.peel.util.biometricUnavailableReason
import wtf.mazy.peel.util.toast

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

        val unavailableReason = activity.biometricUnavailableReason()
        if (unavailableReason != null) {
            reportUnavailable(unavailableReason)
            return PromptOutcome.UNAVAILABLE
        }

        return showPrompt(onPromptShown)
    }

    private fun showPrompt(onPromptShown: () -> Unit): PromptOutcome {
        val askedFor = ownerUuid() ?: return PromptOutcome.NOT_NEEDED
        isPromptActive = true
        onPromptShown()

        // A result for a web app this window no longer shows must not unlock or close it.
        val shown = BiometricPrompter(activity)
            .showPrompt(
                {
                    if (askedFor == ownerUuid()) {
                        AppLock.unlock(askedFor)
                        isPromptActive = false
                        content()?.visibility = View.VISIBLE
                        onSuccess()
                    }
                },
                {
                    if (askedFor == ownerUuid()) {
                        isPromptActive = false
                        onFailure()
                    }
                },
                activity.getString(R.string.bioprompt_restricted_webapp),
            )

        if (!shown) {
            isPromptActive = false
            reportUnavailable(activity.getString(R.string.no_biometric_devices))
            return PromptOutcome.UNAVAILABLE
        }
        return PromptOutcome.SHOWN
    }

    private fun reportUnavailable(reason: String) {
        activity.toast(reason, long = true)
        onFailure()
    }

    enum class PromptOutcome { SHOWN, NOT_NEEDED, UNAVAILABLE }
}
