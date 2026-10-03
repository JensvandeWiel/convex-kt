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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Encodes a kotlinx-serialization-encoded argument object into a
 * [ConvexValue.Object].
 *
 * This exists for one reason: **JSON numbers are ambiguous, Convex numbers are
 * not.** When a `@Serializable` argument class declares a `Long`, kotlinx
 * serialization writes a JSON number with no marker distinguishing it from a
 * `Double`. Convex, however, stores `Int64` and `Float64` as different types and
 * transmits them differently on the wire.
 *
 * The plain [Json] path would therefore lose the distinction: a `Long` argument
 * would decode into `ConvexValue.Float64` (the upstream rule for an untagged
 * number), silently turning `42` into `42.0` — a different Convex value, and a
 * different result when read back.
 *
 * The fix is to re-derive the type from the *lexical form* of the JSON number,
 * which kotlinx preserves in [JsonPrimitive.content]:
 *
 * - a token containing `.`, `e`, or `E` is a [ConvexValue.Float64]
 * - any other numeric token is a [ConvexValue.Int64]
 *
 * A `Long` is always written without a fraction, so `42L` re-derives as
 * `Int64(42)` and `42.0` as `Float64(42.0)`. A JSON value that is not a number,
 * string, boolean, array, or object is rejected rather than guessed at.
 */
public object ConvexValueEncoder {
    // The tags ConvexValueSerializer uses for a nested ConvexValue field. They
    // mirror ConvexJson's wire keys, so the two codecs interoperate.
    private const val INTEGER_KEY = "\$integer"
    private const val FLOAT_KEY = "\$float"
    private const val BYTES_KEY = "\$bytes"

    /**
     * Encodes a JSON element produced by kotlinx serialization into a
     * [ConvexValue], preserving the `Int64`/`Float64` distinction.
     *
     * @param element the element, typically from
     *   `Json.encodeToJsonElement(serializer, args)`.
     * @return the equivalent Convex value.
     * @throws ConvexJsonException when a primitive cannot be mapped to a Convex
     *   type.
     */
    public fun encode(element: JsonElement): ConvexValue = when (element) {
        is JsonNull -> ConvexValue.Null
        is JsonPrimitive -> encodePrimitive(element)
        is JsonArray -> ConvexValue.Array(element.map(::encode))
        is JsonObject -> encodeObject(element) ?: ConvexValue.Object(
            element.entries.sortedBy { it.key }.associate { it.key to encode(it.value) },
        )
    }

    /**
     * Recognises the tagged forms that [ConvexValueSerializer] emits for a
     * nested [ConvexValue] field, and returns `null` when [element] is an
     * ordinary object.
     *
     * A generated class may hold a `ConvexValue` field for a validator we could
     * not model. Its serializer tags integers, bytes, and special floats so the
     * type survives; this method turns those tags back into the right
     * [ConvexValue] so the value that reaches the wire is correct.
     */
    private fun encodeObject(element: JsonObject): ConvexValue? {
        if (element.size != 1) return null
        val only = element.entries.single()
        val content = (only.value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return when (only.key) {
            INTEGER_KEY -> ConvexValue.Int64(LittleEndianBase64.decodeLong(content))
            FLOAT_KEY -> ConvexValue.Float64(LittleEndianBase64.decodeDouble(content))
            BYTES_KEY -> ConvexValue.Bytes(LittleEndianBase64.decodeBytes(content))
            else -> null
        }
    }

    /**
     * Encodes a serializable value with [serializer] and maps the result into a
     * [ConvexValue].
     *
     * @param serializer the serializer for [value].
     * @param value the value to encode.
     * @return the equivalent Convex value.
     * @throws ConvexJsonException when [value] does not serialize to JSON, or a
     *   primitive cannot be mapped to a Convex type.
     */
    public fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): ConvexValue {
        val element = try {
            Json.encodeToJsonElement(serializer, value)
        } catch (failure: SerializationException) {
            throw ConvexJsonException("failed to encode arguments: ${failure.message}", failure)
        }
        return encode(element)
    }

    /**
     * Maps one JSON primitive into a Convex value.
     *
     * Strings and booleans are unambiguous. A number is where the ambiguity
     * lives, so it is resolved by [isIntegerToken] rather than by a lookup
     * order that would fold `Long` into `Double`.
     */
    private fun encodePrimitive(element: JsonPrimitive): ConvexValue {
        if (element.isString) return ConvexValue.String(element.content)
        element.booleanOrNull?.let { return ConvexValue.Boolean(it) }
        if (element.content == "null") return ConvexValue.Null
        return encodeNumber(element)
    }

    /**
     * Maps a numeric JSON primitive, resolving the `Int64`/`Float64` ambiguity
     * from the token's lexical form. See [isIntegerToken].
     */
    private fun encodeNumber(element: JsonPrimitive): ConvexValue {
        if (isIntegerToken(element.content)) {
            element.longOrNull?.let { return ConvexValue.Int64(it) }
        }
        return element.doubleOrNull?.let { ConvexValue.Float64(it) }
            ?: throw ConvexJsonException("unsupported argument JSON primitive: ${element.content}")
    }

    /**
     * Whether a numeric token has an integer lexical form.
     *
     * JSON numbers carry no type tag, so the only reliable signal is the text:
     * `42` and `-7` are integers; `42.0`, `1e3`, and `1.5E-2` are not. This is
     * what keeps a Kotlin `Long` from silently becoming a Convex `Float64`.
     */
    private fun isIntegerToken(content: String): Boolean =
        !content.any { it == '.' || it == 'e' || it == 'E' }
}
