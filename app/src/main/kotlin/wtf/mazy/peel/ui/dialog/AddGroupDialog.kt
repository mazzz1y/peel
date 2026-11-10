package wtf.mazy.peel.ui.dialog

import android.app.Activity
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ProxyRoute
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.proxy.ProxyOptions

/** [onResult] gets the group unsaved; storing it is the caller's. */
fun Activity.showAddGroupDialog(onResult: (WebAppGroup) -> Unit) {
    lateinit var controls: SandboxControls<ProxyRoute>
    showInputDialogRaw(
        InputDialogConfig(
            titleRes = R.string.add_group,
            hintRes = R.string.group_name_hint,
            initialSelection = InitialSelection.CURSOR_AT_END,
            positiveRes = R.string.ok,
            extraContent = { container ->
                controls = SandboxControls(
                    this,
                    container,
                    ProxyOptions.forGroup(this, DataManager.proxies),
                    ProxyRoute.Direct,
                )
            },
        ),
    ) { input, _ ->
        onResult(WebAppGroup(title = input.text.toString().trim(), isolation = controls.isolation))
    }
}
