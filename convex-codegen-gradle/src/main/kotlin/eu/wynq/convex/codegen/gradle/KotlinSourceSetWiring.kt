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

import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.TaskProvider

/**
 * Adds the generated directory to the configured Kotlin source set.
 *
 * The Kotlin Gradle plugin is an *optional* dependency: a project that applies
 * `eu.wynq.convex.codegen` may or may not apply Kotlin, and even when it does,
 * Gradle loads the two plugins in separate classloaders unless one declares the
 * other. Referencing `KotlinProjectExtension` directly would therefore fail the
 * moment the plugin runs. The KGP getters used here (`getSourceSets`, and
 * `KotlinSourceSet.getKotlin`) are stable public API and give back plain Gradle
 * types, so they are read reflectively and the plugin stays independent of the
 * Kotlin plugin's classpath.
 *
 * Wiring happens in `afterEvaluate` because a target source set such as
 * `jvmMain` does not exist until the consumer's `kotlin { }` block has declared
 * its targets. The compile tasks are made to depend on generation by the
 * caller, so adding the directory here still runs before compilation.
 */
internal object KotlinSourceSetWiring {
    /**
     * Registers the task's output as a source directory of the configured source
     * set.
     *
     * @param project the project the plugin is applied to.
     * @param extension the plugin configuration.
     * @param generate the generation task whose output is wired in.
     */
    fun wire(
        project: Project,
        extension: ConvexCodegenExtension,
        generate: TaskProvider<GenerateConvexApiTask>,
    ) {
        val generatedDirectory = generate.flatMap { it.outputDirectory }
        project.afterEvaluate {
            val sourceSetName = extension.sourceSet.get()
            // `findByName` rather than `getByName`: a non-Kotlin project has no
            // `kotlin` extension at all and should not fail.
            val kotlin = project.extensions.findByName("kotlin")
            if (kotlin == null) {
                project.logger.warn(
                    "convex-codegen: no Kotlin plugin is applied, so generated sources are not " +
                        "added to a source set. The `generateConvexApi` task still works.",
                )
                return@afterEvaluate
            }

            val sourceSets = kotlin.readProperty<NamedDomainObjectCollection<*>>(project, "sourceSets")
                ?: return@afterEvaluate
            val sourceSet = sourceSets.findByName(sourceSetName)
            checkNotNull(sourceSet) {
                "convex-codegen: Kotlin source set '$sourceSetName' does not exist. " +
                    "Set `convexCodegen { sourceSet = \"...\" }` to an existing source set."
            }
            val kotlinSources = sourceSet.readProperty<SourceDirectorySet>(project, "kotlin")
                ?: return@afterEvaluate
            kotlinSources.srcDir(generatedDirectory)
        }
    }

    /**
     * Reads a public Gradle-typed property from a Kotlin plugin object.
     *
     * Returns `null` and logs a warning when the property is not the expected
     * type, so a future Kotlin plugin that renames one of them degrades to "not
     * wired" rather than failing the build.
     */
    private inline fun <reified T> Any.readProperty(project: Project, property: String): T? {
        val getter = "get" + property.replaceFirstChar(Char::uppercaseChar)
        val value = try {
            javaClass.getMethod(getter).invoke(this)
        } catch (failure: ReflectiveOperationException) {
            project.logger.warn(
                "convex-codegen: could not read Kotlin `$property`; generated sources are not wired in.",
                failure,
            )
            return null
        }
        return value as? T
    }
}
