package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.widget.LinearLayout
import androidx.annotation.DimenRes
import androidx.core.view.isVisible
import com.google.android.material.R as MaterialR
import com.google.android.material.materialswitch.MaterialSwitch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.Isolation
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.model.ProxyRoute
import wtf.mazy.peel.ui.bindDropdown
import wtf.mazy.peel.ui.common.themeResource
import wtf.mazy.peel.ui.proxy.ProxyOption
import wtf.mazy.peel.ui.proxy.ProxyOptions

/**
 * [afterRow] spaces the controls as the next row of a list rather than as the first row under
 * the input field.
 */
class SandboxControls<P : ProxyChoice>(
    private val activity: Activity,
    container: LinearLayout,
    private var proxyOptions: List<ProxyOption<P>>,
    private var defaultProxy: P,
    afterRow: Boolean = false,
) {
    private val switchSandbox = MaterialSwitch(container.context).apply {
        text = activity.getString(R.string.enable_sandbox)
        setTextAppearance(container.context.themeResource(MaterialR.attr.textAppearanceBodyLarge))
        layoutParams = rowParams(if (afterRow) R.dimen.dialog_row_gap else R.dimen.dialog_content_gap)
    }
    private val switchEphemeral = MaterialSwitch(container.context).apply {
        text = activity.getString(R.string.ephemeral_sandbox)
        setTextAppearance(container.context.themeResource(MaterialR.attr.textAppearanceBodyLarge))
        isVisible = false
        layoutParams = rowParams(R.dimen.dialog_row_gap)
    }
    private val proxyRow: DialogChoiceRow
    private var selectedProxy = defaultProxy

    val isolation: Isolation<P>
        get() = if (switchSandbox.isChecked) {
            Isolation.Sandboxed(switchEphemeral.isChecked, selectedProxy)
        } else {
            Isolation.Shared
        }

    init {
        container.addView(switchSandbox)
        container.addView(switchEphemeral)
        proxyRow = DialogChoiceRow(container, R.string.proxy, R.dimen.dialog_row_gap)
        proxyRow.view.isVisible = false
        showSelectedProxy()
        proxyRow.button.bindDropdown(
            itemsProvider = { proxyOptions.map { it.label } },
            currentIndex = { proxyOptions.indexOf(ProxyOptions.current(proxyOptions, selectedProxy)) },
            onSelected = { selectedProxy = proxyOptions[it].choice },
            buttonItemsProvider = { proxyOptions.map { it.routeLabel } },
        )
        switchSandbox.setOnCheckedChangeListener { _, isChecked ->
            switchEphemeral.isVisible = isChecked
            if (!isChecked) {
                switchEphemeral.isChecked = false
                selectedProxy = defaultProxy
                showSelectedProxy()
            }
            renderProxyRow()
        }
    }

    fun setProxyOptions(options: List<ProxyOption<P>>, default: P) {
        proxyOptions = options
        defaultProxy = default
        if (options.none { it.choice == selectedProxy }) selectedProxy = default
        showSelectedProxy()
        renderProxyRow()
    }

    private fun showSelectedProxy() {
        proxyRow.button.text = ProxyOptions.current(proxyOptions, selectedProxy).routeLabel
    }

    private fun renderProxyRow() {
        proxyRow.view.isVisible = switchSandbox.isChecked && proxyOptions.any { it.choice is ProxyRoute.Via }
    }

    private fun rowParams(@DimenRes topMargin: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = activity.resources.getDimensionPixelSize(topMargin) }
}
