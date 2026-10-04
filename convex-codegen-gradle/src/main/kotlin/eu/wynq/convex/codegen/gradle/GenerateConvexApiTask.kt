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

import eu.wynq.convex.codegen.ApiSpecParser
import eu.wynq.convex.codegen.KotlinSourceGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates the typed Convex descriptors from a captured `apiSpec`.
 *
 * The output is byte-identical to the `convex-codegen` CLI because the same
 * engine produces it; this task only supplies Gradle's inputs, outputs, and
 * up-to-date checks.
 */
public abstract class GenerateConvexApiTask : DefaultTask() {
    /** The backend `apiSpec` JSON file. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val spec: RegularFileProperty

    /** The package for the generated file. */
    @get:Input
    public abstract val packageName: Property<String>

    /** The top-level object that holds the generated modules. */
    @get:Input
    public abstract val objectName: Property<String>

    /** The directory the generated Kotlin is written to. */
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    /** Parses the spec and writes the generated Kotlin. */
    @TaskAction
    public fun generate() {
        val specFile = spec.get().asFile
        val functions = ApiSpecParser.parse(specFile.readText())
        require(functions.isNotEmpty()) {
            "no Convex functions found in ${specFile.path}; deploy the backend and run " +
                "`npx convex function-spec > ${specFile.name}` first"
        }
        val source = KotlinSourceGenerator.generate(packageName.get(), objectName.get(), functions)
        val outputFile = outputDirectory.get().asFile.resolve("${objectName.get()}.kt")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(source)
        logger.lifecycle("Generated ${functions.size} Convex functions into ${outputFile.path}")
    }
}
