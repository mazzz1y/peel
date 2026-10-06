package wtf.mazy.peel.ui.settings

import android.app.Activity
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import wtf.mazy.peel.R
import wtf.mazy.peel.model.UrlRule
import wtf.mazy.peel.ui.dialog.dismissOnDestroyOf

object UrlRuleDialog {

    private val kinds = listOf(UrlRule.Kind.DOMAIN, UrlRule.Kind.URL_PREFIX, UrlRule.Kind.URL_REGEX)

    @StringRes
    fun kindName(kind: UrlRule.Kind): Int = when (kind) {
        UrlRule.Kind.DOMAIN -> R.string.url_rule_kind_domain
        UrlRule.Kind.URL_PREFIX -> R.string.url_rule_kind_url_prefix
        UrlRule.Kind.URL_REGEX -> R.string.url_rule_kind_url_regex
        UrlRule.Kind.HOST_REGEX -> R.string.url_rule_kind_host_regex
    }

    @StringRes
    private fun example(kind: UrlRule.Kind): Int = when (kind) {
        UrlRule.Kind.DOMAIN -> R.string.url_rule_example_domain
        UrlRule.Kind.URL_PREFIX -> R.string.url_rule_example_url_prefix
        UrlRule.Kind.URL_REGEX, UrlRule.Kind.HOST_REGEX -> R.string.url_rule_example_url_regex
    }

    @StringRes
    private fun help(kind: UrlRule.Kind): Int = when (kind) {
        UrlRule.Kind.DOMAIN -> R.string.url_rule_help_domain
        UrlRule.Kind.URL_PREFIX -> R.string.url_rule_help_url_prefix
        UrlRule.Kind.URL_REGEX, UrlRule.Kind.HOST_REGEX -> R.string.url_rule_help_url_regex
    }

    @StringRes
    private fun error(problem: UrlRule.Problem): Int = when (problem) {
        UrlRule.Problem.EMPTY -> R.string.url_rule_error_empty
        UrlRule.Problem.INVALID_HOST -> R.string.url_rule_error_invalid_host
        UrlRule.Problem.HAS_PATH -> R.string.url_rule_error_has_path
        UrlRule.Problem.NEEDS_PATH -> R.string.url_rule_error_needs_path
        UrlRule.Problem.INVALID_REGEX -> R.string.url_rule_error_invalid_regex
    }

    fun show(
        activity: Activity,
        @StringRes titleRes: Int,
        existing: UrlRule?,
        prefill: String = existing?.value.orEmpty(),
        onCommit: (UrlRule) -> Unit,
    ) {
        if (existing is UrlRule.HostRegex) {
            return show(activity, titleRes, existing.asUrlRegex, onCommit = onCommit)
        }
        val context = activity
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_url_rule, null, false)
        val kindInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.urlRuleKind)
        val valueLayout = view.findViewById<TextInputLayout>(R.id.urlRuleValueLayout)
        val valueInput = view.findViewById<TextInputEditText>(R.id.urlRuleValue)

        val kindLabels = kinds.map { context.getString(kindName(it)) }
        kindInput.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, kindLabels))

        var selectedKind = existing?.kind ?: UrlRule.Kind.DOMAIN

        fun applyKind() {
            valueLayout.error = null
            valueLayout.hint = context.getString(kindName(selectedKind))
            valueLayout.placeholderText = context.getString(example(selectedKind))
            valueLayout.helperText = context.getString(help(selectedKind))
        }

        kindInput.setText(kindLabels[kinds.indexOf(selectedKind)], false)
        kindInput.setOnItemClickListener { _, _, position, _ ->
            selectedKind = kinds[position]
            applyKind()
        }
        applyKind()

        valueInput.setText(prefill)
        valueInput.setSelection(valueInput.text?.length ?: 0)
        valueInput.doAfterTextChanged { valueLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun commit() {
            val input = valueInput.text?.toString().orEmpty()
            val problem = UrlRule.problem(selectedKind, input)
            if (problem != null) {
                valueLayout.error = context.getString(error(problem))
                return
            }
            onCommit(UrlRule.canonical(selectedKind, input))
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { commit() }
        }
        valueInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            commit()
            true
        }
        dialog.dismissOnDestroyOf(activity)
        dialog.show()
        valueInput.requestFocus()
    }
}
