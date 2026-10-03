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

/**
 * The JSON codec for [ConvexValue].
 *
 * This mirrors the upstream `convex-rs` conversion and exists so 64-bit
 * integers survive the round trip: JSON numbers cannot represent them without
 * precision loss, so integers, non-finite floats, and bytes travel as tagged
 * base64 strings.
 *
 * Wire rules, all taken from the upstream implementation:
 * - `Int64` -> `{"$integer": "<base64 little-endian i64>"}`
 * - `Float64` -> a JSON number, except negative zero and non-finite values,
 *   which become `{"$float": "<base64 little-endian f64>"}`
 * - `Bytes` -> `{"$bytes": "<base64>"}`
 * - an untagged JSON number decodes to `Float64`
 * - a single-key object whose key is reserved is a tagged value; the retired
 *   `$set` and `$map` keys are rejected
 */
public object ConvexJson {
    private const val SET_KEY = "\$set"
    private const val MAP_KEY = "\$map"

    // IEEE-754 zero; the negative-zero distinction is why a float needs a tag.
    // MagicNumber cannot express that.
    @Suppress("MagicNumber")
    private const val ZERO = 0.0

    /**
     * Encodes [value] to its compact JSON representation.
     *
     * Object keys are emitted in sorted order so output is deterministic and
     * matches the upstream, whose maps are ordered.
     *
     * @param value the value to encode.
     * @return compact JSON text.
     */
    public fun encode(value: ConvexValue): String = toElement(value).toString()

    /**
     * Decodes JSON text into a [ConvexValue].
     *
     * @param text the JSON document.
     * @return the decoded value.
     * @throws ConvexJsonException when the text is not valid JSON or does not
     *   match the Convex wire format.
     */
    public fun decode(text: String): ConvexValue {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (failure: SerializationException) {
            throw ConvexJsonException("invalid JSON: ${failure.message}", failure)
        }
        return fromElement(element)
    }

    /**
     * Converts a value to a JSON element for embedding in a larger document.
     *
     * This is the allocation-free counterpart to [encode]: use it when the
     * value is one field among many instead of round-tripping through text.
     *
     * @param value the value to convert.
     * @return the equivalent JSON element.
     */
    public fun toJsonElement(value: ConvexValue): JsonElement = toElement(value)

    /**
     * Converts a JSON element from a larger document into a value.
     *
     * This is the counterpart to [decode] for callers that already hold a
     * parsed element instead of text.
     *
     * @param element the element to convert.
     * @return the decoded value.
     * @throws ConvexJsonException when the element does not match the Convex
     *   wire format.
     */
    public fun fromJsonElement(element: JsonElement): ConvexValue = fromElement(element)

    private fun toElement(value: ConvexValue): JsonElement = when (value) {
        ConvexValue.Null -> JsonNull
        is ConvexValue.Int64 ->
            ConvexTaggedValue.tagged(ConvexTaggedValue.INTEGER_KEY, LittleEndianBase64.encodeLong(value.value))
        is ConvexValue.Float64 -> ConvexTaggedValue.floatElement(value.value)
        is ConvexValue.Boolean -> JsonPrimitive(value.value)
        is ConvexValue.String -> JsonPrimitive(value.value)
        is ConvexValue.Bytes ->
            ConvexTaggedValue.tagged(ConvexTaggedValue.BYTES_KEY, LittleEndianBase64.encodeBytes(value.value))
        is ConvexValue.Array -> JsonArray(value.value.map(::toElement))
        is ConvexValue.Object -> JsonObject(
            value.value.entries.sortedBy { it.key }.associate { it.key to toElement(it.value) },
        )
    }

    private fun fromElement(element: JsonElement): ConvexValue = when (element) {
        is JsonNull -> ConvexValue.Null
        is JsonPrimitive -> fromPrimitive(element)
        is JsonArray -> ConvexValue.Array(element.map(::fromElement))
        is JsonObject -> fromObject(element)
    }

    private fun fromPrimitive(element: JsonPrimitive): ConvexValue {
        if (element.isString) return ConvexValue.String(element.content)
        element.booleanOrNull?.let { return ConvexValue.Boolean(it) }
        element.doubleOrNull?.let { return ConvexValue.Float64(it) }
        throw ConvexJsonException("unsupported JSON primitive: ${element.content}")
    }

    private fun fromObject(element: JsonObject): ConvexValue {
        if (element.size == 1) {
            val key = element.keys.first()
            when (key) {
                ConvexTaggedValue.BYTES_KEY ->
                    return ConvexValue.Bytes(LittleEndianBase64.decodeBytes(element.requireString(key)))
                ConvexTaggedValue.INTEGER_KEY ->
                    return ConvexValue.Int64(LittleEndianBase64.decodeLong(element.requireString(key)))
                ConvexTaggedValue.FLOAT_KEY -> return decodeTaggedFloat(element.requireString(key))
                SET_KEY -> throw ConvexJsonException("$SET_KEY is no longer supported as a Convex type")
                MAP_KEY -> throw ConvexJsonException("$MAP_KEY is no longer supported as a Convex type")
            }
        }
        return ConvexValue.Object(
            element.entries.sortedBy { it.key }.associate { it.key to fromElement(it.value) },
        )
    }

    private fun decodeTaggedFloat(text: String): ConvexValue {
        val value = LittleEndianBase64.decodeDouble(text)
        // A tagged float must be something a plain number could not express.
        val wouldFitAsNumber = value.isFinite() && value != ZERO
        if (!ConvexTaggedValue.isNegativeZero(value) && wouldFitAsNumber) {
            throw ConvexJsonException("Float64 $value should be encoded as a number")
        }
        return ConvexValue.Float64(value)
    }

    private fun JsonObject.requireString(key: String): String {
        val primitive = this[key] as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw ConvexJsonException("'$key' must be a base64 string")
        }
        return primitive.content
    }
}
