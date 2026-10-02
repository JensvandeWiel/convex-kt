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
package eu.wynq.convex.parity

import java.io.File
import kotlin.system.exitProcess

private const val DEFAULT_MANIFEST = "parity.yaml"
private const val EXIT_OK = 0
private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2

/**
 * Entry point for the `:tools:parity` command-line validator.
 *
 * Modes:
 * - default: validate the manifest's structure.
 * - `--upstream <root>`: also require every upstream test to be accounted for.
 * - `--emit-missing <root>`: print manifest entries for unaccounted tests.
 *
 * Usage: `parity [--manifest <path>] [--upstream <root> | --emit-missing <root>]`.
 * The exit code is `0` on success, `1` on a failed check, and `2` for a usage
 * error, which is the contract the CI parity job depends on.
 *
 * Run `--emit-missing` and append its output to `parity.yaml` when adding a new
 * upstream revision, rather than inventing references by hand.
 */
public fun main(args: Array<String>) {
    val options = parseArguments(args)
    if (options == null) {
        System.err.println(
            "usage: parity [--manifest <path>] [--upstream <root> | --emit-missing <root>]",
        )
        exitProcess(EXIT_USAGE)
    }

    if (options.emitMissingRoot != null) {
        val report = ParityCoverage.check(options.manifest, options.emitMissingRoot)
        if (!report.schema.isOk) {
            println(report.schema.render())
            exitProcess(EXIT_FAILURE)
        }
        print(ParityCoverage.emitMissing(report))
        exitProcess(EXIT_OK)
    }

    if (options.upstreamRoot != null) {
        val report = ParityCoverage.check(options.manifest, options.upstreamRoot)
        println(report.render())
        exitProcess(if (report.isOk) EXIT_OK else EXIT_FAILURE)
    }

    val report = ParityChecker.check(options.manifest)
    println(report.render())
    exitProcess(if (report.isOk) EXIT_OK else EXIT_FAILURE)
}

private data class Options(
    val manifest: File,
    val upstreamRoot: File?,
    val emitMissingRoot: File?,
)

private fun parseArguments(args: Array<String>): Options? =
    try {
        parseOrThrow(args)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun parseOrThrow(args: Array<String>): Options {
    var manifest = DEFAULT_MANIFEST
    var upstream: File? = null
    var emitMissing: File? = null
    var index = 0
    while (index < args.size) {
        val value = args.getOrNull(index + 1)
            ?: throw IllegalArgumentException("missing value for ${args[index]}")
        when (args[index]) {
            "--manifest", "-m" -> manifest = value
            "--upstream" -> upstream = File(value)
            "--emit-missing" -> emitMissing = File(value)
            else -> throw IllegalArgumentException("unknown argument: ${args[index]}")
        }
        index += 2
    }
    require(upstream == null || emitMissing == null) {
        "--upstream and --emit-missing are mutually exclusive"
    }
    return Options(File(manifest), upstream, emitMissing)
}
