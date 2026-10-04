// Root build file.
//
// There is deliberately no `plugins {}` block here. `buildSrc` compiles the
// Kotlin and Android Gradle plugins and contributes them to the build classpath,
// and the `convex-*` convention plugins apply them by id. Declaring the same
// plugins again here would fail plugin resolution because the classpath copy has
// no resolvable version. Plugins that the conventions do not apply (Compose,
// serialization) are requested per module through the version catalog.

// The unified API site (`./gradlew dokkaGenerateHtml`, output in
// `build/dokka/html`). Applied the legacy way on purpose: a `plugins {}`
// version lookup would trip over the buildSrc classpath copy, while
// `apply(plugin = ...)` resolves the already-pinned Dokka 2.2.0 from it.
// Child modules contribute through the `convex-*` conventions, which already
// apply the same plugin by id.
apply(plugin = "org.jetbrains.dokka")

// Dokka 2.x aggregates subproject publications declared here. Only the five
// client-facing library modules are listed: the build tools (the codegen engine
// and its Gradle plugin, parity, integration tests) keep their standalone pages
// out of the user-facing site, and the Gradle plugin's consumer interface is the
// `convexCodegen { }` DSL rather than a Kotlin API.
// General guides cannot live on the generated landing page (Dokka renders
// module-attached docs only), so prose lives in the `Writerside/` help module
// while Dokka stays API-only. See README.md for how the two surfaces split.
dependencies {
    add("dokka", project(":convex-core"))
    add("dokka", project(":convex-client"))
    add("dokka", project(":convex-auth"))
    add("dokka", project(":convex-storage"))
    add("dokka", project(":convex-compose"))
}

allprojects {
    group = "eu.wynq.convex"
    version = "0.2.0"
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
