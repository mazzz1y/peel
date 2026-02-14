package wtf.mazy.peel.shortcut

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import kotlin.math.min
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.NotificationUtils.showToast
import wtf.mazy.peel.util.WebViewLauncher

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
        val intent = buildShortcutIntent(webapp, activity) ?: return
        val icon = resolveIcon(webapp)
        val finalTitle = webapp.title.ifEmpty { "Unknown" }

        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) return

        val pinShortcutInfo = buildShortcutInfo(activity, webapp.uuid, finalTitle, icon, intent)
        val scManager: ShortcutManager =
            App.appContext.getSystemService(ShortcutManager::class.java)

        if (scManager.pinnedShortcuts.none { it.id == pinShortcutInfo.id }) {
            ShortcutManagerCompat.requestPinShortcut(activity, pinShortcutInfo, null)
        } else {
            showToast(
                activity,
                activity.getString(R.string.shortcut_already_exists),
                Toast.LENGTH_SHORT,
            )
        }
    }

    fun updatePinnedShortcut(webapp: WebApp, context: Context) {
        val scManager = context.getSystemService(ShortcutManager::class.java)
        if (scManager.pinnedShortcuts.none { it.id == webapp.uuid }) return

        val intent = buildShortcutIntent(webapp, context) ?: return
        val icon = resolveIcon(webapp)
        val finalTitle = webapp.title.ifEmpty { "Unknown" }

        val updated = buildShortcutInfo(context, webapp.uuid, finalTitle, icon, intent)
        ShortcutManagerCompat.updateShortcuts(context, listOf(updated))
    }

    fun resolveIcon(webapp: WebApp): IconCompat {
        if (webapp.hasCustomIcon) {
            try {
                val bitmap = BitmapFactory.decodeFile(webapp.iconFile.absolutePath)
                if (bitmap != null) {
                    return IconCompat.createWithAdaptiveBitmap(resizeBitmapForAdaptiveIcon(bitmap))
                }
            } catch (_: Exception) {
            }
        }
        return IconCompat.createWithAdaptiveBitmap(
            LetterIconGenerator.generateForAdaptiveIcon(webapp.title, webapp.baseUrl))
    }

    private fun buildShortcutIntent(webapp: WebApp, context: Context): Intent? {
        return WebViewLauncher.createWebViewIntent(webapp, context)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private fun buildShortcutInfo(
        context: Context,
        id: String,
        title: String,
        icon: IconCompat,
        intent: Intent,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setIcon(icon)
            .setShortLabel(title)
            .setLongLabel(title)
            .setIntent(intent)
            .build()
}
