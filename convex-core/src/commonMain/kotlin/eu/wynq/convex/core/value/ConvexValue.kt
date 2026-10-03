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

import kotlinx.serialization.Serializable

/**
 * A value that can be passed to, or returned from, a Convex function.
 *
 * These correspond to the supported Convex types. The hierarchy is closed so
 * that every consumer, especially the JSON codec, must handle each variant
 * explicitly: adding a Convex type makes the `when` expressions stop compiling
 * rather than silently dropping it.
 *
 * The names mirror the upstream `convex-rs` `Value` enum on purpose, so a port
 * can be diffed against its source without a translation table.
 */
@Serializable(with = ConvexValueSerializer::class)
public sealed interface ConvexValue {
    /** The absence of a value. Encoded as JSON `null`. */
    public data object Null : ConvexValue

    /**
     * A signed 64-bit integer.
     *
     * @property value the integer, preserved at full precision.
     */
    public data class Int64(public val value: Long) : ConvexValue

    /**
     * A 64-bit floating point number.
     *
     * Finite values are encoded as JSON numbers; negative zero and non-finite
     * values are tagged because JSON cannot represent them.
     *
     * @property value the number, compared bit-for-bit.
     */
    public data class Float64(public val value: Double) : ConvexValue

    /**
     * A boolean.
     *
     * @property value the boolean.
     */
    public data class Boolean(public val value: kotlin.Boolean) : ConvexValue

    /**
     * A UTF-8 string.
     *
     * @property value the text.
     */
    public data class String(public val value: kotlin.String) : ConvexValue

    /**
     * An opaque byte string.
     *
     * Equality is by content, not by array identity, which matters because
     * [ByteArray] alone would compare by reference and silently break
     * round-trip assertions.
     *
     * @property value the raw bytes; callers must not mutate the array.
     */
    public class Bytes(public val value: ByteArray) : ConvexValue {
        override fun equals(other: Any?): kotlin.Boolean =
            this === other || (other is Bytes && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): kotlin.String = "Bytes(${value.size} bytes)"
    }

    /**
     * An ordered list of values.
     *
     * @property value the elements.
     */
    public data class Array(public val value: List<ConvexValue>) : ConvexValue

    /**
     * A string-keyed map of values.
     *
     * @property value the fields. Keys are compared as a set; the codec sorts
     *   them when encoding so output is deterministic.
     */
    public data class Object(public val value: Map<kotlin.String, ConvexValue>) : ConvexValue
}
