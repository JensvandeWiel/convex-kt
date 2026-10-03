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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers identifier and field-name validation.
 *
 * These mirror the upstream `convex-rs` `sync_types/src/identifier.rs` tests
 * one for one. Upstream proves the fast paths with proptest; Kotlin has no
 * property runner, so the same agreement is asserted over a fixed corpus that
 * hits every boundary instead: empty, reserved, overlong, control, DEL,
 * non-ASCII, and astral inputs.
 */
class IdentifierValidationTest {

    @Test
    fun minIdentifierOrdersBeforeGeneratedIdentifiers() {
        // The upstream regex `[a-zA-Z_][a-zA-Z][a-zA-Z0-9_]{0,62}` only
        // produces names at or after "A"; these representatives span its
        // starts, bodies, and maximum length.
        val generated = listOf(
            "Aa",
            "B2",
            "Zz",
            "_a",
            "_A9",
            "a0",
            "abc_def9",
            "A" + "0".repeat(63),
            "z" + "_".repeat(63),
            "_" + "a".repeat(63),
        )
        for (identifier in generated) {
            assertTrue(MIN_IDENTIFIER <= identifier, "expected $MIN_IDENTIFIER <= $identifier")
        }
        assertEquals(Identifier(MIN_IDENTIFIER), Identifier.min())
        assertTrue(Identifier.min() <= Identifier("users"))
    }

    @Test
    fun identifierFastPathMatchesSlowPath() {
        for (candidate in identifierCorpus) {
            val fast = isValidIdentifier(candidate)
            val slow = runCatching { checkValidIdentifier(candidate) }.isSuccess
            assertEquals(slow, fast, "fast/slow disagree on ${candidate.debug()}")
        }
    }

    @Test
    fun fieldNameFastPathMatchesSlowPath() {
        for (candidate in fieldNameCorpus) {
            val fast = isValidFieldName(candidate)
            val slow = runCatching { checkValidFieldName(candidate) }.isSuccess
            assertEquals(slow, fast, "fast/slow disagree on ${candidate.debug()}")
        }
    }

    @Test
    fun controlCharInIdentifierBody() {
        val failure = assertFailsWith<ConvexIdentifierException> {
            checkValidIdentifier("abc" + CONTROL_CHAR + "def")
        }
        assertTrue(failure.message!!.contains("'\\u{10}'"), "got: ${failure.message}")
    }

    @Test
    fun controlCharAsIdentifierStart() {
        val failure = assertFailsWith<ConvexIdentifierException> {
            checkValidIdentifier(CONTROL_CHAR + "abc")
        }
        assertTrue(failure.message!!.contains("'\\u{10}'"), "got: ${failure.message}")
    }

    @Test
    fun controlCharInFieldName() {
        val failure = assertFailsWith<ConvexIdentifierException> {
            checkValidFieldName("field" + CONTROL_CHAR + "name")
        }
        assertTrue(failure.message!!.contains("'\\u{10}'"), "got: ${failure.message}")
    }

    @Test
    fun newlineInIdentifier() {
        val failure = assertFailsWith<ConvexIdentifierException> {
            checkValidIdentifier("abc\ndef")
        }
        assertTrue(failure.message!!.contains("'\\n'"), "got: ${failure.message}")
    }

    @Test
    fun regularCharInIdentifierBody() {
        val failure = assertFailsWith<ConvexIdentifierException> {
            checkValidIdentifier("abc@def")
        }
        assertTrue(failure.message!!.contains("'@'"), "got: ${failure.message}")
    }

    @Test
    fun regularCharAsIdentifierStart() {
        val failure = assertFailsWith<ConvexIdentifierException> {
            checkValidIdentifier("9abc")
        }
        assertTrue(failure.message!!.contains("'9'"), "got: ${failure.message}")
    }

    @Test
    fun emojiInFieldName() {
        val failure = assertFailsWith<ConvexIdentifierException> {
            checkValidFieldName("field😀name")
        }
        assertTrue(failure.message!!.contains("'😀'"), "got: ${failure.message}")
    }

    @Test
    fun validIdentifiersAreAccepted() {
        for (identifier in listOf("A", "a", "_a", "a_", "abc_def9", "A" + "0".repeat(63))) {
            assertTrue(isValidIdentifier(identifier), "expected valid: $identifier")
            checkValidIdentifier(identifier)
            assertEquals(identifier, Identifier(identifier).value)
        }
        assertFailsWith<ConvexIdentifierException> { checkValidIdentifier("") }
        assertFailsWith<ConvexIdentifierException> { checkValidIdentifier("___") }
        assertFailsWith<ConvexIdentifierException> { Identifier("9abc") }
    }

    @Test
    fun fieldNameBoundariesAreAccepted() {
        assertTrue(isValidFieldName(""))
        assertTrue(isValidFieldName("a\$b"))
        assertTrue(isValidFieldName("field name"))
        assertTrue(isValidFieldName("a".repeat(MAX_FIELD_NAME_LENGTH)))
        assertFailsWith<ConvexIdentifierException> { checkValidFieldName("\$abc") }
        assertFailsWith<ConvexIdentifierException> {
            checkValidFieldName("a".repeat(MAX_FIELD_NAME_LENGTH + 1))
        }
    }

    private val identifierCorpus = listOf(
        "",
        "A",
        "a",
        "_",
        "___",
        "_a",
        "a_",
        "a1",
        "9abc",
        "abc@def",
        "abc def",
        "abc\ndef",
        "abc" + CONTROL_CHAR + "def",
        CONTROL_CHAR + "abc",
        "é",
        "éabc",
        "😀",
        "a".repeat(64),
        "a".repeat(65),
        "_".repeat(64),
        "a".repeat(63) + "é",
        "\$abc",
        "a\$b",
        "abc" + DELETE_CHAR + "def",
        DELETE_CHAR + "abc",
    )

    private val fieldNameCorpus = listOf(
        "",
        "\$",
        "\$abc",
        "a\$b",
        "a\$",
        "abc",
        "field name",
        "field" + CONTROL_CHAR + "name",
        "field😀name",
        "\nabc",
        "日本語",
        "a".repeat(1024),
        "a".repeat(1025),
        "é".repeat(512),
        "abc" + DELETE_CHAR + "def",
        DELETE_CHAR.toString(),
    )

    /** Renders control characters visibly so a corpus mismatch names the input. */
    private fun String.debug(): String = map { codePointDebug(it) }.joinToString("")

    private fun codePointDebug(char: Char): String = when (char) {
        '\n' -> "\\n"
        ' ' -> "<space>"
        CONTROL_CHAR, DELETE_CHAR -> "\\u{" + char.code.toString(16) + "}"
        else -> char.toString()
    }

    private companion object {
        /** U+0010, the exact control character the upstream vectors use. */
        val CONTROL_CHAR = 0x10.toChar()

        /** U+007F DEL, control-adjacent: rejected but not matched by `< 0x20`. */
        val DELETE_CHAR = 0x7F.toChar()
    }
}
