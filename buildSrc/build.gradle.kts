plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // These plugins must be on buildSrc's compile classpath because the
    // precompiled script plugins in src/main/kotlin apply them by id.
    implementation("com.android.tools.build:gradle:8.13.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
}
