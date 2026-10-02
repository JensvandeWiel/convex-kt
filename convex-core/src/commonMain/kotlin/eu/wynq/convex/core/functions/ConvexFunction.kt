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
package eu.wynq.convex.core.functions

/**
 * The kind of a Convex function.
 *
 * @property wireName the value used by Convex tools.
 */
public enum class ConvexFunctionKind(public val wireName: String) {
    /** A read-only query. */
    QUERY("query"),

    /** A transactional write. */
    MUTATION("mutation"),

    /** A non-transactional side-effecting call. */
    ACTION("action"),
    ;

    /** Resolution and construction of function kinds. */
    public companion object {
        /**
         * Resolves a kind from its wire name.
         *
         * @param name the wire name.
         * @return the kind.
         * @throws IllegalArgumentException when unknown.
         */
        public fun fromWire(name: String): ConvexFunctionKind =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown function kind '$name'")
    }
}

/**
 * A field of an object validator.
 *
 * @property validator the field's validator.
 * @property optional whether the field may be omitted.
 */
public data class ConvexValidatorField(
    public val validator: ConvexValidator,
    public val optional: Boolean,
)

/**
 * The argument or return shape of a Convex function, mirroring the validator
 * JSON that Convex emits.
 *
 * Closed so consumers must handle every validator kind.
 */
public sealed interface ConvexValidator {
    /** Any value. */
    public data object Any : ConvexValidator

    /** Only `null`. */
    public data object Null : ConvexValidator

    /** A boolean. */
    public data object Boolean : ConvexValidator

    /** A 64-bit float. */
    public data object Float64 : ConvexValidator

    /** A 64-bit integer. */
    public data object Int64 : ConvexValidator

    /** A byte string. */
    public data object Bytes : ConvexValidator

    /**
     * A string.
     *
     * @property description the optional description.
     */
    public data class String(public val description: kotlin.String? = null) : ConvexValidator

    /**
     * A document id.
     *
     * @property table the table the id belongs to.
     */
    public data class Id(public val table: kotlin.String) : ConvexValidator

    /**
     * An array.
     *
     * @property element the element validator.
     */
    public data class Array(public val element: ConvexValidator) : ConvexValidator

    /**
     * An object.
     *
     * @property fields the field validators, keyed by field name.
     */
    public data class Object(
        public val fields: Map<kotlin.String, ConvexValidatorField>,
    ) : ConvexValidator

    /**
     * A union of validators.
     *
     * @property variants the alternatives.
     */
    public data class Union(public val variants: List<ConvexValidator>) : ConvexValidator

    /**
     * A value constrained to specific literals.
     *
     * @property values the allowed literals, as raw JSON text.
     */
    public data class Literal(public val values: List<kotlin.String>) : ConvexValidator
}

/**
 * A typed descriptor for one Convex function, as produced from an `apiSpec`.
 *
 * @property path the function path, for example `messages:list`.
 * @property kind the function kind.
 * @property args the argument object validator, when declared.
 * @property returns the return validator, when declared.
 */
public data class ConvexFunction(
    public val path: String,
    public val kind: ConvexFunctionKind,
    public val args: ConvexValidator? = null,
    public val returns: ConvexValidator? = null,
) {
    /** The module part of the path, for example `messages`. */
    public val module: String get() = path.substringBefore(':')

    /** The function name within its module, for example `list`. */
    public val name: String get() = path.substringAfter(':', path)
}
