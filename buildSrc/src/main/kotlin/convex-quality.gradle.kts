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

// On a Kotlin Multiplatform project the plain `detekt` task is an inert
// aggregate: the real analysis happens in per-source-set tasks such as
// `detektMetadataCommonMain` and `detektJvmMain`. Without wiring, `./gradlew
// detekt` succeeds while analyzing nothing, which is a silently disabled gate.
// Make the aggregate depend on every analyzing task in this project.
val detektAnalysisTasks = tasks.matching { task ->
    task.name.startsWith("detekt") &&
        !task.name.startsWith("detektBaseline") &&
        task.name != "detektGenerateConfig" &&
        task.name != "detekt"
}

tasks.named("detekt") {
    // The aggregate task has no sources of its own; the depended-on per-source-set
    // tasks perform the actual analysis.
    dependsOn(detektAnalysisTasks)
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    // Baselines are intentionally unsupported; see the note above.
    enabled = false
}

extensions.configure<SpotlessExtension> {
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**")
        // Every source file carries the Apache 2.0 header so provenance is
        // unambiguous in a published artifact. `spotlessApply` inserts it.
        licenseHeader(
            """
            |/*
            | * Copyright 2026 convex-kt contributors
            | *
            | * Licensed under the Apache License, Version 2.0 (the "License");
            | * you may not use this file except in compliance with the License.
            | * You may obtain a copy of the License at
            | *
            | *     http://www.apache.org/licenses/LICENSE-2.0
            | *
            | * Unless required by applicable law or agreed to in writing, software
            | * distributed under the License is distributed on an "AS IS" BASIS,
            | * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            | * See the License for the specific language governing permissions and
            | * limitations under the License.
            | */
            |
            """.trimMargin(),
        )
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
