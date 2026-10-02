/*
 * Copyright 2026 convex-kt contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.wynq.convex.core.internal

import eu.wynq.convex.core.value.ConvexJsonException
import kotlin.io.encoding.Base64

/** A 64-bit value is always eight bytes on the wire. */
private const val LONG_BYTES = 8

/** Bits per byte, used to place little-endian bytes into a 64-bit value. */
private const val BITS_PER_BYTE = 8

/** Mask to widen a signed byte to an unsigned 0..255 range. */
private const val BYTE_MASK = 0xFF

/**
 * The base64-of-little-endian-bytes encoding Convex uses for integers, floats,
 * and raw bytes.
 *
 * Convex chose this over JSON numbers because JSON numbers cannot carry a full
 * 64-bit value without precision loss, so this codec is where that promise is
 * kept or broken. All three shapes share one implementation so they cannot
 * drift apart.
 *
 * The upstream uses the `base64` crate's `STANDARD` engine with padding, which
 * matches Kotlin's [Base64.Default].
 */
internal object LittleEndianBase64 {
    /** Encodes raw bytes as standard padded base64. */
    fun encodeBytes(value: ByteArray): String = Base64.Default.encode(value)

    /** Decodes standard padded base64, failing clearly on malformed input. */
    fun decodeBytes(text: String): ByteArray =
        try {
            Base64.Default.decode(text)
        } catch (failure: IllegalArgumentException) {
            throw ConvexJsonException("invalid base64: ${failure.message}", failure)
        }

    /** Encodes a signed 64-bit integer as base64 little-endian bytes. */
    fun encodeLong(value: Long): String = encodeBytes(value.toLittleEndianBytes())

    /** Decodes a signed 64-bit integer, requiring exactly eight bytes. */
    fun decodeLong(text: String): Long = exactBytes(text, "Int64").longFromLittleEndian()

    /** Encodes an unsigned 64-bit integer as base64 little-endian bytes. */
    fun encodeULong(value: ULong): String = encodeBytes(value.toLittleEndianBytes())

    /** Decodes an unsigned 64-bit integer, requiring exactly eight bytes. */
    fun decodeULong(text: String): ULong = exactBytes(text, "u64").uLongFromLittleEndian()

    /**
     * Encodes a double preserving every bit.
     *
     * `toRawBits` rather than `toBits` is deliberate: it round-trips NaN
     * payloads, which the upstream exercises with its NaN "trophy" values.
     */
    fun encodeDouble(value: Double): String = encodeBytes(value.toRawBits().toLittleEndianBytes())

    /** Decodes a double, requiring exactly eight bytes. */
    fun decodeDouble(text: String): Double =
        Double.fromBits(exactBytes(text, "Float64").longFromLittleEndian())

    private fun exactBytes(text: String, label: String): ByteArray {
        val bytes = decodeBytes(text)
        if (bytes.size != LONG_BYTES) {
            throw ConvexJsonException("$label must be exactly $LONG_BYTES bytes")
        }
        return bytes
    }
}

private fun Long.toLittleEndianBytes(): ByteArray =
    ByteArray(LONG_BYTES) { index -> (this ushr (BITS_PER_BYTE * index)).toByte() }

private fun ULong.toLittleEndianBytes(): ByteArray =
    ByteArray(LONG_BYTES) { index -> (this shr (BITS_PER_BYTE * index)).toByte() }

private fun ByteArray.longFromLittleEndian(): Long {
    var result = 0L
    for (index in indices) {
        result = result or ((this[index].toLong() and BYTE_MASK.toLong()) shl (BITS_PER_BYTE * index))
    }
    return result
}

private fun ByteArray.uLongFromLittleEndian(): ULong {
    var result = 0uL
    for (index in indices) {
        result = result or (this[index].toUByte().toULong() shl (BITS_PER_BYTE * index))
    }
    return result
}
