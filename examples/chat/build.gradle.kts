// Example app: a minimal Compose Desktop chat client.
//
// Deliberately a standalone JVM build rather than the library conventions: an
// example should look like an app, not like a published module.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // `org.jetbrains.kotlin.jvm` comes from buildSrc's classpath, so it is
    // applied by id; the Compose plugins are requested through the catalog.
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(project(":convex-client"))
    implementation(project(":convex-compose"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
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
