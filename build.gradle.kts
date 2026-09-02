plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.4.10")
            }
            if (requested.group == "org.jetbrains.kotlinx" && requested.name == "kotlinx-serialization-json") {
                useVersion("1.11.0")
            }
            if (requested.group == "org.jetbrains.kotlinx" && requested.name == "kotlinx-serialization-core") {
                useVersion("1.11.0")
            }
            if (requested.group == "org.jetbrains.kotlinx" && requested.name == "kotlinx-serialization-bom") {
                useVersion("1.11.0")
            }
        }
    }
}
