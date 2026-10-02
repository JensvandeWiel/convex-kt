import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Convention plugin for the public, multiplatform `convex-*` library modules.
//
// It declares the exact target matrix promised by the project plan — Android,
// JVM desktop, and the three Apple targets — enables `explicitApi()` so a
// missing visibility modifier fails the build, and applies the shared quality
// pillars (Detekt, Spotless, Dokka, Kover). See the "Development guardrails" and
// "Quality standard" sections of AGENTS.md.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    id("convex-quality")
}

// `convex-core` -> `eu.wynq.convex.core`. Both the Android namespace and the
// iOS/Android source-set wiring depend on the module name, so deriving it here
// keeps the mapping in one place.
val moduleNamespace: String =
    "eu.wynq.convex." + project.name.removePrefix("convex-").replace('-', '.')

extensions.configure<KotlinMultiplatformExtension> {
    explicitApi()

    jvm {
        compilerOptions {
            // Matches the Android and JVM toolchain pinned in the root build.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets.getByName("commonTest").dependencies {
        implementation(kotlin("test"))
    }
}

extensions.configure<LibraryExtension> {
    namespace = moduleNamespace
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
