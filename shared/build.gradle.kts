import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.PathSensitivity

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
    // Koin Compiler Plugin 1.2 — validates classic DSL (single { ... }) at compile time.
    // No @Single/@Factory annotations needed; koin-annotations 4.x is incompatible (see AGENTS.md).
    // Applied via id() — version catalog accessor fails for hyphenated plugin IDs.
    id("io.insert-koin.compiler.plugin") version "1.2.1"
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

koinCompiler {
    // userLogs = true // uncomment to see detected definitions during development
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
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        // Enables Robolectric tests in src/androidHostTest/ (runs on JVM, no emulator).
        // See docs/decisions/2026-09-28-androidApp-smoke-tests-enabled.md
        withHostTest {}
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

            // Okio — file I/O for FileLogWriter
            implementation(libs.okio)

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
            implementation(libs.kotlin.test.junit5)
            implementation(libs.junit.jupiter)
            implementation(libs.junit.jupiter.params)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            // Kotest assertions — matchers only (shouldBe, shouldNotThrowAny, shouldContain).
            // Does NOT replace kotlin.test.Test — use alongside it in any test file.
            implementation(libs.kotest.assertions.core)
        }

        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.androidx.room3.testing)
            // Architecture boundary tests (ArchitectureTest) — structural assertions
            // over commonMain sources, enforced as part of the regular test run.
            implementation(libs.konsist)
        }
    }
}

// JUnit Platform (Jupiter) — enables @Tag, @Nested, @ParameterizedTest, @TempDir, @AutoClose
tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        // Jupiter parallel execution — classes run concurrently, methods within a class
        // also run concurrently by default (ExecutionMode.CONCURRENT).
        // Class-level parallelism is safe because:
        //   - forkEvery=1 isolates Koin global state between classes
        //   - MutableStateFlow in fakes handles concurrent StateFlow reads/writes
        //   - Room databases are opened per-class via @BeforeEach (see FakeDatabaseFactory)
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")

        val tags = (project.findProperty("test.tags") as String?)
            ?.split(",")?.orEmpty() ?: emptyList()
        if (tags.isNotEmpty()) {
            includeTags(*tags.toTypedArray())
        } else {
            // Default: run everything EXCEPT @Tag("slow") — slow requires -Ptest.tags=slow
            excludeTags("slow")
        }
    }
    // Bumped from default ~512 MB to 3 GB. Forked test JVMs do NOT inherit
    // org.gradle.jvmargs (that's the daemon only). HeapDumpPath is module-local so
    // parallel test runs don't overwrite each other's dumps.
    maxHeapSize = "3g"
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/test-heap-dumps",
    )
}

// Force jvmTest to fork a new JVM for each test class.
// This prevents KoinPlatform global state from leaking between tests that
// call startKoin()/stopKoin() vs koinApplication().
tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach {
    forkEvery = 1
    // Apply heap directly to the jvmTest fork — the global configureEach above
    // also sets this, but being explicit avoids ordering ambiguity.
    maxHeapSize = "3g"
    // Absolute path to commonMain sources for ArchitectureTest (Konsist scope).
    // Passed as a system property instead of relying on the test JVM working dir,
    // which is not guaranteed to be the project directory.
    systemProperty(
        "commonMain.root",
        layout.projectDirectory.dir("src/commonMain/kotlin").asFile.absolutePath,
    )
    // Absolute path to desktopApp jvmTest sources for DesktopTestHarnessEnforcementTest.
    systemProperty(
        "desktopAppJvmTest.root",
        layout.projectDirectory.dir("../desktopApp/src/jvmTest/kotlin").asFile.absolutePath,
    )
    // Test source roots for ViewModelTestCoverageTest (VM ⇒ test rule).
    systemProperty(
        "commonTest.root",
        layout.projectDirectory.dir("src/commonTest/kotlin").asFile.absolutePath,
    )
    systemProperty(
        "jvmTest.root",
        layout.projectDirectory.dir("src/jvmTest/kotlin").asFile.absolutePath,
    )
    // MaestroFlowTagsTest reads the flow files off disk, which makes them an
    // input to this task whether Gradle knows it or not. Declaring them keeps
    // the task from being UP-TO-DATE after a flow-only edit — otherwise editing
    // a journey locally leaves the contract test silently unrun, and CI (a fresh
    // checkout) is the only place it would ever execute. A gate that can skip
    // itself without saying so is the same failure mode as a gate that tests
    // the wrong binary.
    inputs.dir(rootProject.layout.projectDirectory.dir("Maestro"))
        .withPropertyName("maestroFlows")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Restrict this run to a test subset via -Pcoverage.tests="…", so the same
    // configuration applies when koverXmlReport pulls jvmTest in as a dependency.
    // A `--tests` flag on the command line cannot be used for that: Gradle
    // rejects it for a non-Test task in the same invocation, and without the
    // filter the report task re-runs the *whole* suite under instrumentation.
    (project.findProperty("coverage.tests") as String?)?.let { pattern ->
        filter { includeTestsMatching(pattern) }
    }
    // Enable TAGS.md golden regeneration:
    //   ./gradlew :shared:jvmTest -PupdateGoldens=true
    if (project.findProperty("updateGoldens")?.toString() == "true") {
        systemProperty("update.goldens", "true")
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)

    // Room 3 KSP compiler — per-target so AppDatabase_Impl is generated
    // for both Android and JVM. JVM builds the same Room DB via BundledSQLiteDriver.
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)

    // androidHostTest (Robolectric) — JVM-based Android emulator for widget/Compose UI tests.
    // AndroidX compose-ui-test-junit4 (1.7.3) is used here, NOT the JetBrains
    // compose-multiplatform one: AndroidX is compatible with Robolectric, JetBrains is not.
    add("androidHostTestImplementation", libs.robolectric)
    add("androidHostTestImplementation", libs.compose.ui.test.junit4)
    // ApplicationProvider + the instrumentation registry the Koin graph test needs.
    add("androidHostTestImplementation", libs.androidx.test.core)
    add("androidHostTestImplementation", libs.androidx.testExt.junit)
    // The test task uses the JUnit Platform (useJUnitPlatform), and Robolectric is a
    // JUnit4 runner — without the vintage engine the platform silently skips every
    // JUnit4 test class in this source set.
    add("androidHostTestImplementation", libs.junit.vintage.engine)
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
    ignoreFailures = false              // PR 3.3: enforcing — baseline covers accepted debt
    source.setFrom(
        "src/commonMain/kotlin",
        "src/commonTest/kotlin",
        "src/jvmMain/kotlin",
        "src/jvmTest/kotlin",
        "src/androidMain/kotlin"
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
    currentProject {
        instrumentation {
            // Kover instruments every class loaded by the test JVM. For
            // `:shared:jvmTest`, the heavy Koog/classpath causes the IntelliJ
            // coverage runtime to accumulate 3000+ ClassData + 59000+ LineData
            // entries (42% of heap) — exhausting 3-5 GB and OOMing in
            // TaskOutgoingLinksTest. See ADR-1 for heap-dump analysis.
            //
            // That OOM was later shown to be misattributed: it reproduces with
            // Kover off and in complete isolation, so the tests were switched
            // off rather than the cause fixed (2026-09-27-write-layer-soundness.md,
            // ledger #11). The disable is therefore kept as the default — it
            // must not be lifted for the whole suite on a maybe — but it is now
            // opt-in so the coverage ratchet can measure a *filtered* agenda
            // run, which loads far fewer classes than the full jvmTest classpath.
            //
            //   ./gradlew :shared:jvmTest koverXmlReport -Pkover.jvmTest=true \
            //       --tests "com.singularity.todo.feature.agenda.*"
            //
            // Without the flag the report is generated but every counter is 0,
            // because the agenda tests live in jvmTest and nothing instrumented
            // them. A ratchet on that number would ratchet on nothing.
            if (project.findProperty("kover.jvmTest")?.toString() != "true") {
                disabledForTestTasks.add("jvmTest")
            }
        }
    }
    reports {
        total {
            html { onCheck = true }
            xml { onCheck = true }
        }
    }
}

