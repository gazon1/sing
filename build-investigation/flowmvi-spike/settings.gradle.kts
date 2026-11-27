// Disposable probe project for issue #3234 — see README.md in this directory.
//
// A standalone build on purpose, the same shape as build-investigation/kt-62686/probe:
// it is deleted by removing one directory, and its versions are a copy of
// gradle/libs.versions.toml rather than a second source of truth for the app.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "flowmvi-spike"

// The scenario comparison. JVM, because both variants are exercised by tests and the
// in-repo MVI is consumed as production JVM bytecode (see spike/build.gradle.kts).
include(":spike")

// The Android+JVM compile proof for FlowMVI core + compose at the app's toolchain.
include(":resolveProof")
