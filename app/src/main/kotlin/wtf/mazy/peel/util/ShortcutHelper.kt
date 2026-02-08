package wtf.mazy.peel.util

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.util.NotificationUtils.showToast
import java.io.File
import kotlin.math.min
import androidx.core.graphics.scale

object ShortcutHelper {
    private const val ADAPTIVE_ICON_SIZE = 108
    private const val LOGO_SIZE = 48

    fun resizeBitmapForAdaptiveIcon(bitmap: Bitmap): Bitmap {
        val density = App.appContext.resources.displayMetrics.density
        val iconSizePx = (ADAPTIVE_ICON_SIZE * density).toInt()
        val logoSizePx = (LOGO_SIZE * density).toInt()

        val scale = min(logoSizePx.toFloat() / bitmap.width, logoSizePx.toFloat() / bitmap.height)
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val scaledBitmap = bitmap.scale(scaledWidth, scaledHeight)

        val finalBitmap = createBitmap(iconSizePx, iconSizePx)
        val canvas = Canvas(finalBitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.color = Color.WHITE
        canvas.drawRect(0f, 0f, iconSizePx.toFloat(), iconSizePx.toFloat(), bgPaint)

        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val left = ((iconSizePx - scaledWidth) / 2).toFloat()
        val top = ((iconSizePx - scaledHeight) / 2).toFloat()
        canvas.drawBitmap(scaledBitmap, left, top, drawPaint)

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return finalBitmap
    }

    fun createShortcut(webapp: WebApp, activity: Activity) {
        val intent =
            WebViewLauncher.createWebViewIntent(webapp, activity)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: return

        val icon =
            if (webapp.hasCustomIcon && webapp.customIconPath != null) {
                try {
                    val iconFile = File(webapp.customIconPath!!)
                    if (iconFile.exists()) {
                        val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                        if (bitmap != null) {
                            val resizedBitmap = resizeBitmapForAdaptiveIcon(bitmap)
                            IconCompat.createWithAdaptiveBitmap(resizedBitmap)
                        } else {
                            val letterBitmap = LetterIconGenerator.generateForAdaptiveIcon(webapp.title, webapp.baseUrl)
                            IconCompat.createWithAdaptiveBitmap(letterBitmap)
                        }
                    } else {
                        val letterBitmap = LetterIconGenerator.generateForAdaptiveIcon(webapp.title, webapp.baseUrl)
                        IconCompat.createWithAdaptiveBitmap(letterBitmap)
                    }
                } catch (e: Exception) {
                    Log.w("ShortcutHelper", "Failed to load saved icon", e)
                    val letterBitmap = LetterIconGenerator.generateForAdaptiveIcon(webapp.title, webapp.baseUrl)
                    IconCompat.createWithAdaptiveBitmap(letterBitmap)
                }
            } else {
                val letterBitmap = LetterIconGenerator.generateForAdaptiveIcon(webapp.title, webapp.baseUrl)
                IconCompat.createWithAdaptiveBitmap(letterBitmap)
            }

        val finalTitle = webapp.title.ifEmpty { "Unknown" }

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            val pinShortcutInfo =
                ShortcutInfoCompat.Builder(activity, webapp.uuid)
                    .setIcon(icon)
                    .setShortLabel(finalTitle)
                    .setLongLabel(finalTitle)
                    .setIntent(intent)
                    .build()

            val newScId = pinShortcutInfo.id
            val scManager: ShortcutManager =
                App.appContext.getSystemService(ShortcutManager::class.java)

            if (scManager.pinnedShortcuts.none { it.id == newScId }) {
                ShortcutManagerCompat.requestPinShortcut(activity, pinShortcutInfo, null)
            } else {
                showToast(
                    activity,
                    activity.getString(R.string.shortcut_already_exists),
                    Toast.LENGTH_SHORT,
                )
            }
        }
    }
}
