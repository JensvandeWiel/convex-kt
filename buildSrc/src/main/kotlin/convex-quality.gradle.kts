// Shared quality convention applied to every module (KMP and JVM).
//
// This is where the project's stated standard becomes something the build can
// enforce rather than something reviewers must remember:
//
//   Spotless        formatting (ktlint_official + Gradle script formatting)
//   Detekt          static analysis, including the AGENTS.md guardrails
//   Kover           coverage collection and a verification floor
//
// KDoc/Dokka and the public-API dumps are applied by the module-type
// conventions, because not every module needs them.

import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

plugins {
    id("com.diffplug.spotless")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

// Detekt reads one shared config so every module is judged identically, and the
// guardrail rules can never drift between modules.
extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    // No baselines: every finding is fixed or the rule is changed deliberately.
    baseline = null
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    // Baselines are intentionally unsupported; see the note above.
    enabled = false
}

extensions.configure<SpotlessExtension> {
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**")
        ktlint("1.5.0").editorConfigOverride(
            mapOf(
                "ktlint_standard_function-signature" to "disabled",
                "ktlint_standard_class-signature" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
            ),
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint("1.5.0")
    }
}

extensions.configure<KoverProjectExtension> {
    reports {
        filters {
            excludes {
                // Generated stubs and compile-time markers are not behaviour and
                // must not inflate coverage.
                classes(
                    "*_Factory*",
                    "*_Impl*",
                    "*Module",
                )
            }
        }
        // The floor is deliberately low while the protocol implementation is
        // being built out; it is ratcheted upward as convex-core lands. A gate
        // that is trivially true today still catches a coverage regression
        // tomorrow.
        verify {
            rule {
                minBound(20)
            }
        }
    }
}
