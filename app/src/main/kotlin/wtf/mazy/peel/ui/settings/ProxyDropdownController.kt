package wtf.mazy.peel.ui.settings

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.Proxy
import wtf.mazy.peel.model.SandboxOwner
import wtf.mazy.peel.ui.bindDropdown

class ProxyDropdownController(
    private val activity: AppCompatActivity,
    private val owner: SandboxOwner,
    private val proxyRow: View,
    private val proxyButton: MaterialButton,
) {

    private var proxies: List<Proxy> = emptyList()

    fun setup() {
        refresh()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataManager.instance.state.collect { refresh() }
            }
        }
    }

    fun refresh() {
        proxies = DataManager.instance.getProxies().sortedBy { it.displayName().lowercase() }

        val visible = owner.isUseContainer && proxies.isNotEmpty()
        proxyRow.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            if (proxies.isEmpty() && owner.proxyUuid != null) owner.proxyUuid = null
            return
        }

        val directLabel = activity.getString(R.string.proxy_direct)
        val labels = listOf(directLabel) + proxies.map { it.displayName() }

        if (owner.proxyUuid != null && proxies.none { it.uuid == owner.proxyUuid }) {
            owner.proxyUuid = null
        }

        proxyButton.bindDropdown(
            items = labels,
            currentIndex = {
                val uuid = owner.proxyUuid
                if (uuid == null) 0
                else proxies.indexOfFirst { it.uuid == uuid }.let { idx ->
                    if (idx >= 0) idx + 1 else 0
                }
            },
            onSelected = { i ->
                owner.proxyUuid = if (i == 0) null else proxies[i - 1].uuid
            },
        )
    }
}
