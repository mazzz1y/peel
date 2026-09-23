package wtf.mazy.peel.ui.entitylist

import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.showUndoSnackBar

/**
 * Schedules a deletion with snackbar undo. Pending uuids are added to [pendingDeleteSet] so the
 * row disappears immediately; on commit the actual deletion runs in [App.appScope] — the
 * snackbar dismisses after the activity is gone — and the uuid is then cleared from the set.
 */
fun scheduleEntityDelete(
    activity: AppCompatActivity,
    uuids: List<String>,
    message: String,
    pendingDeleteSet: MutableSet<String>,
    onPendingChanged: () -> Unit,
    commitDelete: suspend (List<String>) -> Unit,
) {
    if (uuids.isEmpty()) return
    pendingDeleteSet.addAll(uuids)
    onPendingChanged()

    activity.showUndoSnackBar(
        message = message,
        onUndo = {
            pendingDeleteSet.removeAll(uuids.toSet())
            onPendingChanged()
        },
        onCommit = {
            App.appScope.launch {
                try {
                    commitDelete(uuids)
                } finally {
                    pendingDeleteSet.removeAll(uuids.toSet())
                }
            }
        },
    )
}
