import com.android.build.api.dsl.LibraryExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
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
    id("com.vanniktech.maven.publish")
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
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
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
        // Instrumented tests in convex-auth run on a device/emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Maven Central publication for the public libraries. The plugin detects the
// Kotlin Multiplatform and Android plugins and creates a publication per
// target, plus sources jars and Dokka-backed javadoc jars.
//
// Signing is enabled only when a key is configured, so local
// `publishToMavenLocal` runs need no GPG credentials; CI supplies the key
// through the ORG_GRADLE_PROJECT_* secrets in .github/workflows/publish.yml.
val signingKeyPresent: Boolean =
    findProperty("signingInMemoryKey") != null ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null

extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    if (signingKeyPresent) {
        signAllPublications()
    }
    pom {
        name.set(project.name)
        description.set("A Kotlin Multiplatform client for Convex")
        inceptionYear.set("2026")
        url.set("https://github.com/JensvandeWiel/convex-kt/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("JensvandeWiel")
                name.set("Jens van de Wiel")
                url.set("https://github.com/JensvandeWiel/")
            }
        }
        scm {
            url.set("https://github.com/JensvandeWiel/convex-kt/")
            connection.set("scm:git:https://github.com/JensvandeWiel/convex-kt.git")
            developerConnection.set("scm:git:ssh://git@github.com/JensvandeWiel/convex-kt.git")
        }
    }
}
