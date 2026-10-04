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

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Captures the backend `apiSpec` by running the Convex CLI.
 *
 * The CLI writes its progress to stderr and only the JSON to stdout, so the
 * captured stream is the spec verbatim. This task always runs: the spec is a
 * view of a remote deployment, not of local files, so Gradle has no local input
 * that could make a cached result safe. Set `convexCodegen { spec = ... }` to
 * bypass it and read a committed file instead.
 */
public abstract class ConvexFunctionSpecTask : DefaultTask() {
    /** An optional command run before [functionSpecCommand], such as a deploy. */
    @get:Input
    public abstract val prepareCommand: ListProperty<String>

    /** The command that writes the `apiSpec` JSON to stdout. */
    @get:Input
    public abstract val functionSpecCommand: ListProperty<String>

    /** The directory the Convex CLI runs in. */
    @get:Internal
    public abstract val workingDirectory: DirectoryProperty

    /** The file the captured JSON is written to. */
    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    /** Runs external processes; injected so the task stays configuration-cache safe. */
    @get:Inject
    public abstract val execOperations: ExecOperations

    /** Runs the optional prepare command, then captures the function spec. */
    @TaskAction
    public fun fetch() {
        val directory = workingDirectory.get().asFile
        check(directory.isDirectory) {
            "convex-codegen: the Convex project directory ${directory.path} does not exist. " +
                "Set `convexCodegen { convexProjectDirectory = ... }`."
        }
        prepareCommand.getOrElse(emptyList())
            .takeIf { it.isNotEmpty() }
            ?.let { run(it, directory) }

        val stdout = ByteArrayOutputStream()
        execOperations.exec { spec ->
            spec.workingDir = directory
            spec.commandLine(functionSpecCommand.get())
            spec.standardOutput = stdout
        }
        val json = stdout.toString(Charsets.UTF_8)
        require(json.isNotBlank()) {
            "convex-codegen: `${functionSpecCommand.get().joinToString(" ")}` produced no apiSpec. " +
                "Is the Convex deployment configured (`npx convex dev`)?"
        }
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(json)
        logger.lifecycle("Captured Convex apiSpec into ${output.path}")
    }

    private fun run(command: List<String>, directory: File) {
        execOperations.exec { spec ->
            spec.workingDir = directory
            spec.commandLine(command)
        }
    }
}
