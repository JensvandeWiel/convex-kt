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

import eu.wynq.convex.core.functions.ConvexFunctionKind
import eu.wynq.convex.core.functions.ConvexValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers apiSpec parsing and the shape of the generated Kotlin.
 *
 * The fixtures mirror the *real* backend shape captured from
 * `_system/cli/modules:apiSpec`: a bare array of records with an `identifier`
 * and a capitalized `functionType`.
 */
class CodegenTest {

    private val spec = """
        {
          "functions": [
            {
              "name": "messages:list",
              "functionType": "query",
              "args": { "type": "object", "value": {} },
              "returns": {
                "type": "array",
                "value": {
                  "type": "object",
                  "value": { "body": { "fieldType": { "type": "string" }, "optional": false } }
                }
              }
            },
            {
              "name": "messages:send",
              "functionType": "mutation",
              "args": {
                "type": "object",
                "value": { "body": { "fieldType": { "type": "string" }, "optional": false } }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesFunctionsAndValidators() {
        val functions = ApiSpecParser.parse(spec)
        assertEquals(2, functions.size)

        val list = functions[0]
        assertEquals("messages:list", list.path)
        assertEquals("messages", list.module)
        assertEquals("list", list.name)
        assertEquals(ConvexFunctionKind.QUERY, list.kind)
        assertIs<ConvexValidator.Object>(list.args)
        val returns = assertIs<ConvexValidator.Array>(list.returns)
        val element = assertIs<ConvexValidator.Object>(returns.element)
        assertIs<ConvexValidator.String>(element.fields.getValue("body").validator)
        assertEquals(false, element.fields.getValue("body").optional)
        assertEquals(ConvexFunctionKind.MUTATION, functions[1].kind)
    }

    @Test
    fun parsesTheBackendApiSpecShape() {
        // Captured from the backend's `_system/cli/modules:apiSpec`: a bare
        // array of records with an `identifier` and a capitalized
        // `functionType`.
        val spec = """
        [
          {"args":{"type":"object","value":{}},"functionType":"Query","identifier":"ping.js:ping","returns":null},
          {"args":{"type":"object","value":{"body":{"fieldType":{"type":"string"},"optional":false}}},"functionType":"Mutation","identifier":"messages.js:send","returns":null}
        ]
        """.trimIndent()

        val functions = ApiSpecParser.parse(spec)
        assertEquals(2, functions.size)
        assertEquals("ping:ping", functions[0].path)
        assertEquals(ConvexFunctionKind.QUERY, functions[0].kind)
        assertEquals(null, functions[0].returns)
        assertEquals("messages:send", functions[1].path)
        assertEquals(ConvexFunctionKind.MUTATION, functions[1].kind)
        val args = assertIs<ConvexValidator.Object>(functions[1].args)
        assertEquals(false, args.fields.getValue("body").optional)
    }

    @Test
    fun toleratesModuleAndFunctionNameFields() {
        val functions = ApiSpecParser.parse(
            """{"functions":[{"module":"tasks","functionName":"run","type":"action"}]}""",
        )
        assertEquals("tasks:run", functions.single().path)
        assertEquals(ConvexFunctionKind.ACTION, functions.single().kind)
    }

    @Test
    fun unknownValidatorTypesDegradeToAny() {
        assertEquals(
            ConvexValidator.Any,
            ApiSpecParser.parseValidator(
                kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"from-the-future"}"""),
            ),
        )
    }

    @Test
    fun groupsFunctionsIntoNestedModuleObjects() {
        val source = generate(spec)
        assertTrue(source.contains("public object Api {"), source)
        assertTrue(source.contains("public object Messages {"), source)
        // The descriptor is a typed singleton, not a raw ConvexFunction.
        assertTrue(source.contains("public val list: ConvexQuery<Unit, List<ListResult>>"), source)
    }

    @Test
    fun emitsNoArgumentTypeForANoArgumentQuery() {
        val source = generate(spec)
        // `list` takes no args, so there must be no `ListInput` type and the
        // descriptor's argument type is Unit with a null serializer.
        assertTrue(source.contains("ConvexQuery<Unit, List<ListResult>>"), source)
        assertTrue(!source.contains("ListInput"), source)
        assertTrue(source.contains("argsSerializer = null"), source)
    }

    @Test
    fun suffixesArgumentTypesByKind() {
        val source = generate(
            """
            [
              {"identifier":"a.js:find","functionType":"Query",
               "args":{"type":"object","value":{"q":{"fieldType":{"type":"string"},"optional":false}}}},
              {"identifier":"a.js:update","functionType":"Mutation",
               "args":{"type":"object","value":{"q":{"fieldType":{"type":"string"},"optional":false}}}},
              {"identifier":"a.js:notify","functionType":"Action",
               "args":{"type":"object","value":{"q":{"fieldType":{"type":"string"},"optional":false}}}}
            ]
            """.trimIndent(),
        )
        // A query input is an Input; a mutation or action input is a Request.
        assertTrue(source.contains("data class FindInput("), source)
        assertTrue(source.contains("data class UpdateRequest("), source)
        assertTrue(source.contains("data class NotifyRequest("), source)
        assertTrue(source.contains("ConvexQuery<FindInput, ConvexValue>"), source)
        assertTrue(source.contains("ConvexMutation<UpdateRequest, ConvexValue>"), source)
        assertTrue(source.contains("ConvexAction<NotifyRequest, ConvexValue>"), source)
    }

    @Test
    fun mapsValidatorsToKotlinTypes() {
        val source = generate(
            """
            [
              {"identifier":"t.js:all","functionType":"Query",
               "args":{"type":"object","value":{
                 "s":{"fieldType":{"type":"string"},"optional":false},
                 "b":{"fieldType":{"type":"boolean"},"optional":false},
                 "i":{"fieldType":{"type":"int64"},"optional":false},
                 "f":{"fieldType":{"type":"float64"},"optional":false},
                 "raw":{"fieldType":{"type":"bytes"},"optional":false},
                 "id":{"fieldType":{"type":"id","tableName":"t"},"optional":false},
                 "opt":{"fieldType":{"type":"string"},"optional":true}
               }}},
              {"identifier":"t.js:list","functionType":"Query",
               "args":{"type":"object","value":{}},
               "returns":{"type":"array","value":{"type":"int64"}}}
            ]
            """.trimIndent(),
        )
        // The exact Kotlin types that matter: Long not Double, ByteArray for
        // bytes, and a nullable optional with a default.
        assertTrue(source.contains("public val i: Long,"), source)
        assertTrue(source.contains("public val f: Double,"), source)
        assertTrue(source.contains("public val raw: ByteArray,"), source)
        assertTrue(source.contains("public val b: Boolean,"), source)
        assertTrue(source.contains("public val s: String,"), source)
        assertTrue(source.contains("public val opt: String? = null,"), source)
        assertTrue(source.contains("ConvexQuery<Unit, List<Long>>"), source)
    }

    @Test
    fun mapsNullableUnionToNullableType() {
        val source = generate(
            """
            [
              {"identifier":"t.js:maybe","functionType":"Query",
               "args":{"type":"object","value":{
                 "x":{"fieldType":{"type":"union","value":[{"type":"string"},{"type":"null"}]},"optional":false}
               }}}
            ]
            """.trimIndent(),
        )
        assertTrue(source.contains("public val x: String?,"), source)
    }

    @Test
    fun fallsBackToConvexValueForUnmodelledValidators() {
        val source = generate(
            """
            [
              {"identifier":"t.js:fuzzy","functionType":"Mutation",
               "args":{"type":"object","value":{
                 "payload":{"fieldType":{"type":"from-the-future"},"optional":false}
               }},
               "returns":{"type":"union","value":[{"type":"string"},{"type":"int64"}]}}
            ]
            """.trimIndent(),
        )
        // An unmodelled argument field and a non-null union result both become
        // ConvexValue, and the descriptor still gets a serializer because
        // ConvexValue has one.
        assertTrue(source.contains("public val payload: ConvexValue,"), source)
        assertTrue(source.contains("ConvexMutation<FuzzyRequest, ConvexValue>"), source)
        assertTrue(source.contains("resultSerializer = kotlinx.serialization.serializer<ConvexValue>()"), source)
    }

    @Test
    fun escapesFieldNamesThatAreKotlinKeywords() {
        val source = generate(
            """
            [
              {"identifier":"t.js:k","functionType":"Mutation",
               "args":{"type":"object","value":{
                 "object":{"fieldType":{"type":"string"},"optional":false}
               }}}
            ]
            """.trimIndent(),
        )
        assertTrue(source.contains("public val `object`: String,"), source)
    }

    @Test
    fun keepsSameNamedArgumentTypesSeparateAcrossModules() {
        // Two modules both declare a mutation with a `value` field. The classes
        // are scoped to their module objects, so neither name nor shape leaks.
        val source = generate(
            """
            [
              {"identifier":"alpha.js:set","functionType":"Mutation",
               "args":{"type":"object","value":{"value":{"fieldType":{"type":"string"},"optional":false}}}},
              {"identifier":"beta.js:set","functionType":"Mutation",
               "args":{"type":"object","value":{"value":{"fieldType":{"type":"int64"},"optional":false}}}}
            ]
            """.trimIndent(),
        )
        assertTrue(source.contains("public object Alpha {"), source)
        assertTrue(source.contains("public object Beta {"), source)
        // Each module gets its own SetRequest, with its own field type. If the
        // mapper leaked across modules, one would be reused and wrong.
        assertTrue(source.contains("public val value: String,"), source)
        assertTrue(source.contains("public val value: Long,"), source)
    }

    @Test
    fun generatesNestedObjectTypesFromFieldPaths() {
        val source = generate(
            """
            [
              {"identifier":"t.js:put","functionType":"Mutation",
               "args":{"type":"object","value":{
                 "address":{"fieldType":{"type":"object","value":{
                   "city":{"fieldType":{"type":"string"},"optional":false}
                 }},"optional":false}
               }}}
            ]
            """.trimIndent(),
        )
        assertTrue(source.contains("data class PutRequestAddress("), source)
        assertTrue(source.contains("public val address: PutRequestAddress,"), source)
        assertTrue(source.contains("public val city: String,"), source)
    }

    @Test
    fun generationIsDeterministic() {
        // A generated file is committed and diffed, so the same spec must always
        // produce byte-identical output, whatever order the parser yields.
        val first = generate(spec)
        val second = generate(spec)
        assertEquals(first, second)
    }

    @Test
    fun propertyNameMatchesTheFunctionName() {
        val source = generate(spec)
        // `messages:send` becomes `send`, so generated code stays idiomatic.
        assertTrue(source.contains("public val send: ConvexMutation<SendRequest,"), source)
        assertTrue(source.contains("public val list: ConvexQuery<Unit,"), source)
    }

    @Test
    fun mapsTheBackendsNumberValidatorToDouble() {
        // The backend serializes `v.number()` (a float64) as `"number"`, not
        // `"float64"`; failing to map it would silently degrade every JS number
        // argument and result to ConvexValue.
        val source = generate(
            """
            [
              {"identifier":"t.js:scale","functionType":"Query",
               "args":{"type":"object","value":{
                 "factor":{"fieldType":{"type":"number"},"optional":false}
               }},
               "returns":{"type":"number"}}
            ]
            """.trimIndent(),
        )
        assertTrue(source.contains("public val factor: Double,"), source)
        assertTrue(source.contains("ConvexQuery<ScaleInput, Double>"), source)
        assertTrue(source.contains("resultSerializer = kotlinx.serialization.serializer<Double>()"), source)
    }

    @Test
    fun emitsAReifiedSerializerForCollectionResults() {
        // `List<T>` has no generated `.serializer()`, so an array result must use
        // the reified `serializer<List<T>>()`. Emitting `List<T>.serializer()`
        // would not compile.
        val source = generate(
            """
            [
              {"identifier":"t.js:all","functionType":"Query",
               "args":{"type":"object","value":{}},
               "returns":{"type":"array","value":{"type":"int64"}}}
            ]
            """.trimIndent(),
        )
        assertTrue(source.contains("ConvexQuery<Unit, List<Long>>"), source)
        assertTrue(source.contains("resultSerializer = kotlinx.serialization.serializer<List<Long>>()"), source)
        assertTrue(!source.contains("List<Long>.serializer()"), source)
    }

    @Test
    fun emitsANullableSerializerForNullableUnionResults() {
        val source = generate(
            """
            [
              {"identifier":"t.js:maybe","functionType":"Query",
               "args":{"type":"object","value":{}},
               "returns":{"type":"union","value":[{"type":"string"},{"type":"null"}]}}
            ]
            """.trimIndent(),
        )
        assertTrue(source.contains("ConvexQuery<Unit, String?>"), source)
        assertTrue(source.contains("resultSerializer = kotlinx.serialization.serializer<String?>()"), source)
    }

    private fun generate(specJson: String): String =
        KotlinSourceGenerator.generate("com.example.generated", "Api", ApiSpecParser.parse(specJson))
}
