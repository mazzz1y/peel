package wtf.mazy.peel.ui.settings

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.model.SandboxOwner
import wtf.mazy.peel.model.proxyOrNull
import wtf.mazy.peel.model.withExistingProxy
import wtf.mazy.peel.model.withProxy
import wtf.mazy.peel.ui.common.Draft
import wtf.mazy.peel.ui.proxy.ProxyOption
import wtf.mazy.peel.ui.proxy.ProxyOptions
import wtf.mazy.peel.ui.proxy.ProxyPickerDialog

class ProxyPickerController<T : SandboxOwner<T, P>, P : ProxyChoice>(
    private val activity: AppCompatActivity,
    private val draft: Draft<T>,
    private val proxyRow: View,
    private val proxyButton: MaterialButton,
    private val options: (T) -> List<ProxyOption<P>>,
) {

    fun setup() {
        proxyButton.setOnClickListener { showPicker() }
        refresh()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataManager.state.collect { refresh() }
            }
        }
    }

    fun refresh() {
        val proxyUuids = DataManager.proxies.mapTo(mutableSetOf()) { it.uuid }
        draft.update { it.withExistingProxy(proxyUuids) }
        val owner = draft.value
        val proxy = owner.isolation.proxyOrNull?.takeIf { proxyUuids.isNotEmpty() }
        proxyRow.visibility = if (proxy != null) View.VISIBLE else View.GONE
        if (proxy == null) return
        proxyButton.text = ProxyOptions.current(options(owner), proxy).routeLabel
    }

    private fun showPicker() {
        val owner = draft.value
        val proxy = owner.isolation.proxyOrNull ?: return
        ProxyPickerDialog.show(activity, options(owner), proxy) { picked ->
            draft.update { it.withProxy(picked) }
            refresh()
        }
    }
}
