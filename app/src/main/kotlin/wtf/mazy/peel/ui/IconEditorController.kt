package wtf.mazy.peel.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.util.toast

class IconEditorController(
    private val activity: ComponentActivity,
    private val iconView: () -> ImageView,
    private val ownerProvider: () -> IconOwner?,
    private val onPickerOpening: () -> Unit = {},
) {
    private val sizePx = (activity.resources.displayMetrics.density * 96).toInt()
    private var pendingSave: Job? = null

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { handleSelectedIcon(it) }
        }

    fun onIconTap() {
        val owner = ownerProvider() ?: return
        if (owner.hasCustomIcon) {
            MaterialAlertDialogBuilder(activity)
                .setItems(
                    arrayOf(
                        activity.getString(R.string.icon_update),
                        activity.getString(R.string.icon_remove),
                    )
                ) { _, which ->
                    when (which) {
                        0 -> launchPicker()
                        1 -> removeIcon(owner)
                    }
                }
                .show()
        } else {
            launchPicker()
        }
    }

    fun refreshIcon() {
        val owner = ownerProvider() ?: return
        iconView().setImageBitmap(owner.resolveIcon(sizePx))
    }

    private fun launchPicker() {
        try {
            onPickerOpening()
            launcher.launch("image/*")
        } catch (_: Exception) {
            activity.toast(R.string.icon_not_found)
        }
    }

    private fun removeIcon(owner: IconOwner) {
        owner.deleteIcon()
        refreshIcon()
    }

    /** Saves [bitmap] as [owner]'s icon, past the screen's own lifetime; [awaitSaved] waits for it. */
    fun saveIcon(owner: IconOwner, bitmap: Bitmap) {
        save { owner.saveIcon(bitmap) }
    }

    suspend fun awaitSaved() {
        pendingSave?.join()
    }

    private fun handleSelectedIcon(uri: Uri) {
        val owner = ownerProvider() ?: return
        save {
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri))
            }.getOrNull()
            if (bitmap == null) {
                withContext(Dispatchers.Main) { activity.toast(R.string.icon_not_found) }
            } else {
                owner.saveIcon(bitmap)
            }
        }
    }

    // A chosen icon is the user's edit, so it is written even if the screen goes away meanwhile.
    private fun save(write: suspend () -> Unit) {
        val previous = pendingSave
        pendingSave = activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.IO) {
                previous?.join()
                write()
            }
            refreshIcon()
        }
    }
}
