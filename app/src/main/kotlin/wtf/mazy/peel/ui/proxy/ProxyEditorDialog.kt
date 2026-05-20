package wtf.mazy.peel.ui.proxy

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import wtf.mazy.peel.R
import wtf.mazy.peel.model.Proxy

object ProxyEditorDialog {

    fun show(
        activity: Activity,
        existing: Proxy?,
        onSave: (Proxy) -> Unit,
        onDelete: (() -> Unit)? = null,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_proxy, null, false)

        val nameInput = view.findViewById<TextInputEditText>(R.id.proxyName)
        val typeInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.proxyType)
        val hostInput = view.findViewById<TextInputEditText>(R.id.proxyHost)
        val portInput = view.findViewById<TextInputEditText>(R.id.proxyPort)
        val usernameInput = view.findViewById<TextInputEditText>(R.id.proxyUsername)
        val passwordInput = view.findViewById<TextInputEditText>(R.id.proxyPassword)
        val remoteDnsSwitch = view.findViewById<MaterialSwitch>(R.id.proxyRemoteDns)
        val bypassInput = view.findViewById<TextInputEditText>(R.id.proxyBypass)

        val remoteDnsRow = view.findViewById<View>(R.id.proxyRemoteDnsRow)
        val portLayout = view.findViewById<TextInputLayout>(R.id.proxyPortLayout)
        val hostLayout = view.findViewById<TextInputLayout>(R.id.proxyHostLayout)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.proxyNameLayout)

        val typeLabels = listOf(
            activity.getString(R.string.proxy_type_http),
            activity.getString(R.string.proxy_type_https),
            activity.getString(R.string.proxy_type_socks4),
            activity.getString(R.string.proxy_type_socks5),
        )
        val typeValues = listOf(
            Proxy.TYPE_HTTP, Proxy.TYPE_HTTPS,
            Proxy.TYPE_SOCKS4, Proxy.TYPE_SOCKS5,
        )
        typeInput.setAdapter(
            ArrayAdapter(activity, android.R.layout.simple_list_item_1, typeLabels)
        )

        fun applyTypeVisibility(type: Int) {
            val isSocks = type == Proxy.TYPE_SOCKS4 || type == Proxy.TYPE_SOCKS5
            remoteDnsRow.visibility = if (isSocks) View.VISIBLE else View.GONE
        }

        val initial = existing ?: Proxy()
        nameInput.setText(initial.name)
        val initialTypeIdx = typeValues.indexOf(initial.type).coerceAtLeast(0)
        typeInput.setText(typeLabels[initialTypeIdx], false)
        hostInput.setText(initial.host)
        portInput.setText(if (initial.port == 0) "" else initial.port.toString())
        usernameInput.setText(initial.username.orEmpty())
        passwordInput.setText(initial.password.orEmpty())
        remoteDnsSwitch.isChecked = initial.remoteDns
        bypassInput.setText(initial.bypassList.joinToString(", "))
        applyTypeVisibility(initial.type)

        var selectedType = initial.type
        typeInput.setOnItemClickListener { _, _, position, _ ->
            selectedType = typeValues[position]
            applyTypeVisibility(selectedType)
        }

        val titleRes = if (existing == null) R.string.proxy_add else R.string.proxy_edit
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (existing != null && onDelete != null) {
            builder.setNeutralButton(R.string.delete) { _, _ -> onDelete.invoke() }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                nameLayout.error = null
                hostLayout.error = null
                portLayout.error = null

                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    nameLayout.error = activity.getString(R.string.proxy_name_required)
                    return@setOnClickListener
                }

                val bypassList = bypassInput.text?.toString().orEmpty()
                    .split(',', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val host = hostInput.text?.toString()?.trim().orEmpty()
                val portStr = portInput.text?.toString()?.trim().orEmpty()
                val port = portStr.toIntOrNull()
                if (host.isEmpty()) {
                    hostLayout.error = activity.getString(R.string.proxy_host_required)
                    return@setOnClickListener
                }
                if (port == null || port !in 1..65535) {
                    portLayout.error = activity.getString(R.string.proxy_port_invalid)
                    return@setOnClickListener
                }
                val proxy = (existing?.copy() ?: Proxy()).apply {
                    this.name = name
                    this.type = selectedType
                    this.host = host
                    this.port = port
                    this.username = usernameInput.text?.toString()?.takeIf { it.isNotEmpty() }
                    this.password = passwordInput.text?.toString()?.takeIf { it.isNotEmpty() }
                    this.remoteDns = remoteDnsSwitch.isChecked &&
                            (selectedType == Proxy.TYPE_SOCKS4 || selectedType == Proxy.TYPE_SOCKS5)
                    this.bypassList = bypassList
                }

                onSave(proxy)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
