// convex-kt — a Kotlin Multiplatform Convex client.
//
// The build is intentionally explicit about repositories: plugin resolution may
// use the Gradle Plugin Portal, but project dependencies are restricted to
// Google (Android) and Maven Central so that a build never silently depends on
// an untracked repository. See AGENTS.md for the architectural rationale.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "convex-kt"

include(
    ":convex-core",
    ":convex-client",
    ":convex-auth",
    ":convex-storage",
    ":convex-compose",
    ":convex-codegen",
    ":tools:parity",
)
