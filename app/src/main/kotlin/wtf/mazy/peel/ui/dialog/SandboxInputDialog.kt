package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.text.InputType
import androidx.annotation.StringRes
import wtf.mazy.peel.R
import wtf.mazy.peel.model.Isolation
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.ui.proxy.ProxyOption

data class SandboxInputResult<out P : ProxyChoice>(
    val text: String,
    val isolation: Isolation<P>,
)

fun <P : ProxyChoice> Activity.showSandboxInputDialog(
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    proxyOptions: List<ProxyOption<P>>,
    defaultProxy: P,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    prefill: String = "",
    onResult: (SandboxInputResult<P>) -> Unit,
) {
    lateinit var controls: SandboxControls<P>
    showInputDialogRaw(
        InputDialogConfig(
            titleRes = titleRes,
            hintRes = hintRes,
            inputType = inputType,
            prefill = prefill,
            initialSelection = InitialSelection.CURSOR_AT_END,
            positiveRes = R.string.ok,
            extraContent = { container ->
                controls = SandboxControls(this, container, proxyOptions, defaultProxy)
            },
        ),
    ) { input, _ ->
        val text = input.text?.toString()?.trim() ?: return@showInputDialogRaw
        if (text.isBlank()) return@showInputDialogRaw
        onResult(SandboxInputResult(text, controls.isolation))
    }
}
