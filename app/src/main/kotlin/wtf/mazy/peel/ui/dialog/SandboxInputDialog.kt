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
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.bindDropdown

data class SandboxInputResult(
    val text: String,
    val sandbox: Boolean,
    val ephemeral: Boolean,
    val proxyUuid: String?,
)

fun Activity.showSandboxInputDialog(
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    onResult: (SandboxInputResult) -> Unit,
) {
    var switchSandbox: MaterialSwitch? = null
    var switchEphemeral: MaterialSwitch? = null
    var selectedProxyUuid: String? = null
    val proxies = DataManager.instance.getProxies().sortedBy { it.displayName().lowercase() }

    showInputDialogRaw(
        InputDialogConfig(
            titleRes = titleRes,
            hintRes = hintRes,
            inputType = inputType,
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

                val proxyLabels = listOf(getString(R.string.proxy_direct)) +
                        proxies.map { it.displayName() }
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
                proxyButton.bindDropdown(
                    items = proxyLabels,
                    currentIndex = {
                        val uuid = selectedProxyUuid
                        if (uuid == null) 0
                        else proxies.indexOfFirst { it.uuid == uuid }.let { idx ->
                            if (idx >= 0) idx + 1 else 0
                        }
                    },
                    onSelected = { i ->
                        selectedProxyUuid = if (i == 0) null else proxies[i - 1].uuid
                    },
                )

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
                    val showProxy = isChecked && proxies.isNotEmpty()
                    proxyRowView.visibility = if (showProxy) View.VISIBLE else View.GONE
                    if (!isChecked) {
                        selectedProxyUuid = null
                        proxyButton.text = proxyLabels[0]
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
        onResult(
            SandboxInputResult(
                text = text,
                sandbox = switchSandbox?.isChecked == true,
                ephemeral = switchEphemeral?.isChecked == true,
                proxyUuid = if (switchSandbox?.isChecked == true) selectedProxyUuid else null,
            )
        )
    }
}
