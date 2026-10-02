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
 * Covers apiSpec parsing and Kotlin generation. The sample mirrors the shape
 * Convex emits: an object argument and an array-of-object return.
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
    fun generatesKotlinDescriptors() {
        val source = KotlinSourceGenerator.generate(
            packageName = "com.example.generated",
            objectName = "ConvexApi",
            functions = ApiSpecParser.parse(spec),
        )

        assertTrue(source.contains("package com.example.generated"), source)
        assertTrue(source.contains("public object ConvexApi"), source)
        assertTrue(source.contains("public val messagesList: ConvexFunction"), source)
        assertTrue(source.contains("path = \"messages:list\""), source)
        assertTrue(source.contains("kind = ConvexFunctionKind.QUERY"), source)
        assertTrue(source.contains("kind = ConvexFunctionKind.MUTATION"), source)
        assertTrue(source.contains("ConvexValidator.String()"), source)
        assertTrue(source.contains("ConvexValidatorField"), source)
    }
}
