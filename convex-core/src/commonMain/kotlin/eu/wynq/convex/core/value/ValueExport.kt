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

import eu.wynq.convex.core.ConvexException
import eu.wynq.convex.core.internal.LittleEndianBase64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Exports a value to the plain-JSON "database types" format Convex documents at
 * <https://docs.convex.dev/database/types>.
 *
 * This is deliberately **not** [ConvexJson]: that codec is the tagged wire
 * format, where an integer survives as base64 little-endian bytes. Export is
 * the lossy, human-facing projection, so `Int64` becomes a decimal string and
 * bytes become base64 text. Round-tripping an exported value therefore needs
 * the original validator (type) as well, which is what makes the two APIs
 * distinct rather than redundant.
 *
 * The projection mirrors the upstream `convex-rs` `Value::export`:
 * - `Null` -> `null`
 * - `Int64` -> a decimal string (JSON numbers cannot hold 64 bits)
 * - `Float64` -> a number, except `NaN`/`Infinity`/`-Infinity`, which become
 *   strings because JSON has no such numbers
 * - `Bytes` -> standard padded base64 text
 * - arrays and objects recurse
 *
 * @return the JSON representation.
 */
public fun ConvexValue.export(): JsonElement = when (this) {
    ConvexValue.Null -> JsonNull
    is ConvexValue.Int64 -> JsonPrimitive(value.toString())
    is ConvexValue.Float64 -> exportFloat(value)
    is ConvexValue.Boolean -> JsonPrimitive(value)
    is ConvexValue.String -> JsonPrimitive(value)
    is ConvexValue.Bytes -> JsonPrimitive(LittleEndianBase64.encodeBytes(value))
    is ConvexValue.Array -> kotlinx.serialization.json.JsonArray(value.map { it.export() })
    is ConvexValue.Object -> kotlinx.serialization.json.JsonObject(value.mapValues { it.value.export() })
}

private fun exportFloat(value: Double): JsonElement = when {
    value.isNaN() -> JsonPrimitive(NAN)
    value == Double.POSITIVE_INFINITY -> JsonPrimitive(POSITIVE_INFINITY)
    value == Double.NEGATIVE_INFINITY -> JsonPrimitive(NEGATIVE_INFINITY)
    // JsonPrimitive(Double) preserves -0.0's sign, matching upstream, which
    // distinguishes positive and negative zero on the JSON number path.
    else -> JsonPrimitive(value)
}

private const val NAN = "NaN"
private const val POSITIVE_INFINITY = "Infinity"
private const val NEGATIVE_INFINITY = "-Infinity"

/**
 * The type information an exported value needs to be reconstructed.
 *
 * Exporting is lossy — integers become strings, bytes become base64 — so the
 * original [ConvexValue] shape has to travel alongside the JSON. [of] derives
 * the context from a value; [importExported] consumes it to invert [export].
 * Mirrors the upstream `convex-rs` `ExportContext`.
 *
 * [Float64] stores the original NaN because every NaN exports as the string
 * `"NaN"`, so a plain inversion could not recover a specific NaN bit pattern.
 */
public sealed interface ExportContext {
    /** Corresponds to [ConvexValue.Null]. */
    public data object Null : ExportContext

    /** Corresponds to a decimal-string [ConvexValue.Int64]. */
    public data object Int64 : ExportContext

    /**
     * Corresponds to a [ConvexValue.Float64].
     *
     * @property nanValue the original NaN, when the value was one.
     */
    public data class Float64(public val nanValue: Double? = null) : ExportContext

    /** Corresponds to a [ConvexValue.Boolean]. */
    public data object Boolean : ExportContext

    /** Corresponds to a [ConvexValue.String]. */
    public data object String : ExportContext

    /** Corresponds to a base64 [ConvexValue.Bytes]. */
    public data object Bytes : ExportContext

    /**
     * Corresponds to a [ConvexValue.Array].
     *
     * @property elements the context of each element, in order.
     */
    public data class Array(public val elements: List<ExportContext>) : ExportContext

    /**
     * Corresponds to a [ConvexValue.Object].
     *
     * @property fields the context of each field, keyed by field name.
     */
    public data class Object(public val fields: Map<kotlin.String, ExportContext>) : ExportContext

    /** Derivation. */
    public companion object {
        /**
         * Derives the export context of a value.
         *
         * @param value the value being exported.
         * @return the context needed to invert the export.
         */
        public fun of(value: ConvexValue): ExportContext = when (value) {
            ConvexValue.Null -> Null
            is ConvexValue.Int64 -> Int64
            is ConvexValue.Float64 -> Float64(nanValue = value.value.takeIf { it.isNaN() })
            is ConvexValue.Boolean -> Boolean
            is ConvexValue.String -> String
            is ConvexValue.Bytes -> Bytes
            is ConvexValue.Array -> Array(value.value.map(::of))
            is ConvexValue.Object -> Object(value.value.mapValues { of(it.value) })
        }
    }
}

/**
 * Reconstructs a value from its exported JSON and the matching [ExportContext].
 *
 * The inverse of [export]; together they round-trip a value when the context is
 * derived from the same value via [ExportContext.of].
 *
 * @param exported the JSON produced by [export].
 * @param context the type hint that disambiguates the lossy export.
 * @return the reconstructed value.
 * @throws eu.wynq.convex.core.ConvexException when [exported] does not match
 *   [context].
 */
public fun importExported(exported: JsonElement, context: ExportContext): ConvexValue = when (context) {
    ExportContext.Null -> ConvexValue.Null
    ExportContext.Int64 -> importInt64(exported)
    is ExportContext.Float64 -> importFloat64(exported, context.nanValue)
    ExportContext.Boolean -> importBoolean(exported)
    ExportContext.String -> importString(exported)
    ExportContext.Bytes -> importBytes(exported)
    is ExportContext.Array -> importArray(exported, context.elements)
    is ExportContext.Object -> importObject(exported, context.fields)
}

private fun importInt64(exported: JsonElement): ConvexValue.Int64 {
    val text = (exported as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ConvexException("Unexpected value for i64")
    val value = text.toLongOrNull() ?: throw ConvexException("Unexpected string for i64")
    return ConvexValue.Int64(value)
}

private fun importFloat64(exported: JsonElement, nanValue: Double?): ConvexValue.Float64 {
    if (nanValue != null) return importNan(exported, nanValue)
    val primitive = exported as? JsonPrimitive ?: throw ConvexException("Unexpected value for f64")
    return importNumberOrInfinity(primitive)
}

private fun importNan(exported: JsonElement, nanValue: Double): ConvexValue.Float64 {
    if (!nanValue.isNaN()) throw ConvexException("Unexpected non-NaN value in the export context")
    if (exported != JsonPrimitive(NAN)) throw ConvexException("Unexpected serialization of a NaN value")
    return ConvexValue.Float64(nanValue)
}

private fun importNumberOrInfinity(primitive: JsonPrimitive): ConvexValue.Float64 {
    if (primitive.isString) {
        return when (primitive.content) {
            POSITIVE_INFINITY -> ConvexValue.Float64(Double.POSITIVE_INFINITY)
            NEGATIVE_INFINITY -> ConvexValue.Float64(Double.NEGATIVE_INFINITY)
            else -> throw ConvexException("Unexpected string for f64")
        }
    }
    return ConvexValue.Float64(primitive.doubleOrNull ?: throw ConvexException("Unexpected number for f64"))
}

private fun importBoolean(exported: JsonElement): ConvexValue.Boolean {
    val value = (exported as? JsonPrimitive)?.booleanOrNull
        ?: throw ConvexException("Unexpected value for boolean")
    return ConvexValue.Boolean(value)
}

private fun importString(exported: JsonElement): ConvexValue.String {
    val primitive = exported as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw ConvexException("Unexpected value for string")
    return ConvexValue.String(primitive.content)
}

private fun importBytes(exported: JsonElement): ConvexValue.Bytes {
    val text = (exported as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ConvexException("Unexpected value for bytes")
    return ConvexValue.Bytes(decodeBytesOrThrow(text))
}

private fun decodeBytesOrThrow(text: String): ByteArray = try {
    LittleEndianBase64.decodeBytes(text)
} catch (failure: IllegalArgumentException) {
    throw ConvexException("Unexpected string for bytes", failure)
}

private fun importArray(exported: JsonElement, elements: List<ExportContext>): ConvexValue.Array {
    val array = exported as? JsonArray ?: throw ConvexException("Unexpected value for array")
    if (array.size != elements.size) throw ConvexException("Array lengths do not match")
    return ConvexValue.Array(array.mapIndexed { index, element -> importExported(element, elements[index]) })
}

private fun importObject(exported: JsonElement, fields: Map<kotlin.String, ExportContext>): ConvexValue.Object {
    val json = exported as? JsonObject ?: throw ConvexException("Unexpected value for object")
    val rebuilt = linkedMapOf<kotlin.String, ConvexValue>()
    for ((key, value) in json) {
        val hint = fields[key] ?: throw ConvexException("Missing export context for an object key")
        rebuilt[key] = importExported(value, hint)
    }
    return ConvexValue.Object(rebuilt)
}
