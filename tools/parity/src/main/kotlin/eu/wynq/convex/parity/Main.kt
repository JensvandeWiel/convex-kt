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
 * Usage: `parity [--manifest <path>]`. The exit code is `0` when the manifest
 * is valid, `1` when validation fails, and `2` for a usage error. This contract
 * is what the CI parity job depends on, so it is documented here.
 */
public fun main(args: Array<String>) {
    val manifest = parseManifestArgument(args)
    if (manifest == null) {
        System.err.println("usage: parity [--manifest <path>]")
        exitProcess(EXIT_USAGE)
    }

    val report = ParityChecker.check(manifest)
    println(report.render())
    exitProcess(if (report.isOk) EXIT_OK else EXIT_FAILURE)
}

private fun parseManifestArgument(args: Array<String>): File? {
    var path = DEFAULT_MANIFEST
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--manifest", "-m" -> {
                if (index + 1 >= args.size) return null
                path = args[index + 1]
                index += 2
            }
            else -> return null
        }
    }
    return File(path)
}
