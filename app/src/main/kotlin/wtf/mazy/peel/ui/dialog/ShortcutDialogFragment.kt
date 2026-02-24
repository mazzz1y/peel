package wtf.mazy.peel.ui.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.TrampolineActivity
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ShortcutDialogFragment : DialogFragment() {
    private var webapp: WebApp? = null
    private var bitmap: Bitmap? = null
    private var uiFavicon: ImageView? = null
    private var uiProgressBar: CircularProgressIndicator? = null
    private var uiTitle: EditText? = null
    private var executorService: ExecutorService? = null
    private var faviconFetcherTask: Future<*>? = null
    private var iconPickerLauncher: ActivityResultLauncher<String?>? = null

    companion object {
        private const val ARG_WEBAPP_UUID = "webapp_uuid"

        fun newInstance(webappUuid: String): ShortcutDialogFragment {
            return ShortcutDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_WEBAPP_UUID, webappUuid) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uuid = arguments?.getString(ARG_WEBAPP_UUID)
        if (uuid != null) {
            webapp = DataManager.instance.getWebApp(uuid)
        }

        executorService = Executors.newSingleThreadExecutor()

        iconPickerLauncher =
            registerForActivityResult<String?, Uri?>(
                GetContent(),
                ActivityResultCallback { uri: Uri? ->
                    if (uri == null) return@ActivityResultCallback
                    try {
                        val source =
                            ImageDecoder.createSource(requireActivity().contentResolver, uri)
                        bitmap = ImageDecoder.decodeBitmap(source)
                        applyNewBitmapToDialog()
                    } catch (e: IOException) {
                        showToast(
                            requireActivity(),
                            getString(R.string.icon_not_found),
                            Toast.LENGTH_SHORT
                        )
                        Log.e("ShortcutDialog", "Failed to load icon from URI", e)
                    }
                },
            )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (faviconFetcherTask?.isDone == false) {
            faviconFetcherTask?.cancel(true)
        }
        executorService?.shutdownNow()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = getLayoutInflater().inflate(R.layout.shortcut_dialog, null)

        val dialog =
            MaterialAlertDialogBuilder(requireActivity())
                .setView(view)
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { _, _ ->
                    addShortcutToHomeScreen(bitmap)
                    dismiss()
                }
                .setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
                .create()

        uiTitle = view.findViewById(R.id.websiteTitle)
        uiFavicon = view.findViewById(R.id.favicon)
        uiProgressBar = view.findViewById(R.id.circularProgressBar)

        val btnCustomIcon = view.findViewById<Button>(R.id.btnCustomIcon)
        btnCustomIcon.visibility = View.GONE
        dialog.setOnShowListener { _: DialogInterface? -> loadSavedIconAndTitle() }

        return dialog
    }

    private fun loadSavedIconAndTitle() {
        uiProgressBar?.visibility = View.GONE
        uiFavicon?.visibility = View.VISIBLE

        if (webapp?.title?.isNotEmpty() == true) {
            uiTitle?.setText(webapp?.title)
        }

        val app = webapp ?: return
        bitmap = app.loadIcon()
        uiFavicon?.setImageBitmap(bitmap ?: app.resolveIcon())
    }

    private fun addShortcutToHomeScreen(bitmap: Bitmap?) {
        val app = webapp ?: return
        val activity = requireActivity()
        val intent =
            Intent(activity, TrampolineActivity::class.java).apply {
                putExtra(Const.INTENT_WEBAPP_UUID, app.uuid)
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        val icon =
            if (bitmap != null) {
                IconCompat.createWithAdaptiveBitmap(
                    ShortcutHelper.resizeBitmapForAdaptiveIcon(bitmap)
                )
            } else {
                ShortcutHelper.resolveIcon(app)
            }

        var finalTitle = uiTitle?.text?.toString() ?: ""
        if (finalTitle.isEmpty()) finalTitle = app.title
        if (finalTitle.isEmpty()) finalTitle = "Unknown"

        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) return

        val pinShortcutInfo =
            ShortcutInfoCompat.Builder(activity, app.uuid)
                .setIcon(icon)
                .setShortLabel(finalTitle)
                .setLongLabel(finalTitle)
                .setIntent(intent)
                .build()
        val scManager: ShortcutManager =
            App.appContext.getSystemService(ShortcutManager::class.java)
        if (scManager.pinnedShortcuts.any { it.id == pinShortcutInfo.id }) {
            ShortcutManagerCompat.updateShortcuts(activity, listOf(pinShortcutInfo))
            showToast(activity, getString(R.string.shortcut_updated))
        } else {
            ShortcutManagerCompat.requestPinShortcut(activity, pinShortcutInfo, null)
        }
    }

    private fun prepareFailedUI() {
        val app = webapp ?: return
        showFailedMessage()
        if (app.title.isNotEmpty()) {
            uiTitle?.setText(app.title)
        }

        uiTitle?.requestFocus()

        uiProgressBar?.visibility = View.GONE
        uiFavicon?.visibility = View.VISIBLE
    }

    private fun showFailedMessage() {
        val title = webapp?.title ?: ""
        showToast(
            requireActivity(),
            getString(R.string.icon_fetch_failed_line1, title) +
                    getString(R.string.icon_fetch_failed_line2) +
                    getString(R.string.icon_fetch_failed_line3),
        )
    }

    private fun applyNewBitmapToDialog() {
        if (bitmap == null) {
            prepareFailedUI()
            return
        }
        uiFavicon?.setImageBitmap(bitmap)
        uiProgressBar?.visibility = View.GONE
        uiFavicon?.visibility = View.VISIBLE
    }
}
