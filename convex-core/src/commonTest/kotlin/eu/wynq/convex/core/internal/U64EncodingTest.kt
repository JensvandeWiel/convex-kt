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
package eu.wynq.convex.core.internal

import eu.wynq.convex.core.value.ConvexJsonException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the shared little-endian base64 codec, including unsigned 64-bit
 * values which are the whole reason the encoding exists: a JSON number cannot
 * carry them without losing precision.
 */
class U64EncodingTest {

    @Test
    fun u64RoundTripsAcrossFullRange() {
        val values = listOf(
            0uL,
            1uL,
            255uL,
            256uL,
            65535uL,
            4294967295uL,
            4294967296uL,
            Long.MAX_VALUE.toULong(),
            ULong.MAX_VALUE,
        )
        values.forEach { value ->
            assertEquals(value, LittleEndianBase64.decodeULong(LittleEndianBase64.encodeULong(value)))
        }
    }

    @Test
    fun u64MatchesKnownWireValues() {
        assertEquals("AQAAAAAAAAA=", LittleEndianBase64.encodeULong(1uL))
        assertEquals("//////////8=", LittleEndianBase64.encodeULong(ULong.MAX_VALUE))
    }

    @Test
    fun longRoundTripsExtremes() {
        listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L).forEach { value ->
            assertEquals(value, LittleEndianBase64.decodeLong(LittleEndianBase64.encodeLong(value)))
        }
    }

    @Test
    fun doublePreservesNaNBits() {
        val nanBits = 0x7ff8000000000000L
        val decoded = LittleEndianBase64.decodeDouble(
            LittleEndianBase64.encodeDouble(Double.fromBits(nanBits)),
        )
        assertEquals(nanBits, decoded.toRawBits())
    }

    @Test
    fun wrongLengthIsRejected() {
        assertFailsWith<ConvexJsonException> { LittleEndianBase64.decodeULong("AQID") }
        assertFailsWith<ConvexJsonException> { LittleEndianBase64.decodeLong("AQID") }
    }
}
