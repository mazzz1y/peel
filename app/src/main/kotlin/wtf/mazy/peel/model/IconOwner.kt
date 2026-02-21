package wtf.mazy.peel.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import wtf.mazy.peel.util.App

interface IconOwner {
    val uuid: String
    val title: String
    val letterIconSeed: String get() = title

    val iconFile: File
        get() = File(App.appContext.filesDir, "icons/${uuid}.png")

    val hasCustomIcon: Boolean
        get() = iconFile.exists()

    fun deleteIcon() {
        try {
            if (iconFile.exists()) iconFile.delete()
        } catch (_: Exception) {}
    }

    fun loadIcon(): Bitmap? =
        if (hasCustomIcon) {
            try {
                BitmapFactory.decodeFile(iconFile.absolutePath)
            } catch (_: Exception) { null }
        } else null

    fun saveIcon(bitmap: Bitmap) {
        try {
            val file = iconFile
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {}
    }
}
