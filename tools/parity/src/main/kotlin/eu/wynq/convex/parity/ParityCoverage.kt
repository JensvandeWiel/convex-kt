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

/**
 * Result of comparing the manifest against the upstream test set.
 *
 * @property schema the structural validation of the manifest.
 * @property upstreamRoot upstream checkout that was scanned.
 * @property discovered upstream tests found on disk.
 * @property missing upstream tests with no manifest entry (drift in).
 * @property orphaned manifest references with no upstream test (drift out).
 */
public data class ParityCoverageReport(
    public val schema: ParityReport,
    public val upstreamRoot: String,
    public val discovered: List<UpstreamTest>,
    public val missing: List<UpstreamTest>,
    public val orphaned: List<String>,
) {
    /**
     * `true` only when the manifest is valid, every upstream test is accounted
     * for, and no manifest reference points at a test that no longer exists.
     */
    public val isOk: Boolean get() = schema.isOk && missing.isEmpty() && orphaned.isEmpty()

    /**
     * Renders the coverage result for CI logs.
     *
     * @return the multi-line report.
     */
    public fun render(): String = buildString {
        appendLine(schema.render())
        appendLine("upstream coverage")
        appendLine("  root: $upstreamRoot")
        appendLine("  discovered: ${discovered.size}")
        appendLine("  accounted for: ${discovered.size - missing.size}")
        missing.forEach { appendLine("  missing: ${it.reference}") }
        orphaned.forEach { appendLine("  orphaned reference: $it") }
        append(if (isOk) "COVERAGE: ok" else "COVERAGE: FAILED")
    }
}

/**
 * Enforces "every upstream `convex-rs` test has a Kotlin counterpart" (the first
 * hard rule in `AGENTS.md`).
 *
 * Structural validation alone cannot catch a test that exists upstream but is
 * absent from the manifest; only scanning the upstream sources can. This is the
 * check that makes the rule real, and it runs as an ordinary test so `check`
 * fails on drift without anyone remembering to invoke a separate tool.
 */
public object ParityCoverage {
    /**
     * Validates [manifest] and checks it against the tests in [upstreamRoot].
     *
     * When the manifest is structurally invalid the coverage comparison is
     * skipped: the schema errors are the actionable failure and reporting
     * cascading drift from an unparseable manifest would only add noise.
     *
     * @param manifest the `parity.yaml` manifest.
     * @param upstreamRoot a `convex-rs` checkout to scan.
     * @return the combined schema and coverage report.
     */
    public fun check(manifest: File, upstreamRoot: File): ParityCoverageReport {
        val schema = ParityChecker.check(manifest)
        if (!schema.isOk) {
            return ParityCoverageReport(schema, upstreamRoot.path, emptyList(), emptyList(), emptyList())
        }

        val discovered = RustTestDiscovery.discover(upstreamRoot)
        val discoveredReferences = discovered.mapTo(mutableSetOf()) { it.reference }
        val declaredReferences = schema.requirements.flatMapTo(mutableSetOf()) { it.upstreamTests }

        return ParityCoverageReport(
            schema = schema,
            upstreamRoot = upstreamRoot.path,
            discovered = discovered,
            missing = discovered.filter { it.reference !in declaredReferences },
            orphaned = declaredReferences.filter { it !in discoveredReferences }.sorted(),
        )
    }

    /**
     * Emits `parity.yaml` requirement entries for every unaccounted upstream
     * test, so a new upstream revision is absorbed by appending output rather
     * than by hand-writing references that could be mistyped.
     *
     * @param report a coverage report from [check].
     * @return YAML entries indented for the manifest's `requirements` list.
     */
    public fun emitMissing(report: ParityCoverageReport): String {
        if (report.missing.isEmpty()) {
            return "# nothing to add: every upstream test is accounted for\n"
        }
        val usedIds = mutableSetOf<String>()
        return buildString {
            report.missing.forEach { test ->
                val id = uniqueId(slug(test), usedIds)
                val description =
                    "Upstream test '${test.name}' in '${test.path}' has no Kotlin counterpart yet."
                appendLine("  - id: $id")
                appendLine("    description: \"$description\"")
                appendLine("    status: planned")
                appendLine("    upstream:")
                appendLine("      - \"${test.reference}\"")
                appendLine("")
            }
        }
    }

    private fun slug(test: UpstreamTest): String =
        (test.path.removeSuffix(".rs") + "-" + test.name)
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')

    private fun uniqueId(base: String, used: MutableSet<String>): String {
        if (base.isEmpty()) return "test"
        if (used.add(base)) return base
        var suffix = 2
        while (!used.add("$base-$suffix")) suffix++
        return "$base-$suffix"
    }
}
