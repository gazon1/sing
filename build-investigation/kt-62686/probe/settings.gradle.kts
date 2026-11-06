pluginManagement {
    // One property moves both modules — the kotlin("jvm") control is only a control if it moves with
    // the KMP one. The default is the version the ADR measured, so the committed reproducer is unchanged.
    val kotlinVersion = providers.gradleProperty("kotlinVersion").getOrElse("2.4.10")
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
    }
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kt62686-probe"

include(":probe")
include(":probeJvm")
