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
package eu.wynq.convex.core.value

import eu.wynq.convex.core.internal.LittleEndianBase64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The tagged-JSON vocabulary shared by the [ConvexValue] codecs.
 *
 * [ConvexJson], [ConvexValueSerializer], [ConvexValueEncoder], and
 * [ConvexValueDecoder] all recognise the same three reserved keys and must agree
 * on when a float needs a tag instead of a plain JSON number. Keeping the rules
 * in one place is what stops the codecs drifting apart.
 *
 * The tags are:
 * - `$integer` — base64 little-endian `i64`
 * - `$float` — base64 little-endian `f64`, used only when a plain number cannot
 *   carry the value (negative zero or non-finite)
 * - `$bytes` — base64
 */
internal object ConvexTaggedValue {
    /** The tag for an integer that a JSON number could not carry exactly. */
    const val INTEGER_KEY = "\$integer"

    /** The tag for a float that a JSON number could not express. */
    const val FLOAT_KEY = "\$float"

    /** The tag for an opaque byte string. */
    const val BYTES_KEY = "\$bytes"

    // IEEE-754 zero, named because the negative-zero distinction is exactly why
    // a float needs a tagged encoding at all. MagicNumber cannot express that.
    @Suppress("MagicNumber")
    private const val ZERO = 0.0

    private val negativeZeroBits: Long = (-ZERO).toRawBits()

    /**
     * Whether [value] is negative zero, which JSON would silently fold into
     * positive zero.
     */
    fun isNegativeZero(value: Double): Boolean =
        value == ZERO && value.toRawBits() == negativeZeroBits

    /**
     * Encodes a float as a plain JSON number, or a `$float` tag when it is
     * negative zero or non-finite.
     */
    fun floatElement(value: Double): JsonElement =
        if (!value.isFinite() || isNegativeZero(value)) {
            tagged(FLOAT_KEY, LittleEndianBase64.encodeDouble(value))
        } else {
            JsonPrimitive(value)
        }

    /** Wraps a base64 payload under a reserved tag key. */
    fun tagged(key: String, value: String): JsonObject =
        JsonObject(mapOf(key to JsonPrimitive(value)))
}
