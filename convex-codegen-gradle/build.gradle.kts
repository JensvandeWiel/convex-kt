plugins {
    id("java-gradle-plugin")
    id("convex-jvm-publish")
}

dependencies {
    // The plugin is a thin Gradle skin over the codegen engine; generation
    // behaviour lives in `convex-codegen` so the CLI and the plugin produce
    // byte-identical output. The Kotlin Gradle plugin is deliberately not a
    // dependency: it is optional and discovered at runtime, so the coupling is
    // kept at arm's length (see KotlinSourceSetWiring).
    implementation(project(":convex-codegen"))

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("convexCodegen") {
            id = "eu.wynq.convex.codegen"
            implementationClass = "eu.wynq.convex.codegen.gradle.ConvexCodegenPlugin"
            displayName = "convex-kt code generation"
            description = "Generates typed Kotlin descriptors from a Convex apiSpec."
        }
    }
}
