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
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A `KSerializer<ConvexValue>` for the generated code's **untyped fallback**
 * fields.
 *
 * `convex-codegen` maps a validator it cannot model (an untyped `any`, a
 * non-null union, a literal) to `ConvexValue`. For a generated argument or
 * result class to be `@Serializable`, that field needs a serializer, which this
 * provides.
 *
 * It uses the **tagged wire shape** of [ConvexJson]: `Int64` becomes
 * `{"$integer": "<base64>"}`, `Bytes` becomes `{"$bytes": "<base64>"}`, and a
 * non-finite or negative-zero `Float64` becomes `{"$float": "<base64>"}`. The
 * tag is necessary for two reasons:
 *
 * - a plain JSON number cannot say whether it is an `Int64` or a `Float64`;
 * - a plain JSON string cannot say whether it is text or base64 bytes.
 *
 * Both are decoded back exactly, and the tagged forms are also produced by
 * [ConvexJson], so the two codecs are interoperable. A plain JSON number is
 * accepted on decode as well and read as an `Int64` when it has no fraction,
 * matching [ConvexJson].
 */
public object ConvexValueSerializer : KSerializer<ConvexValue> {
    // Reuse the JSON element descriptor: this serializer is a JSON-element
    // passthrough, so its shape is exactly JsonElement's. Building a bespoke
    // descriptor would require the internal Serialization API for no gain.
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ConvexValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("ConvexValueSerializer requires a JSON encoder")
        jsonEncoder.encodeJsonElement(toElement(value))
    }

    override fun deserialize(decoder: Decoder): ConvexValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("ConvexValueSerializer requires a JSON decoder")
        return fromElement(jsonDecoder.decodeJsonElement())
    }

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
        is ConvexValue.Object -> JsonObject(value.value.mapValues { toElement(it.value) })
    }

    private fun fromElement(element: JsonElement): ConvexValue = when (element) {
        is JsonNull -> ConvexValue.Null
        is JsonArray -> ConvexValue.Array(element.map(::fromElement))
        is JsonObject -> fromObject(element)
        is JsonPrimitive -> fromPrimitive(element)
    }

    private fun fromObject(element: JsonObject): ConvexValue {
        if (element.size == 1) {
            val only = element.entries.single()
            when (only.key) {
                ConvexTaggedValue.INTEGER_KEY -> (only.value as? JsonPrimitive)?.content?.let {
                    return ConvexValue.Int64(LittleEndianBase64.decodeLong(it))
                }
                ConvexTaggedValue.FLOAT_KEY -> (only.value as? JsonPrimitive)?.content?.let {
                    return ConvexValue.Float64(LittleEndianBase64.decodeDouble(it))
                }
                ConvexTaggedValue.BYTES_KEY -> (only.value as? JsonPrimitive)?.content?.let {
                    return ConvexValue.Bytes(LittleEndianBase64.decodeBytes(it))
                }
            }
        }
        return ConvexValue.Object(element.mapValues { fromElement(it.value) })
    }

    private fun fromPrimitive(element: JsonPrimitive): ConvexValue {
        if (element.isString) return ConvexValue.String(element.content)
        element.booleanOrNull?.let { return ConvexValue.Boolean(it) }
        return fromNumber(element)
    }

    private fun fromNumber(element: JsonPrimitive): ConvexValue {
        element.longOrNull?.let { return ConvexValue.Int64(it) }
        element.doubleOrNull?.let { return ConvexValue.Float64(it) }
        return ConvexValue.Null
    }
}
