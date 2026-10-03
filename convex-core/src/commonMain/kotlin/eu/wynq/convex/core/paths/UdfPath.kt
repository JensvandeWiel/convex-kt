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

/**
 * A user-specified path to a function: a [ModulePath] plus an optional
 * [FunctionName], separated by a colon.
 *
 * When no function name is present the module's default export is implied, so
 * several distinct [UdfPath]s can address one function. [canonicalize] chooses
 * the single representative; [CanonicalizedUdfPath.strip] goes back. Mirrors
 * `convex-rs`'s `sync_types/src/udf_path.rs`.
 *
 * @property modulePath the module portion.
 * @property function the named function, or `null` for the default export.
 */
public class UdfPath internal constructor(
    private val modulePath: ModulePath,
    private val function: FunctionName?,
) : Comparable<UdfPath> {
    /**
     * Whether the addressed function is a system UDF.
     *
     * @return [ModulePath.isSystem] of the module.
     */
    public fun isSystem(): Boolean = modulePath.isSystem()

    /**
     * The module portion of this path.
     *
     * @return [modulePath].
     */
    public fun module(): ModulePath = modulePath

    /**
     * The named function, when the path names one explicitly.
     *
     * @return [function], or `null` for the module default export.
     */
    public fun functionName(): FunctionName? = function

    /**
     * Resolves this path to its canonical, one-per-function form.
     *
     * @return the canonicalized path, defaulting the function to `"default"`.
     */
    public fun canonicalize(): CanonicalizedUdfPath = CanonicalizedUdfPath(
        modulePath.canonicalize(),
        function ?: FunctionName.defaultExport(),
    )

    /**
     * Compares by module, then by function.
     *
     * @param other the path to compare against.
     * @return negative, zero, or positive when this sorts before, ties, or after [other].
     */
    public override fun compareTo(other: UdfPath): Int =
        compareValuesBy(this, other, { it.modulePath }, { it.function })

    /**
     * Whether two paths address the same module and function.
     *
     * @param other the object to compare against.
     * @return `true` when [other] is an equal [UdfPath].
     */
    public override fun equals(other: Any?): Boolean =
        this === other || (other is UdfPath && modulePath == other.modulePath && function == other.function)

    /**
     * A hash consistent with [equals].
     *
     * @return the hash code.
     */
    public override fun hashCode(): Int = HASH_MULTIPLIER * modulePath.hashCode() + function.hashCode()

    /**
     * Renders the path as `module` or `module:function`.
     *
     * @return the display form.
     */
    public override fun toString(): String =
        if (function == null) modulePath.asString() else "${modulePath.asString()}:$function"

    /** Parsing. */
    public companion object {
        /**
         * Parses and validates a UDF path.
         *
         * The last colon separates module from function, so a colon anywhere
         * else is part of the module text and is rejected by [ModulePath.parse].
         *
         * @param raw the candidate path, for example `messages:list`.
         * @return the validated path.
         * @throws eu.wynq.convex.core.ConvexException when the module or
         *   function is invalid.
         */
        public fun parse(raw: String): UdfPath {
            val separator = raw.lastIndexOf(':')
            return if (separator >= 0) {
                UdfPath(
                    ModulePath.parse(raw.substring(0, separator)),
                    FunctionName(raw.substring(separator + 1)),
                )
            } else {
                UdfPath(ModulePath.parse(raw), null)
            }
        }
    }
}

/**
 * A [UdfPath] in one-to-one correspondence with a function, produced by
 * [UdfPath.canonicalize]: the module always carries `.js` and the function is
 * never omitted.
 *
 * @property modulePath the canonical module portion.
 * @property function the resolved function name.
 */
public class CanonicalizedUdfPath internal constructor(
    private val modulePath: CanonicalizedModulePath,
    private val function: FunctionName,
) : Comparable<CanonicalizedUdfPath> {
    /**
     * Whether the addressed function is a system UDF.
     *
     * @return [CanonicalizedModulePath.isSystem] of the module.
     */
    public fun isSystem(): Boolean = modulePath.isSystem()

    /**
     * The canonical module portion of this path.
     *
     * @return [modulePath].
     */
    public fun module(): CanonicalizedModulePath = modulePath

    /**
     * The resolved function name.
     *
     * @return [function].
     */
    public fun functionName(): FunctionName = function

    /**
     * Converts back to the user-specified form, dropping the default export
     * name and the `.js` extension.
     *
     * @return the stripped path.
     */
    public fun strip(): UdfPath = UdfPath(
        modulePath.strip(),
        if (function.isDefaultExport()) null else function,
    )

    /**
     * Compares by module, then by function.
     *
     * @param other the path to compare against.
     * @return negative, zero, or positive when this sorts before, ties, or after [other].
     */
    public override fun compareTo(other: CanonicalizedUdfPath): Int =
        compareValuesBy(this, other, { it.modulePath }, { it.function })

    /**
     * Whether two canonical paths address the same function.
     *
     * @param other the object to compare against.
     * @return `true` when [other] is an equal [CanonicalizedUdfPath].
     */
    public override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is CanonicalizedUdfPath &&
                    modulePath == other.modulePath &&
                    function == other.function
                )

    /**
     * A hash consistent with [equals].
     *
     * @return the hash code.
     */
    public override fun hashCode(): Int = HASH_MULTIPLIER * modulePath.hashCode() + function.hashCode()

    /**
     * Renders the path as `module:function`.
     *
     * @return the display form.
     */
    public override fun toString(): String = "${modulePath.asString()}:$function"
}

/**
 * The multiplier for hand-written hash codes; the JDK's conventional value.
 * Not a tunable, so it has no name beyond this.
 */
@Suppress("MagicNumber")
private const val HASH_MULTIPLIER: Int = 31
