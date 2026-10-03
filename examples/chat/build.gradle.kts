// Example app: a minimal Compose Desktop chat client.
//
// Deliberately a standalone JVM build rather than the library conventions: an
// example should look like an app, not like a published module.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // `org.jetbrains.kotlin.jvm` comes from buildSrc's classpath, so it is
    // applied by id; the Compose plugins are requested through the catalog.
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // Examples ship no public API, but they are still held to the formatting
    // and static-analysis gates so sample code never rots past the standard.
    id("convex-quality")
}

dependencies {
    implementation(project(":convex-client"))
    implementation(project(":convex-compose"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
}

// Without explicit targets the Java and Kotlin tasks each follow the running
// JDK and disagree (Gradle fails with "Inconsistent JVM Target
// Compatibility"). Pin both to 21, the JDK this project builds with.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

// No unit-testable logic ships here (a Compose UI over the gated libraries),
// so the coverage floor does not apply; formatting and analysis still do. The
// floor itself is untouched for every library module.
tasks.named("koverVerify") {
    enabled = false
}

compose.desktop {
    application {
        mainClass = "eu.wynq.convex.example.chat.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "convex-kt-chat"
            packageVersion = "1.0.0"
        }
    }
}
