import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Convention plugin for build-time-only JVM modules (codegen, the parity tool).
// These never ship to a client platform, so they stay plain JVM projects rather
// than joining the multiplatform target matrix.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
    id("convex-quality")
}

extensions.configure<KotlinJvmProjectExtension> {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The Kotlin JVM plugin also applies the Java plugin, whose compilation defaults
// to the running JDK (21). Align it with the Kotlin target so Gradle's
// inconsistent-JVM-target check stays quiet without pulling in a toolchain.
extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
