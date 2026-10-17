package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.Isolation
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.model.ProxyRoute
import wtf.mazy.peel.ui.proxy.ProxyOption
import wtf.mazy.peel.ui.proxy.ProxyOptions
import wtf.mazy.peel.ui.proxy.ProxyPickerDialog

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
    onCancel: (() -> Unit)? = null,
    onResult: (SandboxInputResult<P>) -> Unit,
) {
    var switchSandbox: MaterialSwitch? = null
    var switchEphemeral: MaterialSwitch? = null
    var selectedProxy = defaultProxy
    val hasProxies = proxyOptions.any { it.choice is ProxyRoute.Via }

    showInputDialogRaw(
        InputDialogConfig(
            titleRes = titleRes,
            hintRes = hintRes,
            inputType = inputType,
            prefill = prefill,
            initialSelection = InitialSelection.CURSOR_AT_END,
            onCancel = onCancel,
            positiveRes = R.string.ok,
            extraContent = { container ->
                val density = resources.displayMetrics.density
                switchSandbox = MaterialSwitch(container.context).apply {
                    text = getString(R.string.enable_sandbox)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (16 * density).toInt() }
                }
                switchEphemeral = MaterialSwitch(container.context).apply {
                    text = getString(R.string.ephemeral_sandbox)
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (8 * density).toInt() }
                }

                val proxyButton = MaterialButton(
                    container.context,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                fun showSelectedProxy() {
                    proxyButton.text = ProxyOptions.current(proxyOptions, selectedProxy).routeLabel
                }
                showSelectedProxy()
                proxyButton.setOnClickListener {
                    ProxyPickerDialog.show(this, proxyOptions, selectedProxy) { picked ->
                        selectedProxy = picked
                        showSelectedProxy()
                    }
                }

                val proxyLabel = TextView(container.context).apply {
                    text = getString(R.string.proxy)
                    setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                        switchSandbox.textSize,
                    )
                    typeface = switchSandbox.typeface
                    setTextColor(switchSandbox.textColors)
                    letterSpacing = switchSandbox.letterSpacing
                    if (switchSandbox.lineHeight > 0) lineHeight = switchSandbox.lineHeight
                    fontFeatureSettings = switchSandbox.fontFeatureSettings
                    includeFontPadding = switchSandbox.includeFontPadding
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                }
                val proxyRowView = LinearLayout(container.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (8 * density).toInt() }
                    addView(proxyLabel)
                    addView(proxyButton)
                }

                switchSandbox.setOnCheckedChangeListener { _, isChecked ->
                    switchEphemeral.visibility = if (isChecked) View.VISIBLE else View.GONE
                    if (!isChecked) switchEphemeral.isChecked = false
                    val showProxy = isChecked && hasProxies
                    proxyRowView.visibility = if (showProxy) View.VISIBLE else View.GONE
                    if (!isChecked) {
                        selectedProxy = defaultProxy
                        showSelectedProxy()
                    }
                }
                container.addView(switchSandbox)
                container.addView(switchEphemeral)
                container.addView(proxyRowView)
            },
        ),
    ) { input, _ ->
        val text = input.text?.toString()?.trim() ?: return@showInputDialogRaw
        if (text.isBlank()) return@showInputDialogRaw
        val isolation = if (switchSandbox?.isChecked == true) {
            Isolation.Sandboxed(switchEphemeral?.isChecked == true, selectedProxy)
        } else {
            Isolation.Shared
        }
        onResult(SandboxInputResult(text, isolation))
    }
}
