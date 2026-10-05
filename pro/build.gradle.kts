import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ---------------------------------------------------------------------------
// :pro — the source-available catalogue, licensed FSL-1.1-ALv2 (see LICENSE.pro)
//
// This module exists because of a licence boundary, not because of a feature.
// `ru.ok.tracer` (AppTracer) is a **proprietary** SDK: its POM declares
// "Tracer's License Agreement", it is © VK, and it is not OSI-approved. Shipping
// it inside the Apache-2.0 core would make the core's licence a claim the project
// cannot honour (ADR 2026-10-05-provenance-audit §3).
//
// So the SDK lives here, and the free core has no dependency on it at all. What
// makes that cheap is that the SDK was already confined to one class behind
// `CrashReportingPort`, with a JVM no-op on the other platform.
//
// ## Why it is not in `settings.gradle.kts` unconditionally
//
// The open-core rule this project operates under is that **the public repository
// builds and passes every gate without `pro/`**. An unconditional `include(":pro")`
// would make the free configuration unbuildable, and a free configuration that
// only exists on paper is not a free configuration. `-PwithPro=true` is the only
// thing that pulls it in.
//
// The cost of that choice is stated rather than hidden: the code below compiles
// only in the pro configuration, so CI must run both. That is what
// `scripts/check-pro-licence-boundary.py` exists to police — the two
// configurations are only meaningful if something checks both.
// ---------------------------------------------------------------------------

plugins {
    // `id()`, not `alias(...)`, and deliberately without a version. AGP arrives on the
    // buildscript classpath from `:androidApp`, which pins it through the catalog
    // (`libs.plugins.androidApplication`); asking for `com.android.library` *with* a
    // version then fails with "the plugin is already on the classpath with an unknown
    // version". One AGP version for the whole build is the correct outcome — the
    // alternative, two independently-versioned AGPs, is not something Gradle supports.
    id("com.android.library")
    // No Kotlin plugin at all. AGP 9 ships built-in Kotlin support and fails the build if
    // `org.jetbrains.kotlin.android` is applied as well — so adding it would have been
    // both redundant and an error. The Kotlin *stdlib* still comes from the catalog via
    // `:shared`, which is an `api` dependency below.
    alias(libs.plugins.detekt)
    // The vendor's own Gradle plugin, needed for its resValues/build-time
    // configuration. Applied here rather than in the app so that the free
    // configuration never resolves the plugin at all.
    alias(libs.plugins.tracer)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// Tokens, read through `providers.*` rather than System.getenv: a raw environment
// read is snapshotted by the configuration cache and silently goes stale. Supply
// them as Gradle properties in ~/.gradle/gradle.properties (outside this repo) or
// as the TRACER_APP_TOKEN / TRACER_PLUGIN_TOKEN environment variables.
//
// With no token the plugin is disabled, so a secretless checkout of the pro
// configuration still produces a working build.
val tracerAppToken = providers.gradleProperty("tracerAppToken")
    .orElse(providers.environmentVariable("TRACER_APP_TOKEN"))
val tracerPluginToken = providers.gradleProperty("tracerPluginToken")
    .orElse(providers.environmentVariable("TRACER_PLUGIN_TOKEN"))

tracer {
    create("defaultConfig") {
        appToken = tracerAppToken.getOrElse("")
        pluginToken = tracerPluginToken.getOrElse("")

        uploadMapping = true
        uploadRetryCount = 2
        // A network blip during CI must not fail the build.
        dontFailOnUploadFailure = true
        isDisabled = tracerAppToken.getOrElse("").isBlank()
    }
    create("debug") {
        isDisabled = tracerAppToken.getOrElse("").isBlank()
    }
}

android {
    namespace = "com.singularity.todo.pro"
    compileSdk = libs.versions.sdk.compile.get().toInt()

    defaultConfig {
        minSdk = libs.versions.sdk.min.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        // REQUIRED by AppTracer from AGP 9 onward — see the note in
        // androidApp/build.gradle.kts. Omitting this fails at RUNTIME.
        resValues = true
    }
}

dependencies {
    // `api`, not `implementation`: the app's pro build needs the Tracer types on
    // its own compile classpath, because `ProSingularityApp` implements
    // `HasTracerConfiguration`. This is the one place the pro catalogue is
    // deliberately transparent to its consumer.
    api(project(":shared"))
    // `api`, not `implementation`: `ProSingularityApp` lives in `:androidApp` and
    // implements the vendor's `HasTracerConfiguration`, so the Tracer types must be on the
    // app's own compile classpath. This is the one place the pro catalogue is
    // deliberately transparent to its consumer — and it is why the pro sources are
    // confined to `androidApp/src/pro/kotlin` instead of living in `src/main`.
    api(libs.tracer.crash.report)

    // Kermit, for `TracerCrashReportingPort`'s `Logger` parameter. `:shared` declares it
    // `implementation`, so it is not on a consumer's compile classpath. The project
    // convention is an explicit constructor parameter rather than a global logger, and the
    // class has to report *its own* failures — so the type is needed here, and only here.
    implementation(libs.kermit)

    // Koin, for `proObservabilityModule()`. Same reason: `:shared` declares koin-core
    // `implementation`. `implementation` rather than `api` is right here because the app
    // only calls the returned `Module`; it never names a Koin definition.
    implementation(libs.koin.core)

    // Test-only. The rebinding this module performs is the entire reason `pro/`
    // exists, and it was the one thing about it nothing checked: the free
    // graph is validated by `KoinGraphValidationTest` in `:shared`, but that
    // test cannot see a module that only exists under `-PwithPro=true`. A
    // binding that silently stopped overriding would leave the free
    // `FileCrashReportingPort` in place and nobody would see a crash report
    // go anywhere.
    testImplementation(libs.koin.test)
    testImplementation(libs.kermit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)

    // The custom rule sets, matching the other modules — otherwise no project rule
    // applies to :pro at all.
    detektPlugins(project(":detekt-rules"))
    detektPlugins(libs.detekt.formatting)
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    ignoreFailures = false
    // `src/test/kotlin` joined the list on 2026-10-05 with
    // `ProObservabilityModuleTest`. It has to be named here explicitly: detekt
    // reports nothing about a directory it was not given, so omitting it would
    // leave the only test in the pro catalogue unscanned and invisible.
    // `DetektSourceSetsAreAllScannedTest` is what caught the omission.
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

// The unit-test task. JUnit 5 because `kotlin.test` maps onto it and the rest
// of the project uses it; without `useJUnitPlatform()` Gradle would run the
// Jupiter engine's absence as "no tests found", which is a green build that
// verified nothing — the exact failure mode this project keeps paying for.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
