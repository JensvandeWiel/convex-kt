plugins {
    id("convex-jvm-library")
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    api(project(":convex-core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "eu.wynq.convex.codegen.CodegenCliKt"
}
