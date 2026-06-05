package com.ncmdecrypt

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

class InputTooLargeException(val maxBytes: Long) : Exception()

object BoundedInput {
    fun readBytes(resolver: ContentResolver, uri: Uri, maxBytes: Long): ByteArray {
        val declaredSize = querySize(resolver, uri)
        if (declaredSize != null && declaredSize > maxBytes) {
            throw InputTooLargeException(maxBytes)
        }

        val initialSize = declaredSize?.coerceAtMost(maxBytes)?.toInt() ?: DEFAULT_BUFFER_SIZE
        val out = ByteArrayOutputStream(initialSize)
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read.toLong()
                if (total > maxBytes) throw InputTooLargeException(maxBytes)
                out.write(buffer, 0, read)
            }
        } ?: throw IllegalArgumentException("Input unavailable")
        return out.toByteArray()
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        }.getOrNull()?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) {
                    val size = cursor.getLong(idx)
                    if (size >= 0) return size
                }
            }
        }
        return runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it >= 0 }
            }
        }.getOrNull()
    }

    private const val DEFAULT_BUFFER_SIZE = 64 * 1024
}
