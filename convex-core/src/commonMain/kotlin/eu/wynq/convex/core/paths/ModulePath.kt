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

/**
 * A user-specified path to a loaded module, mirroring `convex-rs`'s
 * `sync_types/src/module_path.rs`.
 *
 * A path is relative, slash-separated, and validated component by component,
 * with an optional `.js` extension. The path itself is not required to carry
 * the extension; [canonicalize] produces the [CanonicalizedModulePath] the
 * backend stores, which always does.
 *
 * The classification flags (`_system`, `_deps`, the HTTP router, the crons
 * module) are computed once at parse time because canonicalization can rename
 * the first component.
 *
 * @property pathText the path exactly as supplied, extension included or not.
 * @property isSystemFlag whether the module lives under `_system/`.
 * @property isDepsFlag whether the module lives under `_deps/` (possibly via
 *   the `actions/_deps/` prefix).
 * @property isHttpFlag whether this is the deployment's HTTP router.
 * @property isCronFlag whether this is the deployment's crons module.
 */
public class ModulePath internal constructor(
    private val pathText: String,
    private val isSystemFlag: Boolean,
    private val isDepsFlag: Boolean,
    private val isHttpFlag: Boolean,
    private val isCronFlag: Boolean,
) : Comparable<ModulePath> {
    /**
     * The path as supplied, without canonicalization.
     *
     * @return [pathText].
     */
    public fun asString(): String = pathText

    /**
     * Whether the module lives within the `_system/` directory.
     *
     * @return [isSystemFlag].
     */
    public fun isSystem(): Boolean = isSystemFlag

    /**
     * Whether the module lives within a `_deps/` directory.
     *
     * @return [isDepsFlag].
     */
    public fun isDeps(): Boolean = isDepsFlag

    /**
     * Whether this is the deployment's single HTTP router.
     *
     * @return [isHttpFlag].
     */
    public fun isHttp(): Boolean = isHttpFlag

    /**
     * Whether this is the deployment's single crons module.
     *
     * @return [isCronFlag].
     */
    public fun isCron(): Boolean = isCronFlag

    /**
     * Produces the stored form of this path, with a `.js` extension.
     *
     * @return this path canonicalized.
     */
    public fun canonicalize(): CanonicalizedModulePath = CanonicalizedModulePath(
        canonicalText(normalPathComponents(pathText)),
        isSystemFlag,
        isDepsFlag,
        isHttpFlag,
        isCronFlag,
    )

    /**
     * Compares paths by text, then by classification flags.
     *
     * @param other the path to compare against.
     * @return negative, zero, or positive when this sorts before, ties, or after [other].
     */
    public override fun compareTo(other: ModulePath): Int = compareValuesBy(
        this,
        other,
        { it.pathText },
        { it.isSystemFlag },
        { it.isDepsFlag },
        { it.isHttpFlag },
        { it.isCronFlag },
    )

    /**
     * Whether two paths have identical text and classification.
     *
     * @param other the object to compare against.
     * @return `true` when [other] is an equal [ModulePath].
     */
    public override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ModulePath &&
                    pathText == other.pathText &&
                    isSystemFlag == other.isSystemFlag &&
                    isDepsFlag == other.isDepsFlag &&
                    isHttpFlag == other.isHttpFlag &&
                    isCronFlag == other.isCronFlag
                )

    /**
     * A hash consistent with [equals].
     *
     * @return the hash code.
     */
    public override fun hashCode(): Int {
        var result = pathText.hashCode()
        result = HASH_MULTIPLIER * result + isSystemFlag.hashCode()
        result = HASH_MULTIPLIER * result + isDepsFlag.hashCode()
        result = HASH_MULTIPLIER * result + isHttpFlag.hashCode()
        result = HASH_MULTIPLIER * result + isCronFlag.hashCode()
        return result
    }

    /**
     * Returns the path text.
     *
     * @return [pathText].
     */
    public override fun toString(): String = pathText

    /** Parsing. */
    public companion object {
        /**
         * Parses and validates a module path.
         *
         * @param raw the candidate path.
         * @return the validated path.
         * @throws ConvexException when [raw] is absolute, empty, has a non-`js`
         *   extension, or contains an invalid component.
         */
        public fun parse(raw: String): ModulePath {
            val components = normalPathComponents(raw)
            if (components.isEmpty()) {
                throw ConvexException("Module paths must be nonempty.")
            }
            val extension = extensionOf(components.last())
            if (extension != null && extension != JS_EXTENSION) {
                throw ConvexException("Module path ($raw) has an extension that isn't 'js'.")
            }
            val canonical = canonicalText(components)
            for (component in normalPathComponents(canonical)) {
                checkValidPathComponent(component)
            }
            val isSystem = components.first() == SYSTEM_UDF_DIR
            val isDeps = components.first() == DEPS_DIR ||
                (components.first() == ACTIONS_DIR && components.getOrNull(1) == DEPS_DIR)
            return ModulePath(
                pathText = raw,
                isSystemFlag = isSystem,
                isDepsFlag = isDeps,
                isHttpFlag = canonical == HTTP_PATH,
                isCronFlag = canonical == CRON_PATH,
            )
        }
    }
}

/**
 * A [ModulePath] guaranteed to carry its `.js` extension, produced by
 * [ModulePath.canonicalize].
 *
 * @property pathText the canonical path, extension included.
 * @property isSystemFlag whether the module lives under `_system/`.
 * @property isDepsFlag whether the module lives under `_deps/`.
 * @property isHttpFlag whether this is the deployment's HTTP router.
 * @property isCronFlag whether this is the deployment's crons module.
 */
public class CanonicalizedModulePath internal constructor(
    private val pathText: String,
    private val isSystemFlag: Boolean,
    private val isDepsFlag: Boolean,
    private val isHttpFlag: Boolean,
    private val isCronFlag: Boolean,
) : Comparable<CanonicalizedModulePath> {
    /**
     * The canonical path text.
     *
     * @return [pathText].
     */
    public fun asString(): String = pathText

    /**
     * Whether the module lives within the `_system/` directory.
     *
     * @return [isSystemFlag].
     */
    public fun isSystem(): Boolean = isSystemFlag

    /**
     * Whether the module lives within a `_deps/` directory.
     *
     * @return [isDepsFlag].
     */
    public fun isDeps(): Boolean = isDepsFlag

    /**
     * Whether this is the deployment's single HTTP router.
     *
     * @return [isHttpFlag].
     */
    public fun isHttp(): Boolean = isHttpFlag

    /**
     * Whether this is the deployment's single crons module.
     *
     * @return [isCronFlag].
     */
    public fun isCron(): Boolean = isCronFlag

    /**
     * Drops the `.js` extension, producing the user-specified form.
     *
     * @return this path without its extension.
     */
    public fun strip(): ModulePath = ModulePath(
        pathText = stripJsExtension(pathText),
        isSystemFlag = isSystemFlag,
        isDepsFlag = isDepsFlag,
        isHttpFlag = isHttpFlag,
        isCronFlag = isCronFlag,
    )

    /**
     * Compares paths by text, then by classification flags.
     *
     * @param other the path to compare against.
     * @return negative, zero, or positive when this sorts before, ties, or after [other].
     */
    public override fun compareTo(other: CanonicalizedModulePath): Int = compareValuesBy(
        this,
        other,
        { it.pathText },
        { it.isSystemFlag },
        { it.isDepsFlag },
        { it.isHttpFlag },
        { it.isCronFlag },
    )

    /**
     * Whether two paths have identical text and classification.
     *
     * @param other the object to compare against.
     * @return `true` when [other] is an equal [CanonicalizedModulePath].
     */
    public override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is CanonicalizedModulePath &&
                    pathText == other.pathText &&
                    isSystemFlag == other.isSystemFlag &&
                    isDepsFlag == other.isDepsFlag &&
                    isHttpFlag == other.isHttpFlag &&
                    isCronFlag == other.isCronFlag
                )

    /**
     * A hash consistent with [equals].
     *
     * @return the hash code.
     */
    public override fun hashCode(): Int {
        var result = pathText.hashCode()
        result = HASH_MULTIPLIER * result + isSystemFlag.hashCode()
        result = HASH_MULTIPLIER * result + isDepsFlag.hashCode()
        result = HASH_MULTIPLIER * result + isHttpFlag.hashCode()
        result = HASH_MULTIPLIER * result + isCronFlag.hashCode()
        return result
    }

    /**
     * Returns the canonical path text.
     *
     * @return [pathText].
     */
    public override fun toString(): String = pathText
}

private const val SYSTEM_UDF_DIR: String = "_system"
private const val DEPS_DIR: String = "_deps"
private const val ACTIONS_DIR: String = "actions"
private const val HTTP_PATH: String = "http.js"
private const val CRON_PATH: String = "crons.js"
private const val JS_EXTENSION: String = "js"

/**
 * The multiplier for hand-written hash codes; the JDK's conventional value.
 * Not a tunable, so it has no name beyond this.
 */
@Suppress("MagicNumber")
private const val HASH_MULTIPLIER: Int = 31

/**
 * Splits [raw] into its normal path components, rejecting the special ones.
 *
 * Mirrors `std::path::Path::components` for the cases this type accepts:
 * rejects absolute paths and `.`/`..` components, and collapses empty
 * segments (so `a//b` and a trailing slash are tolerated).
 */
private fun normalPathComponents(raw: String): List<String> {
    if (raw.startsWith('/')) {
        throw ConvexException("Module paths must be relative ($raw is absolute).")
    }
    val components = mutableListOf<String>()
    for (segment in raw.split('/')) {
        if (segment.isEmpty()) continue
        components += validatedSegment(segment, raw)
    }
    return components
}

/**
 * Returns [segment] when it is a normal path component, throwing for the
 * special `.`/`..` components. Split out so no single function exceeds the
 * configured `ThrowsCount`.
 */
private fun validatedSegment(segment: String, raw: String): String {
    if (segment == ".") {
        throw ConvexException("Invalid path component '.' in $raw.")
    }
    if (segment == "..") {
        throw ConvexException("Invalid path component '..' in $raw.")
    }
    return segment
}

/** The extension of a filename, or `null` when it has none (dotfiles included). */
private fun extensionOf(fileName: String): String? {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0) return null
    return fileName.substring(dot + 1)
}

/** The canonical text of [components], adding `.js` to the filename when needed. */
private fun canonicalText(components: List<String>): String {
    val last = components.last()
    val canonicalLast = if (extensionOf(last) == null) "$last.$JS_EXTENSION" else last
    return (components.dropLast(1) + canonicalLast).joinToString("/")
}

/** Removes a trailing `.js`, matching `Path::set_extension("")`. */
private fun stripJsExtension(canonical: String): String = when {
    canonical.endsWith(".$JS_EXTENSION") -> canonical.dropLast(JS_EXTENSION.length + 1)
    else -> canonical
}
