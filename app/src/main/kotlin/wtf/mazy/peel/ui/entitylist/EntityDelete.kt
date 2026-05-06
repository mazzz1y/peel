package wtf.mazy.peel.ui.entitylist

import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.util.NotificationUtils

/**
 * Schedules a deletion with snackbar undo. Pending uuids are added to [pendingDeleteSet] so the
 * row disappears immediately; on commit the actual deletion runs in [DataManager.appScope] and
 * the uuid is then cleared from the pending set.
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

    NotificationUtils.showUndoSnackBar(
        activity = activity,
        message = message,
        onUndo = {
            pendingDeleteSet.removeAll(uuids.toSet())
            onPendingChanged()
        },
        onCommit = {
            DataManager.instance.appScope.launch {
                commitDelete(uuids)
                pendingDeleteSet.removeAll(uuids.toSet())
            }
        },
    )
}
