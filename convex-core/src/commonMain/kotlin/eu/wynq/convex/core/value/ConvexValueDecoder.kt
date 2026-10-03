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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes a [ConvexValue] into a Kotlin type via its kotlinx serializer.
 *
 * This is the inverse of [ConvexValueEncoder] and exists for typed results: a
 * mutation or action returns a [ConvexValue], and a generated descriptor knows
 * the Kotlin type that value should become.
 *
 * The conversion is deliberately **not** [ConvexJson.toJsonElement]. That
 * function produces the *wire* form, where `Int64` and `Bytes` are tagged
 * objects (`{"$integer": ...}`) — correct for the protocol, but undecodable by
 * a serializer expecting a plain `Long` or `ByteArray`. This decoder therefore
 * performs a plain mapping: an `Int64` becomes a real JSON number (base64 is
 * only the wire encoding), and `Bytes` become a base64 string that the
 * serializer reads as a `ByteArray`.
 *
 * The full 64 bits survive because the tag carried them; the reverse direction
 * is exact.
 */
public object ConvexValueDecoder {
    /**
     * Decodes [value] using [serializer].
     *
     * @param serializer the serializer for the expected Kotlin type.
     * @param value the value received from the backend.
     * @return the decoded object.
     * @throws ConvexJsonException when [value] does not match the serializer's
     *   shape.
     */
    public fun <T> decode(serializer: KSerializer<T>, value: ConvexValue): T {
        val element = toElement(value)
        return try {
            Json.decodeFromJsonElement(serializer, element)
        } catch (failure: SerializationException) {
            throw ConvexJsonException("failed to decode result: ${failure.message}", failure)
        }
    }

    private fun toElement(value: ConvexValue): JsonElement = when (value) {
        ConvexValue.Null -> JsonNull
        is ConvexValue.Int64 -> JsonPrimitive(value.value)
        is ConvexValue.Float64 -> floatElement(value.value)
        is ConvexValue.Boolean -> JsonPrimitive(value.value)
        is ConvexValue.String -> JsonPrimitive(value.value)
        is ConvexValue.Bytes -> JsonPrimitive(LittleEndianBase64.encodeBytes(value.value))
        is ConvexValue.Array -> JsonArray(value.value.map(::toElement))
        is ConvexValue.Object -> JsonObject(
            value.value.entries.associate { it.key to toElement(it.value) },
        )
    }

    /**
     * Emits a tagged float when the value cannot be a JSON number.
     *
     * JSON has no literal for infinity or NaN, and `-0.0` would lose its sign as
     * a plain number. Those three become `{"$float": "<base64>"}`, matching
     * [ConvexJson] and [ConvexValueSerializer], so a `ConvexValue`-typed result
     * holding one still round-trips.
     */
    private fun floatElement(value: Double): JsonElement =
        if (!value.isFinite() || isNegativeZero(value)) {
            JsonObject(mapOf("\$float" to JsonPrimitive(LittleEndianBase64.encodeDouble(value))))
        } else {
            JsonPrimitive(value)
        }

    private fun isNegativeZero(value: Double): Boolean =
        value == 0.0 && (1.0 / value) == Double.NEGATIVE_INFINITY
}
