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

// The coverage test needs the manifest and the upstream checkout. Inject them
// as absolute paths so the test does not depend on the working directory, and
// so developers can run it from an IDE once the properties are configured.
tasks.named<Test>("test") {
    systemProperty("convexkt.manifest", rootProject.file("parity.yaml").absolutePath)
    systemProperty("convexkt.upstreamRoot", rootProject.file("third_party/convex-rs").absolutePath)
}

dependencies {
    implementation(libs.snakeyaml)
    testImplementation(kotlin("test"))
}
