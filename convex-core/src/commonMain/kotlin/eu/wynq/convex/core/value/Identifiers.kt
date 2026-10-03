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

import kotlin.jvm.JvmInline

/**
 * Validation for Convex table names, field names, and other identifiers.
 *
 * This mirrors the upstream `convex-rs` `sync_types/src/identifier.rs`: a
 * simplified ASCII grammar (`start: a-zA-Z_`, `continue: a-zA-Z0-9_`, at least
 * one non-underscore character, at most 64 bytes) for identifiers, and a
 * looser rule for field names (non-control ASCII, at most 1024 bytes, never
 * starting with `$`, which is reserved).
 *
 * Lengths are UTF-8 bytes, matching Rust's `str::len`, so a non-ASCII name
 * exhausts its budget faster than its [String.length] suggests. The fast
 * boolean checks and the throwing checks always agree; the throwing variants
 * exist to name the offending character, in Rust `{:?}` notation.
 */
public const val MAX_IDENTIFIER_LEN: Int = 64

/** The human-readable grammar summary, kept next to the checks that enforce it. */
public const val IDENTIFIER_REQUIREMENTS: String =
    "Identifiers must start with a letter and can only contain letters, digits, and underscores."

/** The smallest valid identifier; every generated identifier sorts at or after it. */
public const val MIN_IDENTIFIER: String = "A"

/** Maximum length of a field name in UTF-8 bytes. */
public const val MAX_FIELD_NAME_LENGTH: Int = 1024

/**
 * Whether [value] is a valid Convex identifier (table name or field name in a
 * document).
 *
 * This is the fast path: a byte-wise scan with no allocation beyond the UTF-8
 * encoding. It agrees with [checkValidIdentifier] on every input; use that
 * when the caller needs the reason, not just the verdict.
 *
 * @param value the candidate name.
 * @return `true` when the backend would accept the name.
 */
public fun isValidIdentifier(value: String): Boolean {
    val bytes = value.encodeToByteArray()
    if (bytes.size > MAX_IDENTIFIER_LEN) return false
    val first = bytes.firstOrNull()?.toUnsigned() ?: return false
    if (!first.isAsciiLetter() && first != ASCII_UNDERSCORE) return false
    return bytesContainName(bytes)
}

/**
 * Rejects [value] unless it is a valid Convex identifier.
 *
 * @param value the candidate name.
 * @throws ConvexIdentifierException naming the offending character.
 */
public fun checkValidIdentifier(value: String) {
    if (!isValidIdentifier(value)) {
        throwInvalidIdentifier(value)
    }
}

/**
 * Whether [value] is a valid Convex object field name.
 *
 * Field names are looser than identifiers: any non-control ASCII goes,
 * including a `$` anywhere but first, and the empty name is accepted. Like
 * [isValidIdentifier], this is the fast path that agrees with
 * [checkValidFieldName] on every input.
 *
 * @param value the candidate name.
 * @return `true` when the backend would accept the name.
 */
public fun isValidFieldName(value: String): Boolean {
    val bytes = value.encodeToByteArray()
    if (bytes.firstOrNull()?.toUnsigned() == ASCII_DOLLAR) return false
    if (bytes.size > MAX_FIELD_NAME_LENGTH) return false
    return bytesAreFieldName(bytes)
}

/**
 * Rejects [value] unless it is a valid Convex object field name.
 *
 * @param value the candidate name.
 * @throws ConvexIdentifierException naming the offending character.
 */
public fun checkValidFieldName(value: String) {
    if (!isValidFieldName(value)) {
        throwInvalidFieldName(value)
    }
}

/**
 * A validated Convex identifier, such as a table name.
 *
 * Construction validates, so an [Identifier] value is proof the name passed
 * [checkValidIdentifier]. Ordering is plain lexicographic string ordering,
 * matching the derived `Ord` upstream.
 *
 * @property value the validated name.
 */
@JvmInline
public value class Identifier(public val value: String) : Comparable<Identifier> {
    init {
        checkValidIdentifier(value)
    }

    /**
     * Compares identifiers lexicographically by their text.
     *
     * @param other the identifier to compare against.
     * @return negative, zero, or positive when this sorts before, ties, or after [other].
     */
    public override fun compareTo(other: Identifier): Int = value.compareTo(other.value)

    /**
     * Returns the identifier text.
     *
     * @return [value].
     */
    public override fun toString(): String = value

    /** Construction helpers and well-known identifiers. */
    public companion object {
        /**
         * The smallest valid identifier.
         *
         * @return the identifier `"A"`, which sorts before every generated name.
         */
        public fun min(): Identifier = Identifier(MIN_IDENTIFIER)
    }
}

private fun bytesContainName(bytes: ByteArray): Boolean {
    var hasNonUnderscore = false
    for (byte in bytes) {
        val unit = byte.toUnsigned()
        if (unit.isAsciiLetterOrDigit()) {
            hasNonUnderscore = true
        } else if (unit != ASCII_UNDERSCORE) {
            return false
        }
    }
    return hasNonUnderscore
}

private fun bytesAreFieldName(bytes: ByteArray): Boolean {
    for (byte in bytes) {
        val unit = byte.toUnsigned()
        if (unit < ASCII_PRINTABLE_START || unit >= ASCII_DELETE) return false
    }
    return true
}

/**
 * Runs the detailed identifier scan and throws for the first failure.
 *
 * Only reached when [isValidIdentifier] already said no, so the reason below
 * always names a real violation; computing the message separately keeps each
 * function within the single-throw budget.
 */
private fun throwInvalidIdentifier(value: String): Nothing =
    throw ConvexIdentifierException(invalidIdentifierReason(value))

private fun invalidIdentifierReason(value: String): String {
    if (value.isEmpty()) return "Identifier cannot be empty"
    val badStart = badIdentifierStart(value)
    val badBody = badIdentifierBody(value)
    val byteLength = value.encodeToByteArray().size
    return when {
        badStart != null -> badStart
        badBody != null -> badBody
        byteLength > MAX_IDENTIFIER_LEN -> "Identifier is too long ($byteLength > maximum $MAX_IDENTIFIER_LEN)"
        value.all { it == '_' } -> "Identifier $value cannot have exclusively underscores"
        // Unreachable: the fast path rejected `value`, so a check above fired.
        else -> "Identifier $value is invalid"
    }
}

private fun badIdentifierStart(value: String): String? {
    val (first, _) = codePointAt(value, 0)
    if (!first.isAsciiLetter() && first != ASCII_UNDERSCORE) {
        return "Invalid first character ${debugChar(first)} in $value: " +
            "Identifiers must start with an alphabetic character or underscore"
    }
    return null
}

private fun badIdentifierBody(value: String): String? {
    var index = 0
    while (index < value.length) {
        val (codePoint, width) = codePointAt(value, index)
        if (!codePoint.isAsciiLetterOrDigit() && codePoint != ASCII_UNDERSCORE) {
            return "Identifier $value has invalid character ${debugChar(codePoint)}: " +
                "Identifiers can only contain alphanumeric characters or underscores"
        }
        index += width
    }
    return null
}

/**
 * Runs the detailed field-name scan and throws for the first failure.
 *
 * Only reached when [isValidFieldName] already said no; the message is
 * computed separately to keep each function within the single-throw budget.
 */
private fun throwInvalidFieldName(value: String): Nothing =
    throw ConvexIdentifierException(invalidFieldNameReason(value))

private fun invalidFieldNameReason(value: String): String {
    val badBody = badFieldNameBody(value)
    val byteLength = value.encodeToByteArray().size
    return when {
        value.startsWith('$') -> "Field name $value starts with '$', which is reserved."
        badBody != null -> badBody
        byteLength > MAX_FIELD_NAME_LENGTH -> "Field name is too long ($byteLength > maximum $MAX_FIELD_NAME_LENGTH)"
        // Unreachable: the fast path rejected `value`, so a check above fired.
        else -> "Field name $value is invalid"
    }
}

private fun badFieldNameBody(value: String): String? {
    var index = 0
    while (index < value.length) {
        val (codePoint, width) = codePointAt(value, index)
        if (codePoint < ASCII_PRINTABLE_START || codePoint >= ASCII_DELETE) {
            return "Field name $value has invalid character ${debugChar(codePoint)}: " +
                "Field names can only contain non-control ASCII characters"
        }
        index += width
    }
    return null
}

/**
 * Reads one Unicode scalar value as `(code point, UTF-16 width)`.
 *
 * Kotlin strings are UTF-16, so astral characters (emoji, for example) arrive
 * as surrogate pairs; pairing them here keeps error messages naming the
 * character (`'😀'`), the way Rust's `{:?}` does, instead of naming halves.
 */
private fun codePointAt(value: String, index: Int): Pair<Int, Int> {
    val high = value[index]
    if (!high.isHighSurrogate() || index + 1 >= value.length) return Pair(high.code, 1)
    val low = value[index + 1]
    if (!low.isLowSurrogate()) return Pair(high.code, 1)
    val codePoint = ASTRAL_PLANE_START +
        ((high.code - HIGH_SURROGATE_BASE) shl SURROGATE_BITS) +
        (low.code - LOW_SURROGATE_BASE)
    return Pair(codePoint, 2)
}

/**
 * Formats a code point the way Rust's `{:?}` formats a `char`: C escapes for
 * the classic controls, `'\u{10}'` for other controls, the character itself
 * otherwise.
 */
private fun debugChar(codePoint: Int): String = when (codePoint) {
    '\t'.code -> "'\\t'"
    '\n'.code -> "'\\n'"
    '\r'.code -> "'\\r'"
    '\\'.code -> "'\\\\'"
    '\''.code -> "'\\''"
    '"'.code -> "'\\\"'"
    else -> if (isControlCodePoint(codePoint)) {
        "'\\u{" + codePoint.toString(HEX_RADIX) + "}'"
    } else {
        "'" + codePointToString(codePoint) + "'"
    }
}

/** Whether [codePoint] is a Cc control (ASCII controls and the C1 range). */
private fun isControlCodePoint(codePoint: Int): Boolean =
    codePoint in ASCII_CONTROL_START..ASCII_CONTROL_END || codePoint in ASCII_DELETE..C1_CONTROL_END

private fun codePointToString(codePoint: Int): String {
    if (codePoint < ASTRAL_PLANE_START) return codePoint.toChar().toString()
    val offset = codePoint - ASTRAL_PLANE_START
    val high = ((offset shr SURROGATE_BITS) + HIGH_SURROGATE_BASE).toChar()
    val low = ((offset and SURROGATE_MASK) + LOW_SURROGATE_BASE).toChar()
    return charArrayOf(high, low).concatToString()
}

private fun Byte.toUnsigned(): Int = toInt() and BYTE_MASK

private fun Int.isAsciiLetter(): Boolean =
    this in ASCII_UPPER_A..ASCII_UPPER_Z || this in ASCII_LOWER_A..ASCII_LOWER_Z

private fun Int.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in ASCII_ZERO..ASCII_NINE

/** Masks a signed byte down to its unsigned value. */
private const val BYTE_MASK = 0xFF

private const val ASCII_ZERO = 0x30
private const val ASCII_NINE = 0x39
private const val ASCII_UPPER_A = 0x41
private const val ASCII_UPPER_Z = 0x5A
private const val ASCII_LOWER_A = 0x61
private const val ASCII_LOWER_Z = 0x7A
private const val ASCII_UNDERSCORE = 0x5F
private const val ASCII_DOLLAR = 0x24

/** First printable ASCII character (space); anything below is a control. */
private const val ASCII_PRINTABLE_START = 0x20

/** DEL; the fast field-name scan rejects everything from here up. */
private const val ASCII_DELETE = 0x7F

private const val ASCII_CONTROL_START = 0x00
private const val ASCII_CONTROL_END = 0x1F

/** End of the C1 control range. */
private const val C1_CONTROL_END = 0x9F

/** First code point encoded as a UTF-16 surrogate pair. */
private const val ASTRAL_PLANE_START = 0x10000

private const val HIGH_SURROGATE_BASE = 0xD800
private const val LOW_SURROGATE_BASE = 0xDC00

/** A surrogate carries 10 payload bits; `and` with this keeps the low half. */
private const val SURROGATE_BITS = 10
private const val SURROGATE_MASK = 0x3FF

/** Rust renders control code points in lowercase hexadecimal. */
private const val HEX_RADIX = 16
