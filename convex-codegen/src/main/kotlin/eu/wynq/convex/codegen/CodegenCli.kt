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

import eu.wynq.convex.core.ConvexException
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * The command line entry point for `convex-codegen`.
 *
 * It reads an `apiSpec` JSON file and writes a Kotlin source file, so the
 * generated API can be produced as a build step:
 *
 * ```
 * convex-codegen --spec api-spec.json --package com.example.convex \
 *     --object Api --out src/commonMain/kotlin/com/example/convex/Api.kt
 * ```
 *
 * Obtain `api-spec.json` from a running backend with an admin key:
 *
 * ```
 * curl -s http://127.0.0.1:3210/api/query \
 *   -H 'Authorization: Convex <admin-key>' \
 *   -H 'Content-Type: application/json' \
 *   -d '{"path":"_system/cli/modules:apiSpec","args":{},"format":"json"}' \
 *   > api-spec.json
 * ```
 *
 * The `value` field of that response is the spec; with `format: "json"` the
 * whole response's `value` is the function array the parser expects.
 */
public object CodegenCli {
    private const val EXIT_USAGE = 2
    private const val EXIT_FAILURE = 1

    /**
     * Runs the generator.
     *
     * @param args the command line arguments.
     */
    public fun run(args: Array<String>) {
        val options = try {
            Options.parse(args)
        } catch (failure: IllegalArgumentException) {
            System.err.println(failure.message)
            System.err.println(USAGE)
            exitProcess(EXIT_USAGE)
        }

        val functions = try {
            ApiSpecParser.parse(options.spec.readText())
        } catch (failure: IOException) {
            System.err.println("failed to read spec ${options.spec}: ${failure.message}")
            exitProcess(EXIT_FAILURE)
        } catch (failure: ConvexException) {
            // An unknown function kind: a spec problem, reported like a
            // parse error rather than a stack trace.
            System.err.println("failed to parse spec ${options.spec}: ${failure.message}")
            exitProcess(EXIT_FAILURE)
        } catch (failure: IllegalArgumentException) {
            // JSON parse errors (SerializationException is an
            // IllegalArgumentException).
            System.err.println("failed to parse spec ${options.spec}: ${failure.message}")
            exitProcess(EXIT_FAILURE)
        }

        if (functions.isEmpty()) {
            System.err.println("no functions found in ${options.spec}")
            exitProcess(EXIT_FAILURE)
        }

        val source = KotlinSourceGenerator.generate(options.packageName, options.objectName, functions)
        options.out.parentFile?.mkdirs()
        options.out.writeText(source)
        println("wrote ${functions.size} functions to ${options.out}")
    }

    private const val USAGE =
        "usage: convex-codegen --spec <file> --package <name> --object <name> --out <file>"

    /**
     * The parsed command line options.
     *
     * `internal` rather than `private` so the argument parser can be covered by
     * a unit test without launching a process.
     */
    internal data class Options(
        val spec: File,
        val packageName: String,
        val objectName: String,
        val out: File,
    ) {
        companion object {
            fun parse(args: Array<String>): Options {
                val values = mutableMapOf<String, String>()
                var index = 0
                while (index < args.size) {
                    val key = args[index]
                    require(key.startsWith("--")) { "unexpected argument '$key'" }
                    require(index + 1 < args.size) { "missing value for '$key'" }
                    values[key.removePrefix("--")] = args[index + 1]
                    index += 2
                }
                return Options(
                    spec = File(requireNotNull(values["spec"]) { "missing --spec" }),
                    packageName = requireNotNull(values["package"]) { "missing --package" },
                    objectName = requireNotNull(values["object"]) { "missing --object" },
                    out = File(requireNotNull(values["out"]) { "missing --out" }),
                )
            }
        }
    }
}

/**
 * The process entry point, kept outside [CodegenCli] because the `application`
 * plugin requires a static `main`.
 *
 * @param args the command line arguments.
 */
public fun main(args: Array<String>) {
    CodegenCli.run(args)
}
