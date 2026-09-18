plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
}

allprojects {
    // Version values extracted here so resolutionStrategy rules are easy to find and update.
    // NOTE: version refs (kotlin, kotlin-serialization, kotlinxCollectionsImmutable) are
    // shadowed by generated nested accessors from library keys starting with "kotlin-"
    // (kotlin-test, kotlin-test-junit). Using direct values instead of catalog refs.
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.3.21")
            }
            if (requested.group == "org.jetbrains.kotlinx") {
                if (requested.name == "kotlinx-serialization-json" ||
                    requested.name == "kotlinx-serialization-core" ||
                    requested.name == "kotlinx-serialization-bom") {
                    useVersion("1.11.0")
                }
                if (requested.name == "kotlinx-collections-immutable") {
                    useVersion("0.5.0")
                }
            }
        }
    }
}
