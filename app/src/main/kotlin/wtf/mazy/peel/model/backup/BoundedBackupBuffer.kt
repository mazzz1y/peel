package wtf.mazy.peel.model.backup

import java.io.IOException
import java.io.OutputStream

internal class BoundedBackupBuffer(private val limit: Int) : OutputStream() {
    private val chunks = mutableListOf<ByteArray>()
    private var size = 0
    private var exceeded = false

    override fun write(value: Int) {
        reserve(1)
        ensureChunk()
        chunks.last()[size % CHUNK_BYTES] = value.toByte()
        size++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        reserve(length)
        var position = offset
        var remaining = length
        while (remaining > 0) {
            ensureChunk()
            val chunkOffset = size % CHUNK_BYTES
            val count = minOf(remaining, chunks.last().size - chunkOffset)
            bytes.copyInto(chunks.last(), chunkOffset, position, position + count)
            size += count
            position += count
            remaining -= count
        }
    }

    fun toByteArray(): ByteArray {
        reserve(0)
        return ByteArray(size).also { result ->
            var offset = 0
            for (chunk in chunks) {
                val count = minOf(chunk.size, size - offset)
                chunk.copyInto(result, offset, 0, count)
                offset += count
            }
        }
    }

    fun clear() {
        chunks.forEach { it.fill(0) }
        chunks.clear()
        size = 0
    }

    private fun reserve(count: Int) {
        if (exceeded || count > limit - size) {
            exceeded = true
            throw IOException("Backup exceeds size limit")
        }
    }

    private fun ensureChunk() {
        if (size % CHUNK_BYTES == 0) {
            chunks.add(ByteArray(minOf(CHUNK_BYTES, limit - size)))
        }
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
