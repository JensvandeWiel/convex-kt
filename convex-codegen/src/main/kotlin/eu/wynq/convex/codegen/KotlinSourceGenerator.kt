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
package eu.wynq.convex.codegen

import eu.wynq.convex.core.functions.ConvexFunction
import eu.wynq.convex.core.functions.ConvexFunctionKind
import eu.wynq.convex.core.functions.ConvexValidator
import eu.wynq.convex.core.functions.ConvexValidatorField

/**
 * Kotlin keywords that cannot be used as an unquoted identifier, so a generated
 * property with such a name is backtick-quoted.
 */
private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while",
)

/**
 * Turns a wire name (`messages.js:send`, `put_address`, ...) into a Kotlin
 * PascalCase identifier, with [fallback] for an empty name and a `T` prefix for
 * a leading digit (a Kotlin identifier cannot start with one).
 */
private fun pascalCase(raw: String, fallback: String): String {
    val parts = raw.split(':', '.', '_', '-', '/').filter { it.isNotBlank() }
    val joined = parts.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    val safe = joined.ifEmpty { fallback }
    return if (safe.first().isDigit()) "T$safe" else safe
}

/**
 * Maps a [ConvexValidator] onto a Kotlin type, emitting the data classes that
 * type needs.
 *
 * The mapping follows the Convex wire vocabulary:
 *
 * | Validator | Kotlin |
 * | --- | --- |
 * | `string`, `id` | `String` |
 * | `boolean` | `Boolean` |
 * | `float64` | `Double` |
 * | `int64` | `Long` |
 * | `bytes` | `ByteArray` |
 * | `null` | `ConvexValue` (a `null`-only value carries no data) |
 * | `array<T>` | `List<T>` |
 * | `object{...}` | a generated `@Serializable data class` |
 * | `union[null, T]` | `T?` |
 * | anything else | `ConvexValue` |
 *
 * A validator that cannot be modelled degrades to [ConvexValue] rather than
 * failing generation, so a newer backend never breaks the build.
 *
 * One mapper is created **per module**, because each module is emitted as its
 * own nested object: two modules may both define a `Body` class without
 * colliding, and a mapper scoped to the module keeps that independence.
 */
internal class TypeMapper {
    /** Data classes that must be emitted, keyed by their generated name. */
    private val emitted = LinkedHashMap<String, String>()

    /** Validators that degraded to [ConvexValue], with the reason. */
    private val fallbacks = LinkedHashSet<String>()

    /** The generated data classes, in deterministic order. */
    val declarations: List<String> get() = emitted.values.toList()

    /** A human-readable list of fallbacks, one per line. */
    val fallbackNotes: List<String> get() = fallbacks.toList()

    /**
     * Returns the Kotlin type for [validator].
     *
     * @param validator the validator.
     * @param nameHint the base name for generated classes; callers must make it
     *   unique per field position so two shapes cannot silently share a class.
     * @param optional whether the field is optional, making the type nullable.
     * @return the Kotlin type expression.
     */
    fun typeOf(validator: ConvexValidator, nameHint: String, optional: Boolean): String {
        val base = when (validator) {
            ConvexValidator.Any -> fallback("any", nameHint)
            ConvexValidator.Null -> "ConvexValue"
            ConvexValidator.Boolean -> "Boolean"
            ConvexValidator.Float64 -> "Double"
            ConvexValidator.Int64 -> "Long"
            ConvexValidator.Bytes -> "ByteArray"
            is ConvexValidator.String -> "String"
            is ConvexValidator.Id -> "String"
            is ConvexValidator.Array -> "List<${typeOf(validator.element, nameHint, optional = false)}>"
            is ConvexValidator.Object -> objectType(validator, nameHint)
            is ConvexValidator.Union -> unionType(validator, nameHint, optional)
            is ConvexValidator.Literal -> fallback("literal", nameHint)
        }
        return if (optional && !base.endsWith("?")) "$base?" else base
    }

    private fun unionType(union: ConvexValidator.Union, nameHint: String, optional: Boolean): String {
        // `union[null, T]` is an optional T, the common Convex spelling.
        val nonNull = union.variants.filterNot { it == ConvexValidator.Null }
        val nullable = optional || union.variants.any { it == ConvexValidator.Null }
        if (nonNull.size == 1) {
            return typeOf(nonNull.single(), nameHint, optional = nullable)
        }
        return fallback("union", nameHint)
    }

    private fun objectType(obj: ConvexValidator.Object, nameHint: String): String {
        // An empty object carries no fields to type, so it degrades to the
        // untyped fallback. A nested class like `ConvexValue.Object` has no
        // generated serializer of its own, whereas `ConvexValue` does.
        if (obj.fields.isEmpty()) return "ConvexValue"
        val name = className(nameHint)
        val existing = emitted[name]
        // An existing entry means this exact class was already emitted; reuse it.
        // A reserved-but-empty entry means a cycle, so return the name to break
        // the recursion and let the outer call fill it.
        if (existing != null) return name
        emitted[name] = ""
        val fields = obj.fields.entries.joinToString("\n") { (fieldName, field) ->
            val type = typeOf(field.validator, "$name${className(fieldName)}", field.optional)
            val default = if (field.optional) " = null" else ""
            "        public val ${propertyName(fieldName)}: $type$default,"
        }
        emitted[name] = buildString {
            append("        /** Generated from an object validator. */\n")
            append("        @Serializable\n")
            append("        public data class $name(\n")
            append(fields)
            append("\n        )")
        }
        return name
    }

    private fun fallback(reason: String, nameHint: String): String {
        fallbacks += "$nameHint: `$reason` maps to `ConvexValue`"
        return "ConvexValue"
    }

    private fun className(hint: String): String = pascalCase(hint, fallback = "Generated")

    private fun propertyName(name: String): String {
        val candidate = name.replaceFirstChar(Char::lowercaseChar)
        return if (candidate in KOTLIN_KEYWORDS) "`$candidate`" else candidate
    }
}

/**
 * Emits Kotlin source that reconstructs typed descriptors and their argument and
 * result types.
 *
 * The output is plain Kotlin with no reflection. Each Convex module becomes a
 * nested object under a single top-level `object`, so names never collide and a
 * call site reads `client.mutate(Api.Messages.send, SendMessageRequest(...))`.
 *
 * Argument types are suffixed by kind: a mutation or action input is a
 * `Request` (a request/response exchange), a query input is an `Input` (a
 * subscription is a stream, not a request). A no-argument function emits no
 * argument type at all.
 */
public object KotlinSourceGenerator {
    /**
     * Generates a source file.
     *
     * @param packageName the package for the generated file.
     * @param objectName the top-level object that holds the modules.
     * @param functions the descriptors to emit.
     * @return the Kotlin source text.
     */
    public fun generate(
        packageName: String,
        objectName: String,
        functions: List<ConvexFunction>,
    ): String {
        val modules = functions.groupBy { it.module }.toSortedMap()
        val moduleBodies = modules.map { (module, moduleFunctions) ->
            moduleObject(module, moduleFunctions)
        }

        val imports = buildList {
            add("import eu.wynq.convex.core.functions.ConvexAction")
            add("import eu.wynq.convex.core.functions.ConvexFunction")
            add("import eu.wynq.convex.core.functions.ConvexFunctionKind")
            add("import eu.wynq.convex.core.functions.ConvexMutation")
            add("import eu.wynq.convex.core.functions.ConvexQuery")
            add("import eu.wynq.convex.core.functions.ConvexValidator")
            add("import eu.wynq.convex.core.functions.ConvexValidatorField")
            add("import eu.wynq.convex.core.value.ConvexValue")
            add("import kotlinx.serialization.Serializable")
        }

        return buildString {
            append("// Generated by convex-kt codegen. Do not edit.\n")
            append("package $packageName\n\n")
            append(imports.joinToString("\n") { it })
            append("\n\n")
            append(descriptorDocumentation())
            append("public object $objectName {\n")
            append(moduleBodies.joinToString("\n\n"))
            append("\n}\n")
        }
    }

    private fun moduleObject(module: String, functions: List<ConvexFunction>): String {
        // One mapper per module: generated class names are scoped to the
        // module's nested object, so modules cannot collide.
        val mapper = TypeMapper()
        val sorted = functions.sortedWith(compareBy({ it.name }, { it.path }))
        val properties = sorted.mapNotNull { descriptorProperty(it, mapper) }
        val bodies = buildList {
            val declarations = mapper.declarations
            if (declarations.isNotEmpty()) add(declarations.joinToString("\n\n"))
            if (properties.isNotEmpty()) add(properties.joinToString("\n\n"))
        }
        return buildString {
            append("    /** Functions in the `$module` module. */\n")
            append("    public object ${moduleClassName(module)} {\n")
            append(bodies.joinToString("\n\n"))
            append("\n    }")
        }
    }

    /** Emits one descriptor property and its generated argument and result types. */
    private fun descriptorProperty(function: ConvexFunction, mapper: TypeMapper): String {
        val suffix = when (function.kind) {
            ConvexFunctionKind.QUERY -> "Input"
            ConvexFunctionKind.MUTATION, ConvexFunctionKind.ACTION -> "Request"
        }
        // Namespace the hint with the function path so two functions with the
        // same argument field names cannot silently share a generated class.
        val hintBase = className(function.name)
        val hasArguments = function.args is ConvexValidator.Object &&
            (function.args as ConvexValidator.Object).fields.isNotEmpty()
        val argType = if (hasArguments) {
            mapper.typeOf(function.args as ConvexValidator, hintBase + suffix, optional = false)
        } else {
            "Unit"
        }
        val resultType = function.returns?.let { mapper.typeOf(it, hintBase + "Result", optional = false) }
            ?: "ConvexValue"

        val descriptor = when (function.kind) {
            ConvexFunctionKind.QUERY -> "ConvexQuery"
            ConvexFunctionKind.MUTATION -> "ConvexMutation"
            ConvexFunctionKind.ACTION -> "ConvexAction"
        }
        val argsSerializer = if (hasArguments) "$argType.serializer()" else "null"
        // The reified `serializer<T>()` resolves collections, nullables, and
        // builtins that have no generated `.serializer()` (a `List` being the
        // common case), so result serializers never emit uncompilable code.
        val resultSerializer = if (function.returns != null) {
            "kotlinx.serialization.serializer<$resultType>()"
        } else {
            "null"
        }

        return buildString {
            append("        /** `${function.path}` (${function.kind.wireName}). */\n")
            append("        public val ${propertyName(function.name)}: $descriptor<$argType, $resultType> =\n")
            append("            $descriptor(\n")
            append("                function = ConvexFunction(\n")
            append("                    path = \"${function.path}\",\n")
            append("                    kind = ConvexFunctionKind.${function.kind.name},\n")
            append("                    args = ${ValidatorExpressions.render(function.args)},\n")
            append("                    returns = ${ValidatorExpressions.render(function.returns)},\n")
            append("                ),\n")
            append("                argsSerializer = $argsSerializer,\n")
            append("                resultSerializer = $resultSerializer,\n")
            append("            )")
        }
    }

    private fun descriptorDocumentation(): String =
        "// Types are generated from the backend apiSpec. Argument types are named\n" +
            "// `<Function>Input` for queries and `<Function>Request` for mutations and\n" +
            "// actions; a no-argument function has no argument type. A validator that\n" +
            "// could not be modelled falls back to `ConvexValue`.\n"

    private fun moduleClassName(module: String): String = pascalCase(module, fallback = "Default")

    private fun propertyName(name: String): String {
        val parts = name.split(':', '.', '_', '-', '/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return "function"
        val head = parts.first().replaceFirstChar(Char::lowercaseChar)
        val rest = parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val candidate = head + rest
        return if (candidate in KOTLIN_KEYWORDS) "`$candidate`" else candidate
    }

    private fun className(name: String): String = pascalCase(name, fallback = "Generated")
}

/** Renders a validator back into a Kotlin `ConvexValidator` expression. */
internal object ValidatorExpressions {
    /**
     * Renders [validator] as Kotlin source, or `null`.
     *
     * @param validator the validator, or `null`.
     * @return the Kotlin expression.
     */
    fun render(validator: ConvexValidator?): String = when (validator) {
        null -> "null"
        ConvexValidator.Any -> "ConvexValidator.Any"
        ConvexValidator.Null -> "ConvexValidator.Null"
        ConvexValidator.Boolean -> "ConvexValidator.Boolean"
        ConvexValidator.Float64 -> "ConvexValidator.Float64"
        ConvexValidator.Int64 -> "ConvexValidator.Int64"
        ConvexValidator.Bytes -> "ConvexValidator.Bytes"
        is ConvexValidator.String ->
            validator.description
                ?.let { "ConvexValidator.String(\"${escape(it)}\")" }
                ?: "ConvexValidator.String()"
        is ConvexValidator.Id -> "ConvexValidator.Id(\"${escape(validator.table)}\")"
        is ConvexValidator.Array -> "ConvexValidator.Array(${render(validator.element)})"
        is ConvexValidator.Object -> renderObject(validator)
        is ConvexValidator.Union ->
            "ConvexValidator.Union(listOf(" +
                validator.variants.joinToString(", ") { render(it) } + "))"
        is ConvexValidator.Literal ->
            "ConvexValidator.Literal(listOf(" +
                validator.values.joinToString(", ") { "\"${escape(it)}\"" } + "))"
    }

    private fun renderObject(validator: ConvexValidator.Object): String {
        if (validator.fields.isEmpty()) return "ConvexValidator.Object(emptyMap())"
        val entries = validator.fields.entries.joinToString(",\n            ") { (name, field) ->
            "\"${escape(name)}\" to ${renderField(field)}"
        }
        return "ConvexValidator.Object(\n            mapOf(\n            $entries,\n            ),\n        )"
    }

    private fun renderField(field: ConvexValidatorField): String =
        "ConvexValidatorField(${render(field.validator)}, optional = ${field.optional})"

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\${'$'}")
}
