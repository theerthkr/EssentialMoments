package com.theerthkr.essentialmoments.data

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stores [FloatArray] embeddings as a little-endian BLOB inside SQLite.
 *
 * Fixed endianness matters: the bytes are written and read on the same device
 * today, but a stable representation survives backups and future tooling.
 */
class FloatArrayConverter {

    @TypeConverter
    fun fromFloatArray(value: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(value)
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(value: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(value.size / Float.SIZE_BYTES)
        buffer.asFloatBuffer().get(result)
        return result
    }
}
