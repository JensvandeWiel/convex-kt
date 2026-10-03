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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers the typed-argument codec: encoding a `@Serializable` argument into a
 * [ConvexValue] and decoding a [ConvexValue] result back into Kotlin.
 *
 * The tests that matter most are the numeric ones. A Convex `Int64` and
 * `Float64` are different types on the wire, but a JSON number looks identical
 * either way, so the encoder must recover the distinction from the number's
 * lexical form. If that is wrong, `42L` silently becomes `42.0`, which is a
 * different Convex value.
 */
class ConvexValueEncoderTest {

    @Serializable
    private data class Nested(val label: String)

    @Serializable
    private data class Args(
        val text: String,
        val count: Long,
        val ratio: Double,
        val flag: Boolean,
        val optional: String? = null,
        val nested: Nested,
        val tags: List<String>,
        val maybe: ConvexValue? = null,
    )

    @Test
    fun encodesPrimitivesToTheirConvexTypes() {
        val encoded = ConvexValueEncoder.encode(Nested.serializer(), Nested("hi"))
        val obj = assertIs<ConvexValue.Object>(encoded)
        assertEquals(ConvexValue.String("hi"), obj.value["label"])
    }

    @Test
    fun preservesInt64FromALong() {
        // A Long must become Int64, not Float64. This is the whole point of the
        // encoder: Json.encodeToJsonElement writes `42`, not `42.0`, but a naive
        // decode that tries Double first would produce Float64(42.0).
        val encoded = ConvexValueEncoder.encode(Nested.serializer(), Nested("x"))
        assertIs<ConvexValue.Object>(encoded)

        val numbers = ConvexValueEncoder.encode(
            CountOnly.serializer(),
            CountOnly(count = 42L),
        )
        val obj = assertIs<ConvexValue.Object>(numbers)
        assertEquals(ConvexValue.Int64(42L), obj.value["count"])
    }

    @Serializable
    private data class CountOnly(val count: Long)

    @Test
    fun preservesFloat64EvenWhenItIsWhole() {
        val encoded = ConvexValueEncoder.encode(RatioOnly.serializer(), RatioOnly(ratio = 2.0))
        val obj = assertIs<ConvexValue.Object>(encoded)
        // 2.0 has a decimal point, so it must stay a Float64 even though it is a
        // whole number. Treating it as Int64 would change the Convex type.
        assertEquals(ConvexValue.Float64(2.0), obj.value["ratio"])
    }

    @Serializable
    private data class RatioOnly(val ratio: Double)

    @Test
    fun encodesTheFullArgumentShape() {
        val args = Args(
            text = "hello",
            count = 7L,
            ratio = 1.5,
            flag = true,
            nested = Nested("inner"),
            tags = listOf("a", "b"),
        )
        val obj = assertIs<ConvexValue.Object>(ConvexValueEncoder.encode(Args.serializer(), args))
        assertEquals(ConvexValue.String("hello"), obj.value["text"])
        assertEquals(ConvexValue.Int64(7L), obj.value["count"])
        assertEquals(ConvexValue.Float64(1.5), obj.value["ratio"])
        assertEquals(ConvexValue.Boolean(true), obj.value["flag"])
        assertEquals(
            ConvexValue.Object(mapOf("label" to ConvexValue.String("inner"))),
            obj.value["nested"],
        )
        assertEquals(
            ConvexValue.Array(listOf(ConvexValue.String("a"), ConvexValue.String("b"))),
            obj.value["tags"],
        )
    }

    @Test
    fun omitsNullsAndKeepsUnmodelledValues() {
        // A nullable field with no value is omitted (encodeDefaults is off), so
        // an absent optional does not become an explicit null on the wire. A
        // ConvexValue-typed field is carried through by its own serializer.
        val args = Args(
            text = "t",
            count = 1L,
            ratio = 0.5,
            flag = false,
            nested = Nested("n"),
            tags = emptyList(),
            maybe = ConvexValue.Int64(9L),
        )
        val obj = assertIs<ConvexValue.Object>(ConvexValueEncoder.encode(Args.serializer(), args))
        assertNull(obj.value["optional"])
        assertEquals(ConvexValue.Int64(9L), obj.value["maybe"])
    }

    @Test
    fun decodesInt64ResultIntoLong() {
        val value = ConvexValue.Int64(5L)
        assertEquals(5L, ConvexValueDecoder.decode(Long.serializer(), value))
    }

    @Test
    fun decodesWholeFloatResultIntoDouble() {
        assertEquals(2.0, ConvexValueDecoder.decode(Double.serializer(), ConvexValue.Float64(2.0)))
    }

    @Test
    fun decodesAnObjectResultIntoADataClass() {
        val value = ConvexValue.Object(mapOf("label" to ConvexValue.String("decoded")))
        assertEquals(Nested("decoded"), ConvexValueDecoder.decode(Nested.serializer(), value))
    }

    @Test
    fun decodesAListOfObjects() {
        val value = ConvexValue.Array(
            listOf(
                ConvexValue.Object(mapOf("label" to ConvexValue.String("a"))),
                ConvexValue.Object(mapOf("label" to ConvexValue.String("b"))),
            ),
        )
        val decoded = ConvexValueDecoder.decode(ListSerializer(Nested.serializer()), value)
        assertEquals(listOf(Nested("a"), Nested("b")), decoded)
    }

    private val json = Json

    @Test
    fun valueSerializerRoundTripsInt64WithoutPrecisionLoss() {
        // The untyped fallback fields in generated classes use ConvexValue's own
        // serializer. It must carry a full 64-bit integer without going through
        // a Double, or an id-sized number would lose its low bits.
        val big = 9_007_199_254_740_993L // 2^53 + 1, not representable as Double
        val text = json.encodeToString(ConvexValueSerializer, ConvexValue.Int64(big))
        val decoded = json.decodeFromString(ConvexValueSerializer, text)
        assertEquals(ConvexValue.Int64(big), decoded)
    }

    @Test
    fun valueSerializerRoundTripsBytes() {
        val bytes = ConvexValue.Bytes(byteArrayOf(1, 2, 3, -1))
        val text = json.encodeToString(ConvexValueSerializer, bytes)
        assertEquals(bytes, json.decodeFromString(ConvexValueSerializer, text))
    }

    @Test
    fun valueSerializerRoundTripsNestedStructures() {
        val value = ConvexValue.Object(
            mapOf(
                "n" to ConvexValue.Int64(1L),
                "f" to ConvexValue.Float64(1.5),
                "b" to ConvexValue.Boolean(false),
                "s" to ConvexValue.String("s"),
                "l" to ConvexValue.Array(listOf(ConvexValue.Null, ConvexValue.Int64(2L))),
            ),
        )
        val text = json.encodeToString(ConvexValueSerializer, value)
        assertEquals(value, json.decodeFromString(ConvexValueSerializer, text))
    }

    @Test
    fun valueSerializerReadsATaggedWireInteger() {
        // A value that took the wire path is a tagged object; the serializer
        // must still read it, so the two encodings are interoperable.
        // 2 as little-endian bytes is 02 00 00 00 00 00 00 00 -> "AgAAAAAAAAA=".
        val decoded = json.decodeFromString(ConvexValueSerializer, """{"${'$'}integer":"AgAAAAAAAAA="}""")
        assertEquals(ConvexValue.Int64(2L), decoded)
    }
}
