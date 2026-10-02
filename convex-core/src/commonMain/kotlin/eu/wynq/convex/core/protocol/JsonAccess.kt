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
package eu.wynq.convex.core.protocol

import eu.wynq.convex.core.internal.LittleEndianBase64
import eu.wynq.convex.core.value.ConvexJson
import eu.wynq.convex.core.value.ConvexJsonException
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Typed accessors for the protocol JSON.
 *
 * Wire parsing is where the "key presence over key value" guardrail bites, so
 * accessors distinguish three states deliberately: a missing key, a present
 * `null`, and a present value. Optional accessors return `null` for the first
 * two; callers that must tell them apart (for example `errorData`) check
 * [has] directly.
 */

/** Parses a JSON object, converting malformed input into a protocol failure. */
internal fun parseJsonObject(text: String): JsonObject {
    val element = try {
        Json.parseToJsonElement(text)
    } catch (failure: SerializationException) {
        throw ConvexJsonException("invalid JSON: ${failure.message}", failure)
    }
    return element as? JsonObject ?: throw ConvexJsonException("message must be a JSON object")
}

/** Reads a required string, failing if it is missing or not a string. */
internal fun JsonObject.string(key: String): String {
    val primitive = this[key] as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw ConvexJsonException("'$key' must be a string")
    }
    return primitive.content
}

/** Reads an optional string; absent and `null` both yield `null`. */
internal fun JsonObject.optionalString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ConvexJsonException("'$key' must be a string or null")
}

/** Reads a required unsigned 32-bit integer. */
internal fun JsonObject.uint(key: String): UInt = long(key).toUInt()

/** Reads a required integer. */
internal fun JsonObject.long(key: String): Long {
    val primitive = this[key] as? JsonPrimitive
    val value = primitive?.longOrNull
    if (value == null) throw ConvexJsonException("'$key' must be an integer")
    return value
}

/** Reads an optional integer; absent and `null` both yield `null`. */
internal fun JsonObject.optionalLong(key: String): Long? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.longOrNull
        ?: throw ConvexJsonException("'$key' must be an integer or null")
}

/** Reads a required boolean. */
internal fun JsonObject.boolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull
        ?: throw ConvexJsonException("'$key' must be a boolean")

/** Reads an optional boolean; absent and `null` both yield `null`. */
internal fun JsonObject.optionalBoolean(key: String): Boolean? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return boolean(key)
}

/** Reads a required nested object. */
internal fun JsonObject.requireObject(key: String): JsonObject =
    this[key] as? JsonObject ?: throw ConvexJsonException("'$key' must be an object")

/** Reads a required array. */
internal fun JsonObject.requireArray(key: String): JsonArray =
    this[key] as? JsonArray ?: throw ConvexJsonException("'$key' must be an array")

/** Reads a required array of strings. */
internal fun JsonObject.stringList(key: String): List<String> = requireArray(key).map { element ->
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw ConvexJsonException("'$key' must be an array of strings")
    }
    primitive.content
}

/** Reads an optional list of Convex values; absent and `null` both yield empty. */
internal fun JsonObject.optionalValueList(key: String): List<ConvexValue> {
    val element = this[key] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    val array = element as? JsonArray ?: throw ConvexJsonException("'$key' must be an array")
    return array.map(ConvexJson::fromJsonElement)
}

/** Reads a required Convex value, converting a present `null` to [ConvexValue.Null]. */
internal fun JsonObject.value(key: String): ConvexValue {
    val element = this[key] ?: throw ConvexJsonException("'$key' is required")
    return ConvexJson.fromJsonElement(element)
}

/**
 * Reads an optional timestamp.
 *
 * The canonical form is base64 little-endian text. A plain integer is also
 * accepted because the upstream decoder for one field (`serverTs`) reads an
 * integer while its encoder writes the base64 string; tolerating both keeps us
 * compatible regardless of which side is authoritative.
 */
internal fun JsonObject.optionalTimestamp(key: String): Timestamp? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
        ?: throw ConvexJsonException("'$key' must be a timestamp")
    primitive.longOrNull?.let { return Timestamp(it.toULong()) }
    if (primitive.isString) return Timestamp(LittleEndianBase64.decodeULong(primitive.content))
    throw ConvexJsonException("'$key' must be a base64 timestamp")
}

/** Reads a required timestamp. */
internal fun JsonObject.timestamp(key: String): Timestamp =
    optionalTimestamp(key) ?: throw ConvexJsonException("'$key' is required")

/** `true` when the key is present, even if its value is `null`. */
internal fun JsonObject.has(key: String): Boolean = containsKey(key)
