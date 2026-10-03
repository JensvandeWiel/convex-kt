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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses a Convex `apiSpec` document into [ConvexFunction] descriptors.
 *
 * The backend's `apiSpec` system function returns an array of function records;
 * the Convex CLI wraps the same array in a `functions` field. Both shapes are
 * accepted, and the parser tolerates the field-name variations that appear
 * across Convex versions: a function's path may be given as `name`, as an
 * `identifier` (for example `messages.js:send`), or as `module` +
 * `functionName`; its kind as `functionType` or `type`. Unknown validator types
 * degrade to [ConvexValidator.Any] rather than failing, because a newer backend
 * must not break code generation.
 */
public object ApiSpecParser {
    /**
     * Parses an `apiSpec` document.
     *
     * @param specJson the JSON text: either the array of functions or an object
     *   with a `functions` array.
     * @return the functions it declares, in document order.
     */
    public fun parse(specJson: String): List<ConvexFunction> {
        val functions = when (val root = Json.parseToJsonElement(specJson)) {
            is JsonArray -> root
            is JsonObject -> root["functions"] as? JsonArray ?: return emptyList()
            is JsonPrimitive -> return emptyList()
        }
        return functions.mapNotNull { element ->
            (element as? JsonObject)?.let(::parseFunction)
        }
    }

    private fun parseFunction(obj: JsonObject): ConvexFunction? {
        val path = obj.string("name")
            ?: obj.identifierPath()
            ?: obj.moduleAndName()
            ?: return null
        val kindName = obj.string("functionType") ?: obj.string("type") ?: ConvexFunctionKind.QUERY.wireName
        return ConvexFunction(
            path = path,
            kind = ConvexFunctionKind.fromWire(kindName),
            // `returns` is often null, meaning unspecified; treat it as absent.
            args = (obj["args"] as? JsonObject)?.let(::parseValidator),
            returns = (obj["returns"] as? JsonObject)?.let(::parseValidator),
        )
    }

    /** Turns a module identifier like `messages.js:send` into `messages:send`. */
    private fun JsonObject.identifierPath(): String? =
        string("identifier")?.replace(".js:", ":")

    private fun JsonObject.moduleAndName(): String? {
        val module = string("module") ?: return null
        val function = string("functionName") ?: return null
        return "$module:$function"
    }

    /**
     * Parses one validator JSON node.
     *
     * @param element the validator JSON.
     * @return the parsed validator.
     */
    public fun parseValidator(element: JsonElement): ConvexValidator {
        val obj = element as? JsonObject ?: return ConvexValidator.Any
        return when (val type = obj.string("type") ?: TYPE_ANY) {
            "array" -> ConvexValidator.Array(obj["value"]?.let(::parseValidator) ?: ConvexValidator.Any)
            "object" -> ConvexValidator.Object(
                (obj["value"] as? JsonObject)?.mapValues { parseField(it.value) }.orEmpty(),
            )
            "union" -> ConvexValidator.Union(
                (obj["value"] as? JsonArray)?.map(::parseValidator).orEmpty(),
            )
            "literal" -> ConvexValidator.Literal(
                obj["value"]?.let { listOf(it.toString()) }.orEmpty(),
            )
            else -> scalarValidator(type, obj)
        }
    }

    /**
     * Parses the scalar (non-composite) validator types.
     *
     * Split out of [parseValidator] so the composite branches and the scalar
     * vocabulary each stay well under the complexity limit.
     *
     * @param type the validator's `type` string.
     * @param obj the validator JSON, for the types that carry extra fields.
     * @return the parsed scalar, or [ConvexValidator.Any] for an unknown type.
     */
    private fun scalarValidator(type: String, obj: JsonObject): ConvexValidator = when (type) {
        TYPE_ANY -> ConvexValidator.Any
        "null" -> ConvexValidator.Null
        "boolean" -> ConvexValidator.Boolean
        // Convex's `v.number()` (a float64) serializes as `"number"`.
        "float64", "number" -> ConvexValidator.Float64
        "int64" -> ConvexValidator.Int64
        "bytes" -> ConvexValidator.Bytes
        "string" -> ConvexValidator.String(obj.string("description"))
        "id" -> ConvexValidator.Id(obj.string("tableName") ?: obj.string("table").orEmpty())
        else -> ConvexValidator.Any
    }

    private fun parseField(element: JsonElement): ConvexValidatorField {
        val obj = element as? JsonObject ?: return ConvexValidatorField(ConvexValidator.Any, optional = false)
        val validator = obj["fieldType"]?.let(::parseValidator) ?: parseValidator(element)
        val optional = (obj["optional"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        return ConvexValidatorField(validator, optional)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val TYPE_ANY = "any"
}
