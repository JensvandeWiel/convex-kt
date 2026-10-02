plugins {
    id("convex-jvm-library")
    application
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("eu.wynq.convex.parity.MainKt")
}

// Run the validator from the repository root so the default `--manifest
// parity.yaml` resolves regardless of which directory invoked Gradle.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(libs.snakeyaml)
    testImplementation(kotlin("test"))
}
