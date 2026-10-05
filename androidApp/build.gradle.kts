import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.tracer)
    // Applied via id() — version catalog accessor fails for hyphenated plugin IDs.
    id("io.insert-koin.compiler.plugin") version "1.2.1"
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// ---------------------------------------------------------------------------
// AppTracer (ru.ok.tracer)
//
// Tokens are read through `providers.*` rather than System.getenv, because a
// raw environment read is snapshotted by the configuration cache and silently
// goes stale — the same reason desktopApp/build.gradle.kts forwards its test
// switches via providers.systemProperty. Supply them either as Gradle
// properties in ~/.gradle/gradle.properties (outside this repo) or as the
// TRACER_APP_TOKEN / TRACER_PLUGIN_TOKEN environment variables.
//
// With no token, `isDisabled = true` keeps Tracer inert and the build green, so
// CI and any secretless checkout still produce an installable APK.
// ---------------------------------------------------------------------------
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

    // Configurations inherit defaultConfig; "debug" is spelled out only so the
    // intent is visible next to the token wiring above.
    create("debug") {
        isDisabled = tracerAppToken.getOrElse("").isBlank()
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

    // AppTracer
    // Repeated from shared/build.gradle.kts on purpose: this module implements
    // HasTracerConfiguration, and `implementation` deps of :shared are not
    // visible here at compile time.
    implementation(libs.tracer.crash.report)

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
    // src/debug is deliberately NOT linted yet: DebugSeedActivity.kt is one-shot debug
    // tooling where runBlocking and Clock.System are by design, and whether debug-only
    // code should be held to production rules is a decision, not a mechanical fix.
    // Recorded in deferred-backlog.md as `androidapp-debug-source-set-unlinted`.
    source.setFrom(
        "src/main/kotlin",
        "src/androidTest/kotlin",
    )
}
