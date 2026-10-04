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

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the plugin through a real Gradle build, the way a consumer applies
 * it.
 *
 * The JVM and multiplatform cases apply the matching Kotlin plugin so the
 * source-set wiring is covered, not just the generation task.
 */
class ConvexCodegenPluginTest {

    @Test
    fun generatesDescriptorsFromAManualSpec() {
        withProject(
            settings = SETTINGS,
            build = """
                plugins {
                    id("eu.wynq.convex.codegen")
                }
                convexCodegen {
                    packageName = "com.example.app.convex"
                    spec = layout.projectDirectory.file("api-spec.json")
                }
            """.trimIndent(),
        ) { projectDir ->
            val result = run(projectDir, "generateConvexApi")
            assertEquals(TaskOutcome.SUCCESS, result.task(":generateConvexApi")?.outcome)

            assertGenerated(projectDir)
        }
    }

    @Test
    fun capturesTheSpecThenGenerates() {
        withProject(
            settings = SETTINGS,
            build = """
                plugins {
                    id("eu.wynq.convex.codegen")
                }
                convexCodegen {
                    packageName = "com.example.app.convex"
                    convexProjectDirectory = layout.projectDirectory
                    // A portable stand-in for `npx convex function-spec`.
                    functionSpecCommand = listOf("cat", "functions.json")
                }
            """.trimIndent(),
        ) { projectDir ->
            val result = run(projectDir, "generateConvexApi")
            assertEquals(TaskOutcome.SUCCESS, result.task(":convexFunctionSpec")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":generateConvexApi")?.outcome)

            assertGenerated(projectDir)
        }
    }

    @Test
    fun addsGeneratedSourcesToTheKotlinJvmSourceSet() {
        withProject(
            settings = SETTINGS,
            build = kotlinBuild("org.jetbrains.kotlin.jvm", "main", "jvm"),
        ) { projectDir ->
            val result = run(projectDir, "printConvexSourceDirs")
            assertContains(result.output, File("build", "generated/convex").path.replace('\\', '/'))
        }
    }

    @Test
    fun addsGeneratedSourcesToTheMultiplatformSourceSet() {
        withProject(
            settings = SETTINGS,
            build = kotlinBuild("org.jetbrains.kotlin.multiplatform", "commonMain", "multiplatform"),
        ) { projectDir ->
            val result = run(projectDir, "printConvexSourceDirs")
            assertContains(result.output, File("build", "generated/convex").path.replace('\\', '/'))
        }
    }

    @Test
    fun failsWithAdviceWhenTheSpecHasNoFunctions() {
        withProject(
            settings = SETTINGS,
            build = """
                plugins {
                    id("eu.wynq.convex.codegen")
                }
                convexCodegen {
                    packageName = "com.example.app.convex"
                    spec = layout.projectDirectory.file("empty-spec.json")
                }
            """.trimIndent(),
            spec = """{"functions":[]}""",
        ) { projectDir ->
            val result = runAndFail(projectDir, "generateConvexApi")
            assertContains(result.output, "no Convex functions found")
            assertContains(result.output, "npx convex function-spec")
        }
    }

    private fun kotlinBuild(
        kotlinPlugin: String,
        sourceSet: String,
        targetBlock: String,
    ): String = """
        plugins {
            id("$kotlinPlugin") version "2.2.21"
            id("eu.wynq.convex.codegen")
        }
        ${if (targetBlock == "multiplatform") "kotlin {\n    jvm()\n}" else ""}
        convexCodegen {
            packageName = "com.example.app.convex"
            spec = layout.projectDirectory.file("api-spec.json")
        }
        val kotlinExtension = kotlin
        tasks.register("printConvexSourceDirs") {
            doLast {
                println("convex-source-dirs=" + kotlinExtension.sourceSets.getByName("$sourceSet").kotlin.srcDirs)
            }
        }
    """.trimIndent()

    private fun assertGenerated(projectDir: File) {
        val generated = File(projectDir, "build/generated/convex/Api.kt")
        assertTrue(generated.isFile, "expected ${generated.path} to exist")
        val source = generated.readText()
        assertContains(source, "public object Api {")
        assertContains(source, "public object Messages {")
        assertContains(source, "ConvexQuery<Unit, List<ListResult>>")
        assertContains(source, "public val send: ConvexMutation<SendRequest, Double>")
    }

    private fun run(projectDir: File, vararg arguments: String): org.gradle.testkit.runner.BuildResult =
        runner(projectDir, arguments).build()

    private fun runAndFail(projectDir: File, vararg arguments: String): org.gradle.testkit.runner.BuildResult =
        runner(projectDir, arguments).buildAndFail()

    private fun runner(projectDir: File, arguments: Array<out String>): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    private fun withProject(
        settings: String,
        build: String,
        spec: String = FUNCTION_SPEC,
        block: (File) -> Unit,
    ) {
        val projectDir = Files.createTempDirectory("convex-codegen-plugin").toFile()
        try {
            File(projectDir, "settings.gradle.kts").writeText(settings)
            File(projectDir, "build.gradle.kts").writeText(build)
            // Both a manual spec and a `functionSpecCommand` stand-in read from
            // these; each test uses one of them.
            File(projectDir, "api-spec.json").writeText(spec)
            File(projectDir, "empty-spec.json").writeText(spec)
            File(projectDir, "functions.json").writeText(spec)
            block(projectDir)
        } finally {
            projectDir.deleteRecursively()
        }
    }

    private companion object {
        val SETTINGS = """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                    google()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                    google()
                }
            }
            rootProject.name = "consumer"
        """.trimIndent()

        // The shape `npx convex function-spec` emits: an object with a
        // `functions` array of records, using `identifier` and `functionType`.
        val FUNCTION_SPEC = """
            {
              "functions": [
                {
                  "identifier": "messages.js:list",
                  "functionType": "Query",
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
