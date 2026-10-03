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

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Covers the `convex-codegen` command line entry point. */
class CodegenCliTest {

    private val spec = """
        {
          "functions": [
            {
              "name": "tasks:add",
              "functionType": "mutation",
              "args": {
                "type": "object",
                "value": { "title": { "fieldType": { "type": "string" }, "optional": false } }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesAllRequiredOptions() {
        val options = CodegenCli.Options.parse(
            arrayOf("--spec", "in.json", "--package", "com.example", "--object", "Api", "--out", "out.kt"),
        )

        assertEquals("in.json", options.spec.path)
        assertEquals("com.example", options.packageName)
        assertEquals("Api", options.objectName)
        assertEquals("out.kt", options.out.path)
    }

    @Test
    fun rejectsAMissingRequiredOption() {
        assertFailsWith<IllegalArgumentException> {
            CodegenCli.Options.parse(
                arrayOf("--spec", "in.json", "--package", "com.example", "--object", "Api"),
            )
        }
    }

    @Test
    fun writesGeneratedSourceToTheOutputPath() {
        val directory = Files.createTempDirectory("convex-codegen-cli").toFile()
        try {
            val specFile = File(directory, "api-spec.json").apply { writeText(spec) }
            val outFile = File(directory, "Generated.kt")

            CodegenCli.run(
                arrayOf(
                    "--spec",
                    specFile.path,
                    "--package",
                    "com.example.generated",
                    "--object",
                    "Api",
                    "--out",
                    outFile.path,
                ),
            )

            assertTrue(outFile.exists(), "the generator must write the output file")
            val source = outFile.readText()
            assertTrue(source.contains("package com.example.generated"), source)
            assertTrue(source.contains("public object Api {"), source)
            assertTrue(source.contains("public val add: ConvexMutation<AddRequest,"), source)
        } finally {
            directory.deleteRecursively()
        }
    }
}
