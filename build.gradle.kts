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
    // koinCompiler — applied directly (not via version catalog alias) because
    // the plugin id "io.insert-koin.compiler.plugin" is incompatible with catalog accessor.
    id("io.insert-koin.compiler.plugin").version("1.2.1") apply false
}

allprojects {
    // Version constants from gradle.properties (not catalog) — avoids catalog accessor shadowing
    // caused by library keys like jvm-test that generate nested accessors colliding with version keys.
    val kotlinVersion: String = project.property("version.kotlin") as String
    val serializationVersion: String = project.property("version.kotlinSerialization") as String
    val collectionsImmutableVersion: String = project.property("version.kotlinxCollectionsImmutable") as String

    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(kotlinVersion)
            }
            if (requested.group == "org.jetbrains.kotlinx") {
                if (requested.name == "kotlinx-serialization-json" ||
                    requested.name == "kotlinx-serialization-core" ||
                    requested.name == "kotlinx-serialization-bom") {
                    useVersion(serializationVersion)
                }
                if (requested.name == "kotlinx-collections-immutable") {
                    useVersion(collectionsImmutableVersion)
                }
            }
        }
    }
}
