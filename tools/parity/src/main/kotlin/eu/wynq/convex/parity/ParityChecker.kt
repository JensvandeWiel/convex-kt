package eu.wynq.convex.parity

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File

/**
 * Lifecycle state of a single upstream requirement tracked in `parity.yaml`.
 *
 * The wire names are part of the manifest contract, so they are kept explicit
 * rather than derived from the enum constant name. A rename here must be
 * reflected in the manifest, which the parity check will catch.
 *
 * @property wireName the literal string accepted in `parity.yaml`.
 */
public enum class ParityStatus(public val wireName: String) {
    /** Upstream has the test; Kotlin does not yet. Allowed to lack Kotlin refs. */
    PLANNED("planned"),

    /** Upstream and Kotlin both have the test; both reference lists must be present. */
    PORTED("ported"),

    /** Deliberately out of scope, with a documented reason in the manifest. */
    NOT_APPLICABLE("not-applicable"),
    ;

    /** Resolves manifest `status` strings to their enum constants. */
    public companion object {
        /**
         * Resolves a manifest `status` value.
         *
         * @param value the raw YAML string.
         * @return the matching status, or `null` when the value is unknown.
         */
        public fun fromWire(value: String): ParityStatus? =
            entries.firstOrNull { it.wireName == value }
    }
}

/**
 * One entry in `parity.yaml`: an upstream test mapped to its Kotlin counterpart.
 *
 * @property id stable, unique identifier used by the drift report.
 * @property description human-readable summary of what is being proven.
 * @property status current porting state.
 * @property upstreamTests references to upstream tests, e.g. `crate::test_name`.
 * @property kotlinTests references to Kotlin tests, e.g. `module.Class.method`.
 */
public data class ParityRequirement(
    public val id: String,
    public val description: String,
    public val status: ParityStatus,
    public val upstreamTests: List<String>,
    public val kotlinTests: List<String>,
)

/**
 * Outcome of validating a `parity.yaml` manifest.
 *
 * @property manifestPath path that was inspected, for diagnostics.
 * @property requirements successfully parsed requirements.
 * @property errors problems that must fail the build.
 * @property warnings problems that should be surfaced but not fail the build.
 */
public data class ParityReport(
    public val manifestPath: String,
    public val requirements: List<ParityRequirement>,
    public val errors: List<String>,
    public val warnings: List<String>,
) {
    /** `true` when the manifest is structurally valid. */
    public val isOk: Boolean get() = errors.isEmpty()

    /**
     * Renders a human-readable report suitable for CI logs.
     *
     * @return the multi-line report text.
     */
    public fun render(): String = buildString {
        appendLine("convex-kt parity check")
        appendLine("  manifest: $manifestPath")
        appendLine("  requirements: ${requirements.size}")
        if (requirements.isNotEmpty()) {
            val counts = requirements.groupingBy { it.status.wireName }.eachCount()
            counts.toSortedMap().forEach { (status, count) ->
                appendLine("    $status: $count")
            }
        }
        warnings.forEach { appendLine("  warning: $it") }
        errors.forEach { appendLine("  error: $it") }
        append(if (isOk) "RESULT: ok" else "RESULT: FAILED")
    }
}

/**
 * Validates the machine-readable parity manifest.
 *
 * This is the tool the plan uses to gate pull requests: CI runs it and fails
 * when the manifest is malformed or has drifted from the documented schema. See
 * the "Full Test-Suite Parity" rule in `AGENTS.md`.
 */
public object ParityChecker {
    /** Schema version this tool understands. */
    public const val SUPPORTED_SCHEMA_VERSION: Int = 1

    /**
     * Parses and validates [manifest].
     *
     * The check never throws for malformed input; every problem is reported in
     * [ParityReport.errors] so CI can print all of them at once instead of
     * failing on the first line.
     *
     * @param manifest the `parity.yaml` file to validate.
     * @return the validation report.
     */
    public fun check(manifest: File): ParityReport {
        if (!manifest.isFile) {
            return failure(manifest, "manifest not found: ${manifest.path}")
        }
        return checkParsed(manifest)
    }

    /** Validates an existing manifest file; the caller has checked it exists. */
    private fun checkParsed(manifest: File): ParityReport {
        val root: Any? = try {
            // The explicit type argument avoids Kotlin inferring the generic
            // return as `Nothing?`, whose erasure is `java.lang.Void` and would
            // trigger a ClassCastException on the parsed map.
            Yaml().load<Any?>(manifest.readText())
        } catch (failure: YAMLException) {
            return failure(manifest, "manifest is not valid YAML: ${failure.message}")
        }

        if (root !is Map<*, *>) {
            return failure(manifest, "manifest root must be a mapping")
        }

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        validateSchemaVersion(root, errors)

        val rawRequirements = root["requirements"]
        if (rawRequirements !is List<*>) {
            errors += "'requirements' must be a list"
            return ParityReport(manifest.path, emptyList(), errors, warnings)
        }

        if (rawRequirements.isEmpty()) {
            warnings += "'requirements' is empty; the parity gate is inactive until entries are added"
        }

        val requirements = mutableListOf<ParityRequirement>()
        val seenIds = mutableSetOf<String>()

        rawRequirements.forEachIndexed { index, raw ->
            if (raw !is Map<*, *>) {
                errors += "requirements[$index] must be a mapping"
                return@forEachIndexed
            }
            parseRequirement(index, raw, seenIds, errors, warnings)?.let(requirements::add)
        }

        return ParityReport(manifest.path, requirements, errors, warnings)
    }

    /** Builds a report carrying a single fatal [message]; keeps `check` linear. */
    private fun failure(manifest: File, message: String): ParityReport = ParityReport(
        manifestPath = manifest.path,
        requirements = emptyList(),
        errors = listOf(message),
        warnings = emptyList(),
    )

    private fun validateSchemaVersion(root: Map<*, *>, errors: MutableList<String>) {
        val version = root["schemaVersion"]
        when (version) {
            is Int -> if (version != SUPPORTED_SCHEMA_VERSION) {
                errors += "unsupported schemaVersion $version; expected $SUPPORTED_SCHEMA_VERSION"
            }
            null -> errors += "missing required field 'schemaVersion'"
            else -> errors += "'schemaVersion' must be an integer"
        }
    }

    private fun parseRequirement(
        index: Int,
        raw: Map<*, *>,
        seenIds: MutableSet<String>,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ): ParityRequirement? {
        val id = (raw["id"] as? String)?.trim().orEmpty()
        if (id.isEmpty()) {
            errors += "requirements[$index]: 'id' must be a non-empty string"
            return null
        }
        if (!seenIds.add(id)) {
            errors += "requirements[$index]: duplicate id '$id'"
            return null
        }

        val statusWire = (raw["status"] as? String)?.trim().orEmpty()
        val status = ParityStatus.fromWire(statusWire)
        if (status == null) {
            errors += "requirements[$index] ('$id'): unknown status '$statusWire'"
            return null
        }

        val upstream = stringList(raw["upstream"], "requirements[$index] ('$id').upstream", errors)
        val kotlin = stringList(raw["kotlin"], "requirements[$index] ('$id').kotlin", errors)
        val description = (raw["description"] as? String)?.trim().orEmpty()

        when (status) {
            ParityStatus.PLANNED -> {
                if (upstream.isEmpty()) {
                    errors += "requirements[$index] ('$id'): planned requirements need an 'upstream' reference"
                }
                if (kotlin.isNotEmpty()) {
                    warnings += "requirements[$index] ('$id'): 'planned' but already lists " +
                        "Kotlin tests; promote it to 'ported'"
                }
            }
            ParityStatus.PORTED -> {
                if (upstream.isEmpty()) {
                    errors += "requirements[$index] ('$id'): ported requirements need an 'upstream' reference"
                }
                if (kotlin.isEmpty()) {
                    errors += "requirements[$index] ('$id'): ported requirements need a 'kotlin' reference"
                }
            }
            ParityStatus.NOT_APPLICABLE -> {
                if (description.isEmpty()) {
                    warnings += "requirements[$index] ('$id'): 'not-applicable' without a description"
                }
            }
        }

        return ParityRequirement(id, description, status, upstream, kotlin)
    }

    private fun stringList(
        value: Any?,
        field: String,
        errors: MutableList<String>,
    ): List<String> {
        if (value == null) return emptyList()
        if (value !is List<*>) {
            errors += "$field must be a list of strings"
            return emptyList()
        }
        val result = mutableListOf<String>()
        value.forEachIndexed { itemIndex, item ->
            val text = (item as? String)?.trim().orEmpty()
            if (text.isEmpty()) {
                errors += "$field[$itemIndex] must be a non-empty string"
            } else {
                result += text
            }
        }
        return result
    }
}
