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

import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * A sync session identifier: a UUID rendered in canonical hyphenated lowercase.
 *
 * The backend rejects anything that is not a well-formed UUID, so the format is
 * validated at construction and the type cannot represent an invalid session.
 *
 * @property value the canonical hyphenated form.
 */
@JvmInline
public value class SessionId private constructor(public val value: String) {
    override fun toString(): String = value

    /** Parsing and generation of session ids. */
    public companion object {
        private val CANONICAL = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        /** Byte offsets in the 16-byte UUID that carry a hyphen in the text form. */
        private val HYPHEN_OFFSETS = setOf(4, 6, 8, 10)

        /**
         * Parses a canonical hyphenated UUID.
         *
         * @param text the UUID text, case-insensitive.
         * @return the parsed session id.
         * @throws IllegalArgumentException when the text is not a UUID.
         */
        public fun parse(text: String): SessionId {
            val canonical = text.lowercase()
            require(CANONICAL.matches(canonical)) { "session id must be a UUID, got '$text'" }
            return SessionId(canonical)
        }

        /**
         * Generates a random (version 4, variant 1) UUID.
         *
         * @param random the randomness source; injectable for tests.
         * @return a fresh session id.
         */
        @Suppress("MagicNumber")
        public fun random(random: Random = Random): SessionId {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            // Version 4 (high nibble of byte 6) and variant 1 (top two bits of byte 8).
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            return SessionId(
                buildString(36) {
                    bytes.forEachIndexed { index, byte ->
                        if (index in HYPHEN_OFFSETS) append('-')
                        append(HEX[(byte.toInt() ushr 4) and 0x0F])
                        append(HEX[byte.toInt() and 0x0F])
                    }
                },
            )
        }

        private const val HEX = "0123456789abcdef"
    }
}

/**
 * A Convex timestamp: an unsigned 64-bit count of milliseconds.
 *
 * JSON numbers cannot hold it without precision loss, so it is encoded as
 * base64 little-endian bytes on the wire.
 *
 * @property value milliseconds since the Unix epoch.
 */
@JvmInline
public value class Timestamp(public val value: ULong)

/**
 * Identifies a subscribed query within a session.
 *
 * @property value the query number.
 */
@JvmInline
public value class QueryId(public val value: UInt)

/**
 * Monotonic version of the set of subscribed queries.
 *
 * @property value the version number.
 */
@JvmInline
public value class QuerySetVersion(public val value: UInt)

/**
 * Monotonic version of the session's authenticated identity.
 *
 * @property value the version number.
 */
@JvmInline
public value class IdentityVersion(public val value: UInt)

/**
 * Sequence number identifying a mutation or action request within a session.
 *
 * @property value the sequence number.
 */
@JvmInline
public value class RequestId(public val value: UInt)
