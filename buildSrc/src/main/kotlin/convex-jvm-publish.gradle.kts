import com.vanniktech.maven.publish.MavenPublishBaseExtension

// Convention plugin for published JVM-only modules: the codegen engine and the
// Gradle plugin built on top of it. It is `convex-jvm-library` plus Maven
// Central publication, so the build tools ship as normal artifacts instead of
// being reachable only from inside this repository.

plugins {
    id("convex-jvm-library")
    id("com.vanniktech.maven.publish")
}

extensions.configure<MavenPublishBaseExtension> {
    configureConvexPublishing(
        project = project,
        artifactName = project.name,
        artifactDescription = "Kotlin code generation for Convex",
    )
}
