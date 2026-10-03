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

import eu.wynq.convex.core.value.checkValidIdentifier
import kotlin.jvm.JvmInline

/**
 * The exported name of a function within a module.
 *
 * Construction validates through [checkValidIdentifier], mirroring
 * `convex-rs`'s `sync_types/src/function_name.rs`. The name `default` is
 * special: it is the module's default export, which [CanonicalizedUdfPath]
 * treats as equivalent to naming no function at all.
 *
 * @property value the validated function name.
 */
@JvmInline
public value class FunctionName(public val value: String) : Comparable<FunctionName> {
    init {
        checkValidIdentifier(value)
    }

    /**
     * Whether this is the default export name, `"default"`.
     *
     * @return `true` when [value] is `"default"`.
     */
    public fun isDefaultExport(): Boolean = value == DEFAULT_EXPORT

    /**
     * Compares names lexicographically by their text.
     *
     * @param other the name to compare against.
     * @return negative, zero, or positive when this sorts before, ties, or after [other].
     */
    public override fun compareTo(other: FunctionName): Int = value.compareTo(other.value)

    /**
     * Returns the function name text.
     *
     * @return [value].
     */
    public override fun toString(): String = value

    /** Construction helpers. */
    public companion object {
        /**
         * The module default export name, `"default"`.
         *
         * @return a [FunctionName] for `"default"`.
         */
        public fun defaultExport(): FunctionName = FunctionName(DEFAULT_EXPORT)

        private const val DEFAULT_EXPORT: String = "default"
    }
}
