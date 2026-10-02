import org.gradle.api.tasks.testing.Test

plugins {
    id("convex-kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Coroutines are part of the public surface: the sync state machine
            // exposes a cold Flow, so consumers must see these types.
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The conformance fixtures live at the repository root. The JVM test decodes the
// captured frames to prove the codecs against real backend traffic.
tasks.named<Test>("jvmTest") {
    systemProperty("convexkt.fixtures", rootProject.file("conformance/fixtures").absolutePath)
}
