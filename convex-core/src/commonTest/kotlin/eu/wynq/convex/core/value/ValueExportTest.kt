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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [export], the plain-JSON "database types" projection.
 *
 * These mirror the upstream `convex-rs` `value/export` tests one for one. The
 * operation is intentionally **lossy** — an integer becomes a string, bytes
 * become base64 — so these assert the exact JSON, not a round trip.
 */
class ValueExportTest {

    /** Encodes an exported element compactly, matching the upstream `json!` text. */
    private fun exported(value: ConvexValue): String = Json.encodeToString(value.export())

    @Test
    fun bytesExportToBase64() {
        // The exact upstream vector: a 48-byte sequence (the bytes of the ASCII
        // alphabet, 0x00.., then 0x00) chosen so the base64 output walks the full
        // alphabet and ends in padding.
        val bytes = byteArrayOf(
            0, 16, -125, 16, 81, -121, 32,
            -110, -117, 48, -45, -113, 65, 20,
            -109, 81, 85, -105, 97, -106, -101,
            113, -41, -97, -126, 24, -93, -110,
            89, -89, -94, -102, -85, -78, -37,
            -81, -61, 28, -77, -45, 93, -73,
            -29, -98, -69, -13, -33, -65, 0,
        )
        assertEquals(
            "\"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/AA==\"",
            exported(ConvexValue.Bytes(bytes)),
        )
    }

    @Test
    fun nullExportsAsNull() {
        assertEquals("null", exported(ConvexValue.Null))
    }

    @Test
    fun booleansExportAsBooleans() {
        assertEquals("true", exported(ConvexValue.Boolean(true)))
        assertEquals("false", exported(ConvexValue.Boolean(false)))
    }

    @Test
    fun intsExportAsStrings() {
        assertEquals("\"1234\"", exported(ConvexValue.Int64(1234)))
        assertEquals("\"-314\"", exported(ConvexValue.Int64(-314)))
        assertEquals("\"0\"", exported(ConvexValue.Int64(0)))
        assertEquals("\"-9223372036854775808\"", exported(ConvexValue.Int64(Long.MIN_VALUE)))
        assertEquals("\"9223372036854775807\"", exported(ConvexValue.Int64(Long.MAX_VALUE)))
    }

    @Test
    fun finiteFloatsExportAsNumbers() {
        assertEquals("12.34", exported(ConvexValue.Float64(12.34)))
    }

    @Test
    fun positiveZeroExportsAsANegativeSignFreeNumber() {
        val element = ConvexValue.Float64(0.0).export() as JsonPrimitive
        assertEquals("0.0", element.content)
        assertEquals(Double.POSITIVE_INFINITY, 1.0 / element.double)
    }

    @Test
    fun negativeZeroExportsAsANumber() {
        val element = ConvexValue.Float64(-0.0).export() as JsonPrimitive
        assertEquals(-0.0, element.double)
        assertEquals(Double.NEGATIVE_INFINITY, 1.0 / element.double)
    }

    @Test
    fun infiniteFloatsExportAsStrings() {
        assertEquals("\"Infinity\"", exported(ConvexValue.Float64(Double.POSITIVE_INFINITY)))
        assertEquals("\"-Infinity\"", exported(ConvexValue.Float64(Double.NEGATIVE_INFINITY)))
    }

    @Test
    fun nanExportsAsAString() {
        assertEquals("\"NaN\"", exported(ConvexValue.Float64(Double.NaN)))
    }

    @Test
    fun stringsExportAsStrings() {
        assertEquals("\"hello\"", exported(ConvexValue.String("hello")))
    }

    @Test
    fun arraysExportAsArrays() {
        assertEquals(
            "[\"1\",\"2\",\"3\"]",
            exported(ConvexValue.Array(listOf(ConvexValue.Int64(1), ConvexValue.Int64(2), ConvexValue.Int64(3)))),
        )
    }

    @Test
    fun objectsExportAsObjects() {
        assertEquals(
            """{"a":"1","b":"2","c":"3"}""",
            exported(
                ConvexValue.Object(
                    linkedMapOf(
                        "a" to ConvexValue.Int64(1),
                        "b" to ConvexValue.Int64(2),
                        "c" to ConvexValue.Int64(3),
                    ),
                ),
            ),
        )
    }
}
