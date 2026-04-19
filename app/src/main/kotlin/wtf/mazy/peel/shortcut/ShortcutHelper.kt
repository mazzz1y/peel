package wtf.mazy.peel.shortcut

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.google.android.material.slider.Slider
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.TrampolineActivity
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.dialog.InitialSelection
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const
import kotlin.math.min

object ShortcutHelper {
    private const val ADAPTIVE_ICON_SIZE = 108
    private const val LOGO_SIZE = 48
    private const val ADAPTIVE_SAFE_ZONE = 72

    fun createShortcut(owner: IconOwner, activity: Activity) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) return
        showCreateDialog(owner, activity) { name, logoSizeDp ->
            pinShortcut(owner, activity, resolveTitle(name, owner), logoSizeDp)
        }
    }

    fun updatePinnedShortcut(webapp: WebApp, context: Context) {
        val scManager = context.getSystemService(ShortcutManager::class.java)
        if (scManager.pinnedShortcuts.none { it.id == webapp.uuid }) return

        val intent = buildIntent(webapp, context)
        val icon = resolveIcon(webapp)
        val title = resolveTitle("", webapp)

        val updated = buildShortcutInfo(context, webapp.uuid, title, icon, intent)
        ShortcutManagerCompat.updateShortcuts(context, listOf(updated))
    }

    private fun showCreateDialog(
        owner: IconOwner,
        activity: Activity,
        onConfirm: (name: String, logoSizeDp: Int) -> Unit,
    ) {
        val hasCustomIcon = owner.loadIcon() != null
        var preview: ImageView? = null
        var slider: Slider? = null
        var lastPreviewBitmap: Bitmap? = null

        fun renderPreview(logoSizeDp: Int) {
            val full = buildAdaptiveBitmap(owner, logoSizeDp)
            val cropped = cropToSafeZone(full)
            preview?.setImageBitmap(cropped)
            if (full !== cropped) full.recycle()
            lastPreviewBitmap?.takeIf { it !== cropped && !it.isRecycled }?.recycle()
            lastPreviewBitmap = cropped
        }

        fun currentLogoSize(): Int =
            if (hasCustomIcon) slider?.value?.toInt() ?: LOGO_SIZE else LOGO_SIZE

        activity.showInputDialogRaw(
            InputDialogConfig(
                titleRes = R.string.create_shortcut,
                hintRes = R.string.name,
                prefill = owner.title,
                positiveRes = R.string.create,
                initialSelection = InitialSelection.CURSOR_AT_END,
                extraContent = { container ->
                    val extras = LayoutInflater.from(container.context)
                        .inflate(R.layout.dialog_shortcut_extras, container, false)
                    preview = extras.findViewById(R.id.preview)
                    slider = extras.findViewById(R.id.slider)
                    if (hasCustomIcon) {
                        slider?.addOnChangeListener { _, value, _ ->
                            renderPreview(value.toInt())
                        }
                    } else {
                        slider?.visibility = View.GONE
                    }
                    renderPreview(currentLogoSize())
                    container.addView(extras, 0)
                },
            ),
        ) { nameInput, _ ->
            onConfirm(nameInput.text.toString().trim(), currentLogoSize())
        }
    }

    private fun pinShortcut(
        owner: IconOwner,
        activity: Activity,
        title: String,
        logoSizeDp: Int = LOGO_SIZE,
    ) {
        val intent = buildIntent(owner, activity)
        val icon = resolveIcon(owner, logoSizeDp)
        val info = buildShortcutInfo(activity, owner.uuid, title, icon, intent)
        ShortcutManagerCompat.requestPinShortcut(activity, info, null)
        ShortcutManagerCompat.updateShortcuts(activity, listOf(info))
    }

    private fun resolveTitle(name: String, owner: IconOwner): String =
        name.ifEmpty { owner.title.ifEmpty { App.appContext.getString(R.string.untitled_shortcut) } }

    private fun resolveIcon(owner: IconOwner, logoSizeDp: Int = LOGO_SIZE): IconCompat =
        IconCompat.createWithAdaptiveBitmap(buildAdaptiveBitmap(owner, logoSizeDp))

    private fun buildAdaptiveBitmap(owner: IconOwner, logoSizeDp: Int): Bitmap {
        val bitmap = owner.loadIcon()
        return if (bitmap != null) {
            resizeBitmapForAdaptiveIcon(bitmap, logoSizeDp)
        } else {
            LetterIconGenerator.generateForAdaptiveIcon(owner.title, owner.letterIconSeed)
        }
    }

    private fun cropToSafeZone(bitmap: Bitmap): Bitmap {
        val inset = bitmap.width * (ADAPTIVE_ICON_SIZE - ADAPTIVE_SAFE_ZONE) / 2 / ADAPTIVE_ICON_SIZE
        val size = bitmap.width - inset * 2
        if (size <= 0 || inset <= 0) return bitmap
        return Bitmap.createBitmap(bitmap, inset, inset, size, size)
    }

    private fun resizeBitmapForAdaptiveIcon(bitmap: Bitmap, logoSizeDp: Int = LOGO_SIZE): Bitmap {
        val density = App.appContext.resources.displayMetrics.density
        val iconSizePx = (ADAPTIVE_ICON_SIZE * density).toInt()
        val logoSizePx = (logoSizeDp * density).toInt()

        val scale = min(logoSizePx.toFloat() / bitmap.width, logoSizePx.toFloat() / bitmap.height)
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val scaledBitmap = bitmap.scale(scaledWidth, scaledHeight)

        val finalBitmap = createBitmap(iconSizePx, iconSizePx)
        val canvas = Canvas(finalBitmap)

        canvas.drawColor(Color.WHITE)

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
