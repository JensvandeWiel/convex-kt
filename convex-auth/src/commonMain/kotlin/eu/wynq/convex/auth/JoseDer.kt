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
package eu.wynq.convex.auth

/**
 * Pure byte-level helpers shared by every platform's signature backend.
 *
 * The JVM and Android backends previously carried their own copies of these
 * (via `BigInteger`), and iOS carried a third; three implementations of DER
 * encoding is how a platform silently diverges. The platform actuals keep only
 * the framework calls (`java.security`, Security.framework) and share
 * everything computable here.
 */
internal const val ES256_COORDINATE_BYTES = 32
internal const val ES256_SIGNATURE_BYTES = 64

internal fun decodeBase64Url(segment: String?): ByteArray? = segment?.let(::decodeBase64UrlOrNull)

/**
 * Converts a JOSE raw `r || s` ECDSA signature into DER
 * `SEQUENCE { INTEGER r, INTEGER s }`.
 *
 * @return the DER bytes, or `null` when [signature] is not 64 bytes.
 */
internal fun joseToDer(signature: ByteArray): ByteArray? {
    if (signature.size != ES256_SIGNATURE_BYTES) return null
    val content = derInteger(signature, 0) + derInteger(signature, ES256_COORDINATE_BYTES)
    return byteArrayOf(DER_SEQUENCE_TAG, content.size.toByte()) + content
}

/** DER-encodes one fixed-width coordinate starting at [offset]. */
internal fun derInteger(
    signature: ByteArray,
    offset: Int,
): ByteArray {
    val end = offset + ES256_COORDINATE_BYTES
    var start = offset
    while (start < end && signature[start] == ZERO_BYTE) start++
    val body = positiveIntegerBody(signature.copyOfRange(start, end))
    return byteArrayOf(DER_INTEGER_TAG, body.size.toByte()) + body
}

/** DER-encodes an arbitrary-length magnitude, stripping leading zeros. */
internal fun derInteger(value: ByteArray): ByteArray {
    var start = 0
    while (start < value.size - 1 && value[start] == ZERO_BYTE) start++
    val body = positiveIntegerBody(value.copyOfRange(start, value.size))
    return byteArrayOf(DER_INTEGER_TAG) + derLength(body.size) + body
}

/**
 * Prepends the ASN.1 sign byte when the high bit is set.
 *
 * DER INTEGERs are signed, so a positive value whose most-significant bit is
 * `1` needs a leading zero, otherwise a decoder would read it as negative. An
 * empty value is encoded as a single zero byte.
 */
internal fun positiveIntegerBody(magnitude: ByteArray): ByteArray {
    val needsSignByte = magnitude.isEmpty() || (magnitude[0].toInt() and HIGH_BIT_MASK) != 0
    return if (needsSignByte) byteArrayOf(0) + magnitude else magnitude
}

internal fun derSequence(body: ByteArray): ByteArray = byteArrayOf(DER_SEQUENCE_TAG) + derLength(body.size) + body

internal fun derLength(length: Int): ByteArray =
    when {
        length < DER_SHORT_FORM_MAX -> byteArrayOf(length.toByte())
        length < DER_LONG_FORM_MAX -> byteArrayOf(DER_LENGTH_1_TAG, length.toByte())
        else -> byteArrayOf(DER_LENGTH_2_TAG, (length ushr BITS_PER_BYTE).toByte(), length.toByte())
    }

internal const val DER_SEQUENCE_TAG: Byte = 0x30
internal const val DER_INTEGER_TAG: Byte = 0x02

/** DER lengths below 0x80 use one short-form byte; higher ones use a tag byte. */
internal const val DER_SHORT_FORM_MAX = 0x80
internal const val DER_LONG_FORM_MAX = 0x100
internal const val DER_LENGTH_1_TAG: Byte = 0x81.toByte()
internal const val DER_LENGTH_2_TAG: Byte = 0x82.toByte()

/** The ASN.1 sign bit: set means the integer would read as negative. */
internal const val HIGH_BIT_MASK = 0x80
internal const val ZERO_BYTE: Byte = 0

/** A byte holds 8 bits; the long-form DER length splits a 16-bit value per byte. */
internal const val BITS_PER_BYTE = 8
