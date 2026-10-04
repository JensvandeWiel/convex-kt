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
package eu.wynq.convex.codegen.gradle

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * In-process coverage for the plugin's configuration and task actions.
 *
 * [ConvexCodegenPluginTest] drives a real Gradle build, which is the honest
 * end-to-end check but runs the plugin in a separate process that the coverage
 * agent cannot see. These tests exercise the same code inside the test JVM so
 * the coverage gate reflects what actually runs.
 */
class ConvexCodegenPluginUnitTest {

    @Test
    fun registersTheCaptureAndGenerationTasksWithDefaults() {
        val project = project()
        val extension = project.extensions.getByType(ConvexCodegenExtension::class.java)

        assertNotNull(project.tasks.findByName("convexFunctionSpec"), "expected the convexFunctionSpec task")
        val task = project.tasks.findByName("generateConvexApi")
        assertNotNull(task, "expected the generateConvexApi task")
        assertEquals("convex", task.group)

        assertEquals("Api", extension.objectName.get())
        // No manual spec: the plugin captures one itself.
        assertFalse(extension.spec.isPresent)
        assertEquals(project.projectDir, extension.convexProjectDirectory.get().asFile)
        assertTrue(
            extension.functionSpecCommand.get().takeLast(2) == listOf("convex", "function-spec"),
            "unexpected command: ${extension.functionSpecCommand.get()}",
        )
        assertTrue(
            extension.outputDirectory.get().asFile.path.endsWith("generated/convex"),
            "unexpected output directory: ${extension.outputDirectory.get().asFile.path}",
        )
    }

    @Test
    fun writesTheApiFileFromAManualSpec() {
        val specFile = Files.createTempFile("api-spec", ".json").toFile()
        try {
            specFile.writeText(FUNCTION_SPEC)
            val project = project()
            val extension = project.extensions.getByType(ConvexCodegenExtension::class.java)
            extension.spec.set(specFile)
            extension.packageName.set("com.example.app.convex")

            val task = project.tasks.getByName("generateConvexApi") as GenerateConvexApiTask
            task.generate()

            assertGenerated(task.outputDirectory.get().asFile)
        } finally {
            specFile.delete()
        }
    }

    @Test
    fun capturesTheSpecByRunningTheConfiguredCommand() {
        val projectDir = Files.createTempDirectory("convex-spec").toFile()
        try {
            File(projectDir, "functions.json").writeText(FUNCTION_SPEC)
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.pluginManager.apply(ConvexCodegenPlugin::class.java)
            val extension = project.extensions.getByType(ConvexCodegenExtension::class.java)
            extension.convexProjectDirectory.set(projectDir)
            // A portable stand-in for `npx convex function-spec`.
            extension.functionSpecCommand.set(listOf("cat", "functions.json"))

            val task = project.tasks.getByName("convexFunctionSpec") as ConvexFunctionSpecTask
            task.fetch()

            val captured = task.outputFile.get().asFile
            assertTrue(captured.isFile, "expected ${captured.path}")
            assertContains(captured.readText(), "messages.js:send")
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun failsWithAdviceWhenNoFunctionsAreDeclared() {
        val specFile = Files.createTempFile("api-spec", ".json").toFile()
        try {
            specFile.writeText("""{"functions":[]}""")
            val project = project()
            val extension = project.extensions.getByType(ConvexCodegenExtension::class.java)
            extension.spec.set(specFile)
            extension.packageName.set("com.example.app.convex")
            val task = project.tasks.getByName("generateConvexApi") as GenerateConvexApiTask

            val failure = assertFailsWith<IllegalArgumentException> { task.generate() }
            assertContains(failure.message.orEmpty(), "no Convex functions found")
            assertContains(failure.message.orEmpty(), "npx convex function-spec")
        } finally {
            specFile.delete()
        }
    }

    private fun assertGenerated(outputDirectory: File) {
        val generated = File(outputDirectory, "Api.kt")
        assertTrue(generated.isFile, "expected ${generated.path}")
        val source = generated.readText()
        assertContains(source, "package com.example.app.convex")
        assertContains(source, "public object Api {")
        assertContains(source, "public val send: ConvexMutation<SendRequest, Double>")
    }

    private fun project(): Project =
        ProjectBuilder.builder().build().also { it.pluginManager.apply(ConvexCodegenPlugin::class.java) }

    private companion object {
        val FUNCTION_SPEC = """
            {
              "functions": [
                {
                  "identifier": "messages.js:send",
                  "functionType": "Mutation",
                  "args": {
                    "type": "object",
                    "value": { "body": { "fieldType": { "type": "string" }, "optional": false } }
                  },
                  "returns": { "type": "number" }
                }
              ]
            }
        """.trimIndent()
    }
}
