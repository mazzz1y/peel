package wtf.mazy.peel.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager

class GroupPickerDialog : DialogFragment() {

    private var listener: OnGroupSelectedListener? = null
    private var currentGroupUuid: String? = null

    interface OnGroupSelectedListener {
        fun onGroupSelected(groupUuid: String?)
    }

    companion object {
        private const val ARG_CURRENT_GROUP = "current_group"

        fun newInstance(
            currentGroupUuid: String?,
            listener: OnGroupSelectedListener,
        ): GroupPickerDialog {
            return GroupPickerDialog().apply {
                this.listener = listener
                arguments = Bundle().apply {
                    putString(ARG_CURRENT_GROUP, currentGroupUuid)
                }
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (listener == null && context is OnGroupSelectedListener) {
            listener = context
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentGroupUuid = arguments?.getString(ARG_CURRENT_GROUP)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (listener == null) {
            dismissAllowingStateLoss()
            return MaterialAlertDialogBuilder(requireContext()).create()
        }

        val groups = DataManager.instance.sortedGroups
        val names = groups.map { it.title }.toMutableList()
        names.add(getString(R.string.none))

        val uuids = groups.map { it.uuid }.toMutableList<String?>()
        uuids.add(null)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_group)
            .setItems(names.toTypedArray()) { _, which ->
                listener?.onGroupSelected(uuids[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}
