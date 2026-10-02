@Suppress("UnstableApiUsage")
rootProject.name = "Singularity_cllone_kmp"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    // Map plugin id "koin" → module "io.insert-koin:koin-gradle-plugin".
    // Koin 4.x publishes the compiler as a plain Gradle plugin JAR without a
    // plugin-marker artifact, so the plugins DSL alone can't resolve it.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "koin") {
                useModule("io.insert-koin:koin-gradle-plugin:${requested.version}")
            }
            // The compiler plugin (io.insert-koin.compiler.plugin) also lacks a
            // plugin-marker artifact in some releases; resolve it explicitly.
            if (requested.id.id == "io.insert-koin.compiler.plugin") {
                useModule("io.insert-koin:koin-compiler-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JetBrains Koog AI agent framework
        maven { url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")
include(":mcp-server")
include(":detekt-rules")
