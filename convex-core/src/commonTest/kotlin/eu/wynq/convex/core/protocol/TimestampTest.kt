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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [Timestamp], the port of the upstream `timestamp` tests.
 */
class TimestampTest {

    @Test
    fun secondsSinceIsPositiveZeroForEqualTimestamps() {
        // The upstream `test_secs_since_f64_positive_zero` asserts the result is
        // positive zero, not negative zero.
        val timestamp = Timestamp(1234u)
        val zero = timestamp.secondsSince(timestamp)
        assertEquals(0L, zero.toRawBits())
    }

    @Test
    fun secondsSinceIsSignedAndNanosecondBased() {
        val base = Timestamp(1_000_000_000u)
        assertEquals(1.5, Timestamp(2_500_000_000u).secondsSince(base))
        assertEquals(-0.5, Timestamp(500_000_000u).secondsSince(base))
    }
}
