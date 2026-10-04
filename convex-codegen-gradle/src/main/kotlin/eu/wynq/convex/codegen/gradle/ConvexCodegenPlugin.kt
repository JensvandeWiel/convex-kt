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

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The `eu.wynq.convex.codegen` plugin.
 *
 * It captures the backend `apiSpec` by running the Convex CLI, turns it into
 * typed descriptors, and adds the generated directory to the configured Kotlin
 * source set so a normal build compiles it. Apply it alongside a Kotlin
 * Multiplatform or Kotlin JVM plugin:
 *
 * ```
 * plugins {
 *     id("org.jetbrains.kotlin.multiplatform")
 *     id("eu.wynq.convex.codegen") version "0.1.0"
 * }
 *
 * convexCodegen {
 *     packageName = "com.example.app.convex"
 * }
 * ```
 *
 * Set `convexCodegen { spec = ... }` to read a committed JSON file instead of
 * running the CLI.
 */
public class ConvexCodegenPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("convexCodegen", ConvexCodegenExtension::class.java)
        extension.objectName.convention(DEFAULT_OBJECT_NAME)
        extension.outputDirectory.convention(project.layout.buildDirectory.dir(DEFAULT_OUTPUT))
        extension.convexProjectDirectory.convention(project.rootProject.layout.projectDirectory)
        extension.functionSpecCommand.convention(defaultFunctionSpecCommand())

        val functionSpec = project.tasks.register(FUNCTION_SPEC_TASK, ConvexFunctionSpecTask::class.java) { task ->
            task.group = CONVEX_GROUP
            task.description = "Captures the Convex apiSpec by running the Convex CLI."
            task.prepareCommand.set(extension.prepareCommand)
            task.functionSpecCommand.set(extension.functionSpecCommand)
            task.workingDirectory.set(extension.convexProjectDirectory)
            task.outputFile.set(project.layout.buildDirectory.file(DEFAULT_SPEC_OUTPUT))
            // The spec is a view of a remote deployment, so there is no local
            // input that could make a cached result safe: always re-capture.
            task.outputs.upToDateWhen { false }
        }

        val generate = project.tasks.register(GENERATE_TASK, GenerateConvexApiTask::class.java) { task ->
            task.group = CONVEX_GROUP
            task.description = "Generates typed Convex descriptors from the backend apiSpec."
            // A manual `spec` wins; otherwise the spec task produces the file.
            task.spec.set(extension.spec.orElse(functionSpec.flatMap { it.outputFile }))
            task.packageName.set(extension.packageName)
            task.objectName.set(extension.objectName)
            task.outputDirectory.set(extension.outputDirectory)
        }

        for (kotlinPlugin in KOTLIN_PLUGINS) {
            project.plugins.withId(kotlinPlugin) { _ ->
                extension.sourceSet.convention(if (kotlinPlugin == KMP_PLUGIN) "commonMain" else "main")
                // The source directory is a task output, but wiring it in
                // `afterEvaluate` means the provider no longer carries the task
                // dependency reliably, so make every Kotlin compilation depend on
                // generation explicitly.
                project.tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
                    .configureEach { it.dependsOn(generate) }
                KotlinSourceSetWiring.wire(project, extension, generate)
            }
        }
    }

    private companion object {
        const val DEFAULT_OBJECT_NAME = "Api"
        const val DEFAULT_OUTPUT = "generated/convex"
        const val DEFAULT_SPEC_OUTPUT = "generated/convex-spec/api-spec.json"
        const val GENERATE_TASK = "generateConvexApi"
        const val FUNCTION_SPEC_TASK = "convexFunctionSpec"
        const val CONVEX_GROUP = "convex"
        const val KMP_PLUGIN = "org.jetbrains.kotlin.multiplatform"
        const val JVM_PLUGIN = "org.jetbrains.kotlin.jvm"

        /** Kotlin plugin ids whose projects the generated sources are wired into. */
        val KOTLIN_PLUGINS = listOf(KMP_PLUGIN, JVM_PLUGIN)

        /** `npx convex function-spec`, with the Windows launcher name on Windows. */
        fun defaultFunctionSpecCommand(): List<String> {
            val npx = if (System.getProperty("os.name").lowercase().contains("win")) "npx.cmd" else "npx"
            return listOf(npx, "--yes", "convex", "function-spec")
        }
    }
}
