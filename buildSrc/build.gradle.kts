plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // These plugins must be on buildSrc's compile classpath because the
    // precompiled script plugins in src/main/kotlin apply them by id.
    implementation("com.android.tools.build:gradle:8.13.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    // KDoc is mandatory on public APIs; Dokka is applied by the conventions so
    // every public module produces docs without opting in by hand.
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0")
    // Published-library API surface is checked by dumps rather than by hope.
    implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.18.2")
    // Quality pillars, applied by the `convex-quality` convention plugin.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.9.0")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.11")
    // Maven Central publication, applied by the `convex-kmp-library`
    // convention: KMP-aware publications, sources/javadoc (Dokka) jars,
    // signing, and the Central Portal upload/release tasks.
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.37.0")
}
