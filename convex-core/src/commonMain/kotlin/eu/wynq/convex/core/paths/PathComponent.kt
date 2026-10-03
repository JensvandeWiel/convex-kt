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
package eu.wynq.convex.core.paths

import eu.wynq.convex.core.ConvexException
import eu.wynq.convex.core.value.MAX_IDENTIFIER_LEN
import kotlin.jvm.JvmInline

/**
 * Rejects [value] unless it is a valid module-path component.
 *
 * A component is one slash-separated segment of a [ModulePath], filename
 * included. It may hold at most [MAX_IDENTIFIER_LEN] UTF-8 bytes, only ASCII
 * letters, digits, underscores, and periods, and at least one alphanumeric.
 * This mirrors `convex-rs`'s `sync_types/src/path.rs::check_valid_path_component`,
 * but throws instead of returning a result so construction of a
 * [PathComponent] cannot produce an invalid value.
 *
 * @param value the candidate component.
 * @throws ConvexException naming why the component was rejected.
 */
public fun checkValidPathComponent(value: String) {
    val reason = pathComponentError(value) ?: return
    throw ConvexException(reason)
}

/**
 * Returns the first reason [value] is not a valid component, or `null`.
 *
 * Separated from [checkValidPathComponent] so each function has a single throw
 * site, per Detekt's `ThrowsCount`; the checks are pure.
 */
private fun pathComponentError(value: String): String? {
    val byteLength = value.encodeToByteArray().size
    if (byteLength > MAX_IDENTIFIER_LEN) {
        // Rust truncates to the byte limit for the message; the grammar is
        // ASCII-only, so a character slice names the same prefix.
        val prefix = value.take(MAX_IDENTIFIER_LEN)
        return "Path component is too long ($byteLength > maximum $MAX_IDENTIFIER_LEN): $prefix..."
    }
    if (!value.all(Char::isAsciiAlphanumericOrPathPunctuation)) {
        return "Path component $value can only contain alphanumeric characters, underscores, or periods."
    }
    if (value.none(Char::isAsciiAlphanumeric)) {
        return "Path component $value must have at least one alphanumeric character."
    }
    return null
}

/**
 * A validated single segment of a [ModulePath].
 *
 * Construction validates, so a [PathComponent] is proof the text passed
 * [checkValidPathComponent]. Ordering is plain lexicographic string ordering,
 * matching the derived `Ord` upstream.
 *
 * @property value the validated component text.
 */
@JvmInline
public value class PathComponent(public val value: String) : Comparable<PathComponent> {
    init {
        checkValidPathComponent(value)
    }

    /**
     * Compares components lexicographically by their text.
     *
     * @param other the component to compare against.
     * @return negative, zero, or positive when this sorts before, ties, or after [other].
     */
    public override fun compareTo(other: PathComponent): Int = value.compareTo(other.value)

    /**
     * Returns the component text.
     *
     * @return [value].
     */
    public override fun toString(): String = value
}

private fun Char.isAsciiAlphanumeric(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isAsciiAlphanumericOrPathPunctuation(): Boolean =
    isAsciiAlphanumeric() || this == '_' || this == '.'
