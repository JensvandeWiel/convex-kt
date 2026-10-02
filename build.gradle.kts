// Root build file.
//
// There is deliberately no `plugins {}` block here. `buildSrc` compiles the
// Kotlin and Android Gradle plugins and contributes them to the build classpath,
// and the `convex-*` convention plugins apply them by id. Declaring the same
// plugins again here would fail plugin resolution because the classpath copy has
// no resolvable version. Plugins that the conventions do not apply (Compose,
// Dokka, serialization) are requested per module through the version catalog.

allprojects {
    group = "eu.wynq.convex"
    version = "0.1.0-SNAPSHOT"
}
