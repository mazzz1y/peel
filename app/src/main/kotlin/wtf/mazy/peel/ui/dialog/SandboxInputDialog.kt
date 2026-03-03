package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import com.google.android.material.materialswitch.MaterialSwitch
import wtf.mazy.peel.R

data class SandboxInputResult(
    val text: String,
    val sandbox: Boolean,
    val ephemeral: Boolean,
)

fun Activity.showSandboxInputDialog(
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    onResult: (SandboxInputResult) -> Unit,
) {
    var switchSandbox: MaterialSwitch? = null
    var switchEphemeral: MaterialSwitch? = null

    buildInputDialog(
        titleRes = titleRes,
        hintRes = hintRes,
        inputType = inputType,
        positiveRes = R.string.ok,
        extraContent = { container ->
            switchSandbox = MaterialSwitch(container.context).apply {
                text = getString(R.string.enable_sandbox)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (16 * resources.displayMetrics.density).toInt() }
            }
            switchEphemeral = MaterialSwitch(container.context).apply {
                text = getString(R.string.ephemeral_sandbox)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            }
            switchSandbox?.setOnCheckedChangeListener { _, isChecked ->
                switchEphemeral?.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (!isChecked) switchEphemeral?.isChecked = false
            }
            container.addView(switchSandbox)
            container.addView(switchEphemeral)
        },
    ) { input, _ ->
        val text = input.text?.toString()?.trim() ?: return@buildInputDialog
        if (text.isBlank()) return@buildInputDialog
        onResult(
            SandboxInputResult(
                text = text,
                sandbox = switchSandbox?.isChecked == true,
                ephemeral = switchEphemeral?.isChecked == true,
            )
        )
    }
}
