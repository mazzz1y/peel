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
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.google.android.material.materialswitch.MaterialSwitch
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
import wtf.mazy.peel.util.NotificationUtils
import kotlin.math.min

object ShortcutHelper {
    private const val ADAPTIVE_ICON_SIZE = 108
    private const val LOGO_SIZE = 48
    private const val ADAPTIVE_SAFE_ZONE = 72

    fun createShortcut(owner: IconOwner, activity: Activity) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) return
        showCreateDialog(owner, activity) { name, logoSizeDp, fillBackground, adaptiveIcon ->
            pinShortcut(
                owner,
                activity,
                resolveTitle(name, owner),
                logoSizeDp,
                fillBackground,
                adaptiveIcon,
            )
        }
    }

    fun updatePinnedShortcut(owner: IconOwner, context: Context) {
        val scManager = context.getSystemService(ShortcutManager::class.java)
        if (scManager.pinnedShortcuts.none { it.id == owner.uuid }) return

        val saved = ShortcutIconPrefs(context, owner.uuid).load()
        val logoSizeDp = saved?.logoSizeDp ?: LOGO_SIZE
        val fillBackground = saved?.fillBackground ?: false
        val adaptiveIcon = saved?.adaptiveIcon ?: false

        val intent = buildIntent(owner, context)
        val icon = resolveIcon(owner, logoSizeDp, fillBackground, adaptiveIcon)
        val title = resolveTitle("", owner)

        val updated = buildShortcutInfo(context, owner.uuid, title, icon, intent)
        ShortcutManagerCompat.updateShortcuts(context, listOf(updated))
    }

    private fun showCreateDialog(
        owner: IconOwner,
        activity: Activity,
        onConfirm: (
            name: String,
            logoSizeDp: Int,
            fillBackground: Boolean,
            adaptiveIcon: Boolean,
        ) -> Unit,
    ) {
        val hasCustomIcon = owner.loadIcon() != null
        val saved = ShortcutIconPrefs(activity, owner.uuid).load()
        var preview: ImageView? = null
        var slider: Slider? = null
        var fillSwitch: MaterialSwitch?
        var fillRow: View? = null
        var circleOutline: View? = null
        var lastPreviewBitmap: Bitmap? = null
        var fillBackground = saved?.fillBackground ?: false
        var adaptiveIcon = saved?.adaptiveIcon ?: false
        val initialLogoSize = saved?.logoSizeDp ?: LOGO_SIZE

        fun renderPreview(logoSizeDp: Int) {
            val raw = owner.loadIcon()
            val display = if (adaptiveIcon || raw == null) {
                val full = buildAdaptiveBitmap(owner, logoSizeDp, fillBackground)
                val cropped = cropToSafeZone(full)
                if (full !== cropped) full.recycle()
                cropped
            } else {
                raw
            }
            preview?.setImageBitmap(display)
            lastPreviewBitmap?.takeIf { it !== display && !it.isRecycled }?.recycle()
            lastPreviewBitmap = display
        }

        fun currentLogoSize(): Int =
            if (hasCustomIcon) slider?.value?.toInt() ?: LOGO_SIZE else LOGO_SIZE

        fun applyAdaptiveVisibility() {
            val showCustomization = adaptiveIcon && hasCustomIcon
            slider?.visibility = if (showCustomization) View.VISIBLE else View.GONE
            fillRow?.visibility = if (showCustomization) View.VISIBLE else View.GONE
            circleOutline?.visibility = if (adaptiveIcon) View.VISIBLE else View.GONE
        }

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
                            as ViewGroup
                    preview = extras.findViewById(R.id.preview)
                    slider = extras.findViewById(R.id.slider)
                    fillSwitch = extras.findViewById(R.id.switchFillBackground)
                    fillRow = extras.findViewById(R.id.fillBackgroundRow)
                    circleOutline = extras.findViewById(R.id.previewCircleOutline)
                    val adaptiveSwitch =
                        extras.findViewById<MaterialSwitch>(R.id.switchAdaptiveIcon)
                    val adaptiveRow = extras.findViewById<View>(R.id.adaptiveIconRow)
                    val previewContainer =
                        extras.findViewById<View>(R.id.previewContainer)

                    if (hasCustomIcon) {
                        slider?.let {
                            val clamped = initialLogoSize.toFloat()
                                .coerceIn(it.valueFrom, it.valueTo)
                            it.value = clamped
                            it.addOnChangeListener { _, value, _ ->
                                renderPreview(value.toInt())
                            }
                        }
                        fillSwitch?.isChecked = fillBackground
                        fillSwitch?.setOnCheckedChangeListener { _, isChecked ->
                            fillBackground = isChecked
                            renderPreview(currentLogoSize())
                        }
                        adaptiveSwitch.isChecked = adaptiveIcon
                        adaptiveSwitch.setOnCheckedChangeListener { _, isChecked ->
                            adaptiveIcon = isChecked
                            if (isChecked) {
                                fillBackground = false
                                fillSwitch?.isChecked = false
                                slider?.let {
                                    it.value = LOGO_SIZE.toFloat()
                                        .coerceIn(it.valueFrom, it.valueTo)
                                }
                            }
                            applyAdaptiveVisibility()
                            renderPreview(currentLogoSize())
                        }
                        applyAdaptiveVisibility()
                    } else {
                        slider?.visibility = View.GONE
                        fillRow?.visibility = View.GONE
                        adaptiveRow.visibility = View.GONE
                    }
                    renderPreview(currentLogoSize())
                    extras.removeView(previewContainer)
                    container.addView(previewContainer, 0)
                    container.addView(extras)
                },
            ),
        ) { nameInput, _ ->
            onConfirm(
                nameInput.text.toString().trim(),
                currentLogoSize(),
                fillBackground,
                adaptiveIcon,
            )
        }
    }

    private fun pinShortcut(
        owner: IconOwner,
        activity: Activity,
        title: String,
        logoSizeDp: Int = LOGO_SIZE,
        fillBackground: Boolean = false,
        adaptiveIcon: Boolean = false,
    ) {
        val prefs = ShortcutIconPrefs(activity, owner.uuid)
        val savedPrefs = prefs.load()
        val sizeToSave = if (adaptiveIcon) logoSizeDp else (savedPrefs?.logoSizeDp ?: LOGO_SIZE)
        val fillToSave = if (adaptiveIcon) fillBackground else (savedPrefs?.fillBackground ?: false)
        prefs.save(sizeToSave, fillToSave, adaptiveIcon)

        val intent = buildIntent(owner, activity)
        val icon = resolveIcon(owner, logoSizeDp, fillBackground, adaptiveIcon)
        val info = buildShortcutInfo(activity, owner.uuid, title, icon, intent)

        val scManager = activity.getSystemService(ShortcutManager::class.java)
        val alreadyPinned = scManager.pinnedShortcuts.any { it.id == owner.uuid }
        if (alreadyPinned) {
            ShortcutManagerCompat.updateShortcuts(activity, listOf(info))
            NotificationUtils.showToast(
                activity,
                activity.getString(R.string.shortcut_already_exists),
                Toast.LENGTH_SHORT,
            )
        } else {
            ShortcutManagerCompat.requestPinShortcut(activity, info, null)
            ShortcutManagerCompat.updateShortcuts(activity, listOf(info))
        }
    }

    private fun resolveTitle(name: String, owner: IconOwner): String =
        name.ifEmpty { owner.title.ifEmpty { App.appContext.getString(R.string.untitled_shortcut) } }

    private fun resolveIcon(
        owner: IconOwner,
        logoSizeDp: Int = LOGO_SIZE,
        fillBackground: Boolean = false,
        adaptiveIcon: Boolean = false,
    ): IconCompat {
        val raw = owner.loadIcon()
        return if (adaptiveIcon || raw == null) {
            IconCompat.createWithAdaptiveBitmap(
                buildAdaptiveBitmap(owner, logoSizeDp, fillBackground)
            )
        } else {
            IconCompat.createWithBitmap(raw)
        }
    }

    private fun buildAdaptiveBitmap(
        owner: IconOwner,
        logoSizeDp: Int,
        fillBackground: Boolean,
    ): Bitmap {
        val bitmap = owner.loadIcon()
        return if (bitmap != null) {
            resizeBitmapForAdaptiveIcon(bitmap, logoSizeDp, fillBackground)
        } else {
            LetterIconGenerator.generateForAdaptiveIcon(owner.title, owner.letterIconSeed)
        }
    }

    private fun cropToSafeZone(bitmap: Bitmap): Bitmap {
        val inset =
            bitmap.width * (ADAPTIVE_ICON_SIZE - ADAPTIVE_SAFE_ZONE) / 2 / ADAPTIVE_ICON_SIZE
        val size = bitmap.width - inset * 2
        if (size <= 0 || inset <= 0) return bitmap
        return Bitmap.createBitmap(bitmap, inset, inset, size, size)
    }

    private fun resizeBitmapForAdaptiveIcon(
        bitmap: Bitmap,
        logoSizeDp: Int = LOGO_SIZE,
        fillBackground: Boolean = false,
    ): Bitmap {
        val density = App.appContext.resources.displayMetrics.density
        val iconSizePx = (ADAPTIVE_ICON_SIZE * density).toInt()
        val logoSizePx = (logoSizeDp * density).toInt()

        val trimmed =
            if (fillBackground) IconBackgroundExtender.trimTransparentEdges(bitmap) else null
        val source = trimmed ?: bitmap
        val hadPadding = trimmed != null && trimmed !== bitmap

        val scale = min(logoSizePx.toFloat() / source.width, logoSizePx.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt()
        val scaledHeight = (source.height * scale).toInt()

        val scaledBitmap = source.scale(scaledWidth, scaledHeight)

        val finalBitmap = createBitmap(iconSizePx, iconSizePx)
        val canvas = Canvas(finalBitmap)

        val left = (iconSizePx - scaledWidth) / 2
        val top = (iconSizePx - scaledHeight) / 2

        if (fillBackground) {
            canvas.drawColor(IconBackgroundExtender.sampleBackgroundColor(source))
            if (!hadPadding) {
                IconBackgroundExtender.drawEdgeStretch(
                    canvas, scaledBitmap, left, top, scaledWidth, scaledHeight, iconSizePx,
                )
            }
        } else {
            canvas.drawColor(Color.WHITE)
        }

        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaledBitmap, left.toFloat(), top.toFloat(), drawPaint)

        if (scaledBitmap != source) scaledBitmap.recycle()
        if (trimmed != null && trimmed !== bitmap) trimmed.recycle()

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
