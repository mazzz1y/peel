package wtf.mazy.peel.ui.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.gecko.GeckoRuntimeProvider.awaitNullable
import wtf.mazy.peel.shortcut.LetterIconGenerator
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ExtensionIconCache {

    const val ICON_SIZE_PX = 128

    private const val TAG = "ExtensionIconCache"
    private const val DIR = "ext_icons"

    private val unsafeIdChars = Regex("[^a-zA-Z0-9._@-]")

    fun load(context: Context, id: String): Bitmap? {
        val file = iconFile(context, id)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun save(context: Context, id: String, bitmap: Bitmap) {
        val file = iconFile(context, id)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun delete(context: Context, id: String) {
        iconFile(context, id).delete()
    }

    fun bind(imageView: ImageView, context: Context, id: String, displayName: String) {
        val cached = load(context, id)
        if (cached != null) {
            imageView.setImageBitmap(cached)
        } else {
            imageView.setImageBitmap(
                LetterIconGenerator.generate(displayName, displayName, ICON_SIZE_PX)
            )
        }
    }

    suspend fun refreshFromExtension(
        context: Context,
        ext: WebExtension,
        sizePx: Int = ICON_SIZE_PX,
    ) {
        try {
            val bitmap = ext.metaData.icon.getBitmap(sizePx).awaitNullable() ?: return
            save(context, ext.id, bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "refreshFromExtension failed for ${ext.id}", e)
        }
    }

    fun downloadAndCache(context: Context, id: String, iconUrl: String): Bitmap? {
        return try {
            val conn = URL(iconUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.connect()
            if (conn.responseCode != 200) {
                Log.w(TAG, "downloadAndCache $id: HTTP ${conn.responseCode}")
                return null
            }
            val bitmap = BitmapFactory.decodeStream(conn.inputStream) ?: return null
            save(context, id, bitmap)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "downloadAndCache failed for $id", e)
            null
        }
    }

    private fun iconFile(context: Context, id: String): File {
        val safeId = id.replace(unsafeIdChars, "_")
        return File(File(context.filesDir, DIR), "$safeId.png")
    }
}
