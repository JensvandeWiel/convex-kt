// Integration tests run against a real, pinned backend started with
// Testcontainers. They are a separate `integrationTest` task on purpose: they
// need Docker and Node, so the ordinary `build` must not depend on them.
//
// CI runs `:integration-tests:integrationTest` in its own job.

plugins {
    id("convex-jvm-library")
}

dependencies {
    testImplementation(project(":convex-client"))
    testImplementation(project(":convex-auth"))
    testImplementation(project(":convex-storage"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.testcontainers)
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

// The unit `test` task is disabled: everything here needs the container.
tasks.named<Test>("test") {
    enabled = false
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Runs Testcontainers-backed tests against the pinned Convex backend."
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        systemProperty(
            "convexkt.conformanceProject",
            rootProject.file("conformance/harness/project").absolutePath,
        )
        // Surface container and deploy output when something goes wrong.
        testLogging {
            events("failed")
            showStandardStreams = true
            showExceptions = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

// `integrationTest` is intentionally NOT wired into `check`: it needs Docker and
// Node, so `build` stays runnable anywhere. CI invokes it in its own job.
