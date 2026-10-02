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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the [ConvexValue] JSON codec, with emphasis on the encodings that
 * cannot be inferred from the JSON shape alone: tagged little-endian base64
 * integers, non-finite floats, and the retired `$set`/`$map` types.
 */
class ConvexJsonTest {

    @Test
    fun int64UsesTaggedLittleEndianBase64() {
        // Expected strings are derived from the wire rules, not from the
        // decoder, so a byte-order regression cannot pass by symmetry.
        assertEquals(integer("AAAAAAAAAAA="), ConvexJson.encode(ConvexValue.Int64(0)))
        assertEquals(integer("AQAAAAAAAAA="), ConvexJson.encode(ConvexValue.Int64(1)))
        assertEquals(integer("//////////8="), ConvexJson.encode(ConvexValue.Int64(-1)))
    }

    @Test
    fun int64RoundTripsAtExtremes() {
        listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L).forEach { value ->
            assertEquals(ConvexValue.Int64(value), ConvexJson.decode(ConvexJson.encode(ConvexValue.Int64(value))))
        }
    }

    @Test
    fun finiteFloatUsesPlainNumber() {
        assertEquals("1.5", ConvexJson.encode(ConvexValue.Float64(1.5)))
        assertEquals(ConvexValue.Float64(1.5), ConvexJson.decode("1.5"))
    }

    @Test
    fun untaggedIntegerDecodesAsFloat() {
        assertEquals(ConvexValue.Float64(42.0), ConvexJson.decode("42"))
        assertEquals(ConvexValue.Float64(0.0), ConvexJson.decode("0"))
    }

    @Test
    fun nonFiniteFloatsUseTaggedBase64() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { special ->
            val encoded = ConvexJson.encode(ConvexValue.Float64(special))
            assertTrue(encoded.startsWith(prefix("float")), "expected a tagged float, got $encoded")
            val decoded = (ConvexJson.decode(encoded) as ConvexValue.Float64).value
            assertEquals(special.toRawBits(), decoded.toRawBits())
        }
    }

    @Test
    fun negativeZeroIsPreserved() {
        val encoded = ConvexJson.encode(ConvexValue.Float64(-0.0))
        val decoded = (ConvexJson.decode(encoded) as ConvexValue.Float64).value
        assertEquals((-0.0).toRawBits(), decoded.toRawBits())
    }

    @Test
    fun bytesUseTaggedBase64() {
        val value = ConvexValue.Bytes(byteArrayOf(1, 2, 3))
        assertEquals("""{"${'$'}bytes":"AQID"}""", ConvexJson.encode(value))
        assertEquals(value, ConvexJson.decode("""{"${'$'}bytes":"AQID"}"""))
    }

    @Test
    fun emptyArrayRoundTrips() {
        assertEquals("[]", ConvexJson.encode(ConvexValue.Array(emptyList())))
        assertEquals(ConvexValue.Array(emptyList()), ConvexJson.decode("[]"))
    }

    @Test
    fun objectsEncodeInSortedKeyOrder() {
        val value = ConvexValue.Object(
            mapOf(
                "b" to ConvexValue.Int64(2),
                "a" to ConvexValue.Int64(1),
            ),
        )
        assertEquals(
            """{"a":${integer("AQAAAAAAAAA=")},"b":${integer("AgAAAAAAAAA=")}}""",
            ConvexJson.encode(value),
        )
    }

    @Test
    fun nullBooleanAndStringRoundTrip() {
        listOf(
            ConvexValue.Null,
            ConvexValue.Boolean(true),
            ConvexValue.Boolean(false),
            ConvexValue.String("hello"),
            ConvexValue.String(""),
        ).forEach { value ->
            assertEquals(value, ConvexJson.decode(ConvexJson.encode(value)))
        }
    }

    @Test
    fun nestedStructuresRoundTrip() {
        val value = ConvexValue.Array(
            listOf(
                ConvexValue.Object(
                    mapOf(
                        "x" to ConvexValue.Int64(7),
                        "y" to ConvexValue.Array(listOf(ConvexValue.Null, ConvexValue.String("s"))),
                    ),
                ),
                ConvexValue.Bytes(byteArrayOf(0, -1)),
            ),
        )
        assertEquals(value, ConvexJson.decode(ConvexJson.encode(value)))
    }

    @Test
    fun valueRoundTripsTrophies() {
        // The upstream "trophy" values: non-finite floats that JSON cannot hold.
        val trophies = listOf(
            ConvexValue.Float64(1.0),
            ConvexValue.Float64(Double.NaN),
            ConvexValue.Array(listOf(ConvexValue.Float64(Double.NaN))),
        )
        trophies.forEach { value ->
            assertEquals(value, ConvexJson.decode(ConvexJson.encode(value)))
        }
    }

    @Test
    fun retiredSetAndMapAreRejected() {
        assertFailsWith<ConvexJsonException> { ConvexJson.decode("""{"${'$'}set":[]}""") }
        assertFailsWith<ConvexJsonException> { ConvexJson.decode("""{"${'$'}map":{}}""") }
    }

    @Test
    fun taggedFloatThatWouldFitAsNumberIsRejected() {
        val text = """{"${'$'}float":"${LittleEndianBase64.encodeDouble(1.5)}"}"""
        assertFailsWith<ConvexJsonException> { ConvexJson.decode(text) }
    }

    @Test
    fun invalidJsonIsRejected() {
        assertFailsWith<ConvexJsonException> { ConvexJson.decode("not json") }
    }

    private fun integer(body: String): String = """{"${'$'}integer":"$body"}"""

    private fun prefix(tag: String): String = """{"${'$'}$tag":"""
}
