plugins {
    id("convex-kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":convex-core"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test.ext:junit:1.2.1")
                implementation("androidx.test:runner:1.6.2")
            }
        }
    }
}
