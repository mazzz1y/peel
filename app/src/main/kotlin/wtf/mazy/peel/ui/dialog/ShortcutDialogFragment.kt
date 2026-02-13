package wtf.mazy.peel.ui.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.shortcut.ShortcutHelper
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.WebViewLauncher

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
                            Toast.LENGTH_SHORT)
                        Log.e("ShortcutDialog", "Failed to load icon from URI", e)
                    }
                },
            )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (faviconFetcherTask?.isDone == false) {
            faviconFetcherTask?.cancel(true)
            Log.d("CLEANUP", "Cancelled running favicon fetcher")
        }
        executorService?.shutdownNow()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = getLayoutInflater().inflate(R.layout.shortcut_dialog, null)

        val dialog =
            AlertDialog.Builder(requireActivity())
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

        if (webapp?.hasCustomIcon == true) {
            try {
                bitmap = BitmapFactory.decodeFile(webapp?.iconFile?.absolutePath)
                if (bitmap != null) {
                    uiFavicon?.setImageBitmap(bitmap)
                } else {
                    setLetterIconFallback()
                }
            } catch (e: Exception) {
                Log.w("ShortcutDialog", "Failed to load saved icon", e)
                setLetterIconFallback()
            }
        } else {
            setLetterIconFallback()
        }
    }

    private fun setLetterIconFallback() {
        val app = webapp ?: return
        val density = resources.displayMetrics.density
        val sizePx = (48 * density).toInt()
        uiFavicon?.setImageBitmap(LetterIconGenerator.generate(app.title, app.baseUrl, sizePx))
    }

    private fun addShortcutToHomeScreen(bitmap: Bitmap?) {
        val app = webapp ?: return
        val intent = WebViewLauncher.createWebViewIntent(app, requireActivity()) ?: return
        val icon =
            if (bitmap != null) {
                val resizedBitmap = ShortcutHelper.resizeBitmapForAdaptiveIcon(bitmap)
                IconCompat.createWithAdaptiveBitmap(resizedBitmap)
            } else {
                val letterBitmap =
                    LetterIconGenerator.generateForAdaptiveIcon(app.title, app.baseUrl)
                IconCompat.createWithAdaptiveBitmap(letterBitmap)
            }

        var finalTitle = uiTitle?.text?.toString() ?: ""
        if (finalTitle.isEmpty()) finalTitle = app.title
        if (finalTitle.isEmpty()) finalTitle = "Unknown"

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(requireActivity())) {
            val pinShortcutInfo =
                ShortcutInfoCompat.Builder(requireActivity(), app.uuid)
                    .setIcon(icon)
                    .setShortLabel(finalTitle)
                    .setLongLabel(finalTitle)
                    .setIntent(intent)
                    .build()
            val newScId = pinShortcutInfo.id
            val scManager: ShortcutManager =
                App.appContext.getSystemService(ShortcutManager::class.java)
            if (scManager.pinnedShortcuts.none { it.id == newScId }) {
                ShortcutManagerCompat.requestPinShortcut(requireActivity(), pinShortcutInfo, null)
            } else {
                showToast(requireActivity(), getString(R.string.shortcut_already_exists))
            }
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
