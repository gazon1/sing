import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidMultiplatformLibrary)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
	alias(libs.plugins.kotlinxSerialization)
	// KSP for Room annotation processing
    alias(libs.plugins.ksp)
    // Room 3 KSP plugin (schema export)
    alias(libs.plugins.room3)
    // Koin Compiler Plugin (processes @Single, @Factory, @IntoSet annotations
    // across KMP source sets — replaces legacy `ksp("koin-annotations-compiler")`)
    alias(libs.plugins.koin)
	// Code quality
	alias(libs.plugins.detekt)
	alias(libs.plugins.kover)
}

kotlin {
    jvm()

    android {
        namespace = "com.singularity.todo.shared"
        compileSdk = libs.versions.sdk.compile.get().toInt()
        minSdk = libs.versions.sdk.min.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
            freeCompilerArgs.add("-Xskip-metadata-version-check")
            freeCompilerArgs.add("-Xbinary=allow-kotlin-metadata-version-mismatch=true")
            freeCompilerArgs.add("-Xopt-in=kotlin.time.ExperimentalTime")
            // Suppress warning: expect/actual classes are in Beta
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Compose
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)

            // Lifecycle
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            // lifecycle-viewmodel-navigation3: metadata (expect) in commonMain, actuals in android/jvm
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)

            // Coroutines
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)

            // Serialization
            implementation(libs.kotlinx.serialization.json)

            // Room 3 (KMP)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite)

			// Koin
			implementation(libs.koin.core)
			implementation(libs.koin.compose)
			implementation(libs.koin.compose.viewmodel)
			implementation(libs.koin.annotations.runtime)
			implementation(libs.koin.compose.navigation3)

			// Navigation 3 multiplatform runtime (NavKey, NavBackStack, NavEntry)
			// Platform-specific implementations (entryProvider, rememberNavBackStack) are in
			// the platform AARs: navigation3-runtime-android (androidMain).
			// koin-compose-navigation3 (multiplatform metadata) is in commonMain for koinEntryProvider.
			implementation(libs.androidx.navigation3.runtime)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.json)

            // DataStore
            implementation(libs.androidx.datastore.preferences.core)

            // Kermit
            implementation(libs.kermit)

            // Markdown
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)

            // Rich Text Editor
            implementation(libs.rich.editor.compose)

            implementation(libs.openai.client)
            implementation(libs.koog.agents)
            implementation(libs.koog.prompt.executor.openai.client)
            implementation(libs.koog.prompt.llm)
            implementation(libs.koog.prompt.executor.model)

            // MaterialKolor
            implementation(libs.materialkolor)

            // FileKit
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)

            // Coil
            implementation(libs.coil.compose)
            implementation(libs.coil.core)

            // Utils
            implementation(libs.ulid)

            // Supabase
            implementation(libs.auth.kt)
            implementation(libs.postgrest.kt)
            implementation(libs.functions.kt)
        }

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling)
            implementation(libs.compose.ui.tooling.preview)

            // Calendar — Android-only (no JVM/desktop variants)
            implementation("com.kizitonwose.calendar:compose:2.6.0")

            // Room Android
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)

            // Koin Android
            implementation(libs.koin.android)

            // Koin Navigation 3 DSL
            // koin-compose-navigation3 (multiplatform): provides koinEntryProvider in commonMain
            // Note: the navigation{} DSL is NOT accessible from commonMain or androidMain in this
            // Koin version due to classpath resolution issues with the multiplatform metadata JAR.
            // Instead, entries are registered via koinEntryProvider() called from AndroidShellNav3.
            implementation(libs.koin.compose.navigation3)
            // navigation3-runtime-android: rememberNavBackStack, NavBackStack, NavEntry, entryProvider
            // navigation3-ui-android: NavDisplay
            implementation(libs.androidx.navigation3.runtime.android)
            implementation(libs.androidx.navigation3.ui.android)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)

            // Ktor OkHttp
            implementation(libs.ktor.client.okhttp)

            // DataStore
            implementation(libs.androidx.datastore.preferences)

            // FileKit Android
            implementation(libs.filekit.core)

            // Security — EncryptedSharedPreferences
            implementation(libs.android.security.crypto)

            // WorkManager — background job scheduling for SyncEngine
            implementation(libs.androidx.work.runtime)

            // Koog OkHttp HTTP backend — needed by Android actual of createKoogPromptExecutor
            implementation(libs.koog.http.client.okhttp)
        }

        jvmMain.dependencies {
            // Desktop UI — LocalAwtWindow for AwtMenuBarInstaller.
            implementation(libs.compose.ui.desktop)

            // Bundled SQLite — same driver as Android, no external native dep required.
            implementation(libs.androidx.sqlite.bundled)

            // Room JVM
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite)

            // Kermit Koin integration (JVM-only)
            implementation(libs.kermit.koin)

            // Koin
            implementation(libs.koin.core)

            // Ktor CIO
            implementation(libs.ktor.client.cio)

            // DataStore (full artifact includes JVM factory)
            implementation(libs.androidx.datastore.preferences)

            // DateTime
            implementation(libs.kotlinx.datetime)

            // FileKit JVM
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)

            // Coil Ktor network
            implementation(libs.coil.network.ktor3)

            // Koog OkHttp HTTP backend — JVM-only
            implementation(libs.koog.http.client.okhttp)

            // Navigation 3 JVM (JetBrains navigation3-ui-desktop has proper NavDisplay implementation)
            implementation(libs.androidx.navigation3.runtime.desktop)
            implementation(libs.androidx.navigation3.ui.desktop)
            // lifecycle-viewmodel-navigation3: rememberViewModelStoreNavEntryDecorator for JVM
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
        }

        commonTest.dependencies {
            implementation(libs.jvm.test)
            implementation(libs.jvm.test.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.androidx.room3.testing)
            implementation(libs.koin.test)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.jvm.test)
            implementation(libs.jvm.test.junit)
            implementation(libs.koin.test)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.test.core)
            implementation(libs.robolectric)
            // Compose UI test infra — needed for createComposeRule and onNodeWithText.
            // Note: AndroidX version (1.7.3) is used instead of JetBrains (1.11.1) because
            // JetBrains version depends on Espresso which is incompatible with Robolectric.
            implementation(libs.compose.ui.test.junit4)
        }
    }
}

// Force jvmTest to fork a new JVM for each test class.
// This prevents KoinPlatform global state from leaking between tests that
// call startKoin()/stopKoin() vs koinApplication().
tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach {
    forkEvery = 1
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)

    // Room 3 KSP compiler — per-target so AppDatabase_Impl is generated
    // for both Android and JVM. JVM builds the same Room DB via BundledSQLiteDriver.
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
}

// Room 3 KSP schema export
room3 {
    schemaDirectory("$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// detekt — static analysis + ktlint (via detekt-formatting plugin)
// ---------------------------------------------------------------------------
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-shared.xml")
    buildUponDefaultConfig = true
    ignoreFailures = true               // report-only on day 1; tighten once baselines are clean
    source.setFrom(
        "src/commonMain/kotlin",
        "src/commonTest/kotlin",
        "src/jvmMain/kotlin",
        "src/jvmTest/kotlin",
        "src/androidMain/kotlin",
        "src/androidHostTest/kotlin"
    )
}

dependencies {
    detektPlugins(libs.detekt.formatting)   // wires ktlint into detekt so detektFormat fixes both
    detektPlugins(project(":detekt-rules"))  // PassThroughUseCaseRule — flags thin wrappers in *UseCase.kt
}

// ---------------------------------------------------------------------------
// kover — code coverage for all KMP source sets
// ---------------------------------------------------------------------------
kover {
    reports {
        total {
            html { onCheck = true }
            xml { onCheck = true }
        }
    }
}

// Robolectric JDK 21+ fix — open FileDescriptor reflection internals
afterEvaluate {
    project.tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
        jvmArgs(
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        )
    }
}
