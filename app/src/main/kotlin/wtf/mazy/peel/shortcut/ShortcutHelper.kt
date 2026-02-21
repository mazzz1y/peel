package wtf.mazy.peel.shortcut

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlin.math.min
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.TrampolineActivity
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast

object ShortcutHelper {
    private const val ADAPTIVE_ICON_SIZE = 108
    private const val LOGO_SIZE = 48

    fun createShortcut(owner: IconOwner, activity: Activity) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) return

        val intent = buildIntent(owner, activity)
        val icon = resolveIcon(owner)
        val title = owner.title.ifEmpty { "Unknown" }

        val info = buildShortcutInfo(activity, owner.uuid, title, icon, intent)
        val scManager = App.appContext.getSystemService(ShortcutManager::class.java)

        if (scManager.pinnedShortcuts.none { it.id == info.id }) {
            ShortcutManagerCompat.requestPinShortcut(activity, info, null)
        } else {
            showToast(activity, activity.getString(R.string.shortcut_already_exists), Toast.LENGTH_SHORT)
        }
    }

    fun updatePinnedShortcut(webapp: WebApp, context: Context) {
        val scManager = context.getSystemService(ShortcutManager::class.java)
        if (scManager.pinnedShortcuts.none { it.id == webapp.uuid }) return

        val intent = buildIntent(webapp, context)
        val icon = resolveIcon(webapp)
        val title = webapp.title.ifEmpty { "Unknown" }

        val updated = buildShortcutInfo(context, webapp.uuid, title, icon, intent)
        ShortcutManagerCompat.updateShortcuts(context, listOf(updated))
    }

    fun resolveIcon(owner: IconOwner): IconCompat {
        val bitmap = owner.loadIcon()
        if (bitmap != null) {
            return IconCompat.createWithAdaptiveBitmap(resizeBitmapForAdaptiveIcon(bitmap))
        }
        return IconCompat.createWithAdaptiveBitmap(
            LetterIconGenerator.generateForAdaptiveIcon(owner.title, owner.letterIconSeed))
    }

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

    private fun buildIntent(owner: IconOwner, context: Context): Intent {
        return Intent(context, TrampolineActivity::class.java).apply {
            when (owner) {
                is WebApp -> putExtra(Const.INTENT_WEBAPP_UUID, owner.uuid)
                is WebAppGroup -> putExtra(Const.INTENT_GROUP_UUID, owner.uuid)
            }
            data = "peel://${owner.uuid}".toUri()
            action = Intent.ACTION_VIEW
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
