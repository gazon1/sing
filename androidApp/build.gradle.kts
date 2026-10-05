import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    // NOTE: `alias(libs.plugins.tracer)` used to be here. The vendor plugin and SDK
    // are in :pro (FSL-1.1-ALv2) — see pro/build.gradle.kts. Applying it here would
    // put proprietary code in an Apache-2.0 module. ADR 2026-10-05-provenance-audit §3.
    // Applied via id() — version catalog accessor fails for hyphenated plugin IDs.
    id("io.insert-koin.compiler.plugin") version "1.2.1"
}

// Whether to build the source-available `pro` catalogue in. Default false, so a fresh
// clone is Apache-2.0-only and needs no proprietary artefact to resolve. Read through
// `providers.*` rather than System.getenv for the same configuration-cache reason the
// Tracer tokens used to be: a raw environment read is snapshotted at configuration time
// and silently goes stale.
val withPro: Boolean = providers.gradleProperty("withPro")
    .orElse("false")
    .map { it.toBoolean() }
    .get()

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":shared"))

    // The custom rule sets (via :detekt-rules plugin / ServiceLoader). Without this, no
    // project rule applies to androidApp at all.
    detektPlugins(project(":detekt-rules"))
    // ktlint via detekt-formatting — required because the shared config declares a
    //  block, and detekt rejects unknown top-level sections.
    detektPlugins(libs.detekt.formatting)

    // AndroidX
    implementation(libs.androidx.activity.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Koin
    implementation(libs.koin.android)

    // DateTime — required because shared uses kotlinx.datetime.LocalDate in Task models
    implementation(libs.kotlinx.datetime)

    // Play In-App Updates
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)

    // Compose
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // The source-available catalogue. `-PwithPro=true` is what pulls it in, and it is
    // absent by default: the free configuration must build and pass every gate with no
    // proprietary code on the classpath at all. That is the property the open-core model
    // rests on, and it is only true if the default really is free.
    if (withPro) {
        implementation(project(":pro"))
    }

    // Testing — Android Instrumentation (adb device)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------
// Build provenance in versionName
//
// A green Maestro run is only evidence about the binary that ran. After an
// emulator is restored from a snapshot, `run-maestro.sh` reinstalls nothing, so
// the device holds whatever APK the snapshot held — and the flows report a
// result for a build that no longer exists. Size and mtime are not a substitute
// for identity: both are satisfied by a rebuild that changed only the sources
// this run is supposed to be testing.
//
// So a debug build carries its git sha in versionName, and run-maestro.sh reads
// that sha back with `dumpsys package` and refuses to run when it disagrees with
// the checkout. Release builds keep the plain version: a user-facing string is
// not the place for a build identifier, and a release sha is not evidence
// anyway — nobody runs flows against a release build.
//
// Read through providers.exec rather than a bare `git` call: the configuration
// cache snapshots the filesystem, so a value read by running git at
// configuration time goes stale exactly when the commit changes. An absent git
// (a source tarball, a CI export) yields "unknown" and the check degrades to
// comparing "unknown" with "unknown" instead of failing every build.
// ---------------------------------------------------------------------------
val gitShaProvider: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "--short=12", "HEAD")
}.standardOutput.asText.map { it.trim() }.orElse("unknown")

android {
    namespace = "com.singularity.todo"
    compileSdk = libs.versions.sdk.compile.get().toInt()

    defaultConfig {
        applicationId = "com.singularity.todo"
        minSdk = libs.versions.sdk.min.get().toInt()
        targetSdk = libs.versions.sdk.target.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // The Application class differs between the two configurations, and the
        // difference is not cosmetic: `ProSingularityApp` implements the vendor's
        // `HasTracerConfiguration`, so naming it in the free build would put a
        // proprietary type in an Apache-2.0 module. A manifest placeholder keeps the
        // choice in one place instead of editing the manifest per configuration.
        manifestPlaceholders["appClass"] =
            if (withPro) "com.singularity.todo.pro.ProSingularityApp"
            else "com.singularity.todo.SingularityApp"

        // Instrumentation test runner
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        debug {
            // `0.1.0+g<sha>` — the sha is what `dumpsys package versionName`
            // gives back, and what run-maestro.sh compares against the checkout
            // before it runs a single flow. Gradle accepts `+` in versionName;
            // the character survives into the manifest unchanged.
            versionNameSuffix = "+g${gitShaProvider.get()}"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    sourceSets {
        if (withPro) {
            // Compiled only in the pro configuration. `getByName("main").kotlin.srcDir`
            // rather than a named source set: the files belong to the main variant (they
            // are part of the shipped app), they are simply not part of the free build.
            // `directories`, not `srcDir(...)` / `srcDirs(...)`: both are deprecated in AGP 9.
            // Adding to the set rather than replacing it keeps the default
            // `src/main/kotlin` in place — `setSrcDirs` would have dropped it.
            getByName("main").kotlin.directories.add("src/pro/kotlin")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // REQUIRED by AppTracer from AGP 9 onward: the SDK embeds resources at
        // build time via resValues, and AGP 9 disabled the feature by default.
        // Omitting this fails at RUNTIME, not at build time. This is the first
        // resValues use in the repo, so it sets the pattern for future SDKs.
        resValues = true
    }
}

// ---------------------------------------------------------------------------
// detekt — static analysis
// ---------------------------------------------------------------------------
// detekt — static analysis
//
// 2026-10-05: this module was on detekt-minimal.yml with ignoreFailures = true, which
// meant it was outside the governance net in two ways at once: no custom rule applied to
// it, and nothing it did report could fail a build. It now uses the same config as
// :shared and :detekt-rules, with the custom rule sets on the classpath.
//
// The module is small (8 files in src/main) and came to 0 findings after auto-correct and
// two real fixes, so there is no baseline to carry — `baseline-androidApp.xml` does not
// exist and should not be created unless a future change needs one.
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-androidApp.xml")
        .takeIf { it.isFile }
    buildUponDefaultConfig = true
    ignoreFailures = false   // enforcing — a baseline covers accepted debt when there is one
    // `src/debug` is scanned. It was not, and that was the defect: a source set detekt
    // is not told about is a source set where a real defect can hide indefinitely, and
    // it is invisible from the report — detekt scans what it is told to scan and says
    // nothing about what it was not told to scan.
    //
    // The six findings that remain are carried in the baseline with a reason each, not
    // suppressed by source set. A seeder that blocks a thread, stamps real time and
    // swallows failures is doing its job, and the rules forbidding those are right for
    // production code; but baselining them as one blanket exemption would make them
    // indistinguishable from the nine formatting defects that were genuinely fixed. The
    // split was measured before anything changed: 15 findings, 9 fixed, 6 baselined.
    //
    // Policy and the alternative considered: openspec/changes/androidapp-debug-lint-policy,
    // docs/decisions/2026-10-05-debug-source-set-is-linted.md, issue #99.
    // `DetektSourceSetsAreAllScannedTest` fails if a source set is added to this module
    // and not here, which is the class of defect this line used to be.
    // A flat literal list on purpose, with `src/pro/kotlin` always in it.
    //
    // `DetektSourceSetsAreAllScannedTest` reads this list with a regex and fails if a
    // source set that exists on disk is missing from it. A conditional spread —
    // `*(if (withPro) arrayOf("src/pro/kotlin") else emptyArray())` — compiles and looks
    // clever, but the path is then invisible to that test, which is precisely the omission
    // the test exists to catch.
    //
    // Linting the pro sources in *both* configurations is also just correct: the directory
    // is on disk either way, and a file nobody lints is a file where a defect accumulates
    // without a signal. Only *compilation* is gated on `withPro`, not linting.
    source.setFrom(
        "src/main/kotlin",
        "src/androidTest/kotlin",
        "src/debug/kotlin",
        "src/pro/kotlin",
    )
}
