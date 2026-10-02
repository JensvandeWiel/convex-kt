plugins {
    id("convex-jvm-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":convex-core"))
    implementation(libs.kotlinx.serialization.json)
}
