import org.gradle.api.tasks.testing.Test

plugins {
    id("convex-kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":convex-core"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        // One engine per supported platform. The engine is an implementation
        // detail; `convex-client` never leaks Ktor types across its API.
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// The conformance fixtures live at the repository root. The JVM test replays
// captured traffic through the client.
tasks.named<Test>("jvmTest") {
    systemProperty("convexkt.fixtures", rootProject.file("conformance/fixtures").absolutePath)
}
