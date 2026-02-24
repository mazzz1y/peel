package wtf.mazy.peel.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.util.App

interface IconOwner {
    val uuid: String
    val title: String
    val letterIconSeed: String get() = title

    val iconFile: File
        get() = File(App.appContext.filesDir, "icons/${uuid}.png")

    val hasCustomIcon: Boolean
        get() = iconFile.exists()

    fun loadIcon(): Bitmap? = IconCache.getOriginal(this)

    fun resolveIcon(sizePx: Int = defaultIconSizePx()): Bitmap =
        IconCache.resolve(this, sizePx)

    fun saveIcon(bitmap: Bitmap) {
        try {
            val file = iconFile
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {}
        IconCache.evict(this)
    }

    fun deleteIcon() {
        try {
            if (iconFile.exists()) iconFile.delete()
        } catch (_: Exception) {}
        IconCache.evict(this)
    }

    companion object {
        fun defaultIconSizePx(): Int =
            (48 * App.appContext.resources.displayMetrics.density).toInt()
    }
}

object IconCache {
    private val cacheDir by lazy {
        File(App.appContext.cacheDir, "icons").also { it.mkdirs() }
    }

    fun resolve(owner: IconOwner, sizePx: Int): Bitmap {
        val dir = File(cacheDir, owner.uuid)
        if (owner.hasCustomIcon) {
            readCache(dir, "$sizePx")?.let { return it }
            val original = decodeSource(owner.iconFile) ?: return fallback(owner, dir, sizePx)
            val bitmap = scaleToFit(original, sizePx)
            writeCache(dir, "$sizePx", bitmap)
            if (bitmap !== original) original.recycle()
            return bitmap
        }
        return fallback(owner, dir, sizePx)
    }

    fun getOriginal(owner: IconOwner): Bitmap? {
        if (!owner.hasCustomIcon) return null
        return decodeSource(owner.iconFile)
    }

    fun evict(owner: IconOwner) {
        File(cacheDir, owner.uuid).deleteRecursively()
    }

    private fun fallback(owner: IconOwner, dir: File, sizePx: Int): Bitmap {
        val key = "fallback-$sizePx-${owner.title.hashCode()}"
        readCache(dir, key)?.let { return it }
        dir.listFiles()?.filter { it.name.startsWith("fallback-") }?.forEach { it.delete() }
        val bitmap = LetterIconGenerator.generate(owner.title, owner.letterIconSeed, sizePx)
        writeCache(dir, key, bitmap)
        return bitmap
    }

    private fun decodeSource(file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.absolutePath)
    } catch (_: Exception) { null }

    private fun scaleToFit(bitmap: Bitmap, sizePx: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= sizePx) return bitmap
        val scale = sizePx.toFloat() / max
        return bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
        )
    }

    private fun readCache(dir: File, name: String): Bitmap? {
        val file = File(dir, name)
        if (!file.exists()) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                val width = mapped.short.toInt() and 0xFFFF
                val height = mapped.short.toInt() and 0xFFFF
                if (raf.length() != 4L + width * height * 4L) return null
                val bitmap = createBitmap(width, height)
                bitmap.copyPixelsFromBuffer(mapped)
                bitmap
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun writeCache(dir: File, name: String, bitmap: Bitmap) {
        try {
            dir.mkdirs()
            val size = 4L + bitmap.byteCount
            RandomAccessFile(File(dir, name), "rw").use { raf ->
                raf.setLength(size)
                val mapped = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, size)
                mapped.putShort(bitmap.width.toShort())
                mapped.putShort(bitmap.height.toShort())
                bitmap.copyPixelsToBuffer(mapped)
            }
        } catch (_: Exception) {}
    }
}
