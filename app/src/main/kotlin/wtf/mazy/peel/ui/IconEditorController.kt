package wtf.mazy.peel.ui

import android.net.Uri
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.util.NotificationUtils.showToast
import java.io.IOException

class IconEditorController(
    private val activity: ComponentActivity,
    private val iconView: () -> ImageView,
    private val ownerProvider: () -> IconOwner?,
) {
    private val sizePx = (activity.resources.displayMetrics.density * 96).toInt()

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
            launcher.launch("image/*")
        } catch (_: Exception) {
            showToast(activity, activity.getString(R.string.icon_not_found), Toast.LENGTH_SHORT)
        }
    }

    private fun removeIcon(owner: IconOwner) {
        owner.deleteIcon()
        refreshIcon()
    }

    private fun handleSelectedIcon(uri: Uri) {
        try {
            @Suppress("DEPRECATION")
            val bitmap = MediaStore.Images.Media.getBitmap(activity.contentResolver, uri)
            if (bitmap != null) {
                ownerProvider()?.let {
                    it.saveIcon(bitmap)
                    refreshIcon()
                }
            }
        } catch (_: IOException) {
            showToast(activity, activity.getString(R.string.icon_not_found), Toast.LENGTH_SHORT)
        }
    }
}
