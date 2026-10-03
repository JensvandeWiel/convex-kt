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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

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
