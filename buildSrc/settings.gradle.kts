// buildSrc is an independent build; it needs its own repository declarations so
// that it can compile the Android and Kotlin Gradle plugins that the convention
// plugins below apply.

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
