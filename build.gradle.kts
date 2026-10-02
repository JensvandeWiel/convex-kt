// Root build file.
//
// There is deliberately no `plugins {}` block here. `buildSrc` compiles the
// Kotlin and Android Gradle plugins and contributes them to the build classpath,
// and the `convex-*` convention plugins apply them by id. Declaring the same
// plugins again here would fail plugin resolution because the classpath copy has
// no resolvable version. Plugins that the conventions do not apply (Compose,
// serialization) are requested per module through the version catalog.

allprojects {
    group = "eu.wynq.convex"
    version = "0.1.0-SNAPSHOT"
}

// `check` on each module is the local "is this module acceptable" entry point,
// and each module's `check` already depends on its own Spotless, Detekt, Kover,
// and API tasks (see the `convex-*` convention plugins). The root project has no
// plugins and therefore no lifecycle `check` task of its own, so register an
// aggregate that runs every module's checks in one invocation.
tasks.register("checkAll") {
    group = "verification"
    description = "Runs formatting, static analysis, API checks, coverage, and tests for every module."
    // Depend on the leaf projects that actually have a `check` task; intermediate
    // container projects such as `:tools` do not.
    dependsOn(subprojects.filter { it.tasks.names.contains("check") }.map { "${it.path}:check" })
}
