// A separate build on purpose: the cost model must not be able to perturb the repository's build.
// Versions come from properties so run-cost-model.sh reads them from gradle/libs.versions.toml
// instead of a number copied into a document that then rots.
pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").getOrElse("2.4.10")
    val composeVersion = providers.gradleProperty("composeVersion").getOrElse("1.12.2")
    val koinPluginVersion = providers.gradleProperty("koinPluginVersion").getOrElse("1.2.1")
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("org.jetbrains.compose") version composeVersion
        id("io.insert-koin.compiler.plugin") version koinPluginVersion
    }
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform resolves its runtime through AndroidX, which publishes to Google's
        // repository — the repository build has it too, so leaving it out would measure a build that
        // cannot be the same build.
        google()
    }
}

rootProject.name = "split-plan-cost-model"

include(":module")
