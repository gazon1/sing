import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.PathSensitivity
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
    // Koin Compiler Plugin 1.2 — validates classic DSL (single { ... }) at compile time.
    // No @Single/@Factory annotations needed; koin-annotations 4.x is incompatible (see AGENTS.md).
    // Applied via id() — version catalog accessor fails for hyphenated plugin IDs.
    id("io.insert-koin.compiler.plugin") version "1.2.1"
    alias(libs.plugins.detekt)
}

koinCompiler {
    // userLogs = true // uncomment to see detected definitions during development
}

/** kotlinx-coroutines-debug agent for jvmTest only. */
val coroutinesDebugAgent = configurations.create("coroutinesDebugAgent") {
    isCanBeConsumed = false
    isCanBeResolved = true
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

            // AppTracer — crash / ANR / non-fatal reporting. Android-only, so the
            // JVM source set has no equivalent. Repeated from
            // androidApp/build.gradle.kts on purpose: that module implements
            // HasTracerConfiguration, and this module's `implementation`
            // dependencies are not visible to it at compile time.
            // See docs/decisions/2026-10-04-apptracer-integration.md
            implementation(libs.tracer.crash.report)
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
            // kotlinx-coroutines-debug for coroutine dump on failure (FailureContextExtension).
            implementation(libs.kotlinx.coroutines.debug)
        }
    }
}

// JUnit Platform (Jupiter) — enables @Tag, @Nested, @ParameterizedTest, @TempDir, @AutoClose
tasks.withType<Test>().configureEach {
    // JUnit matches tags per class, so an over-narrow -Ptest.tags selection can discover
    // nothing — and the task would still report BUILD SUCCESSFUL. That is exactly how
    // `:desktopApp:test -Ptest.tags=fast,slow` ran zero tests for months. Discovering
    // nothing is a configuration error, not a pass.
    // Partial selection is the other half of the problem and is not detectable here;
    // `TestTagCoverageTest` (every test class carries a @Tag) is what keeps
    // `-Ptest.tags=fast,slow` from silently skipping the untagged majority.
    failOnNoDiscoveredTests = true

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
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        when {
            tags.isEmpty() -> {
                // Default: run everything EXCEPT @Tag("slow") — slow requires -Ptest.tags=slow
                excludeTags("slow")
            }
            // `-Ptest.tags=all` applies no tag filter at all. This is the only
            // setting that runs untagged tests, and JUnit's includeTags()
            // excludes them — so a CI step passing a tag list silently skips
            // every test that carries no @Tag. TestTagCoverageTest (arch) fails
            // the build for a class in a tag-filtered source set that has no
            // @Tag; the finding and the reasoning behind that gate's scope are
            // under "an-untagged-test-class-is-invisible-to-a-tag-filtered-run"
            // in `docs/decisions/deferred-backlog.md`.
            tags == listOf("all") -> Unit
            else -> includeTags(*tags.toTypedArray())
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
//
// It is also load-bearing for a second reason that is easy to lose: this task runs
// BackgroundFailureHandlerTest, and that handler is process-wide mutable state. A
// fresh JVM per class is what stops one class's installed target from receiving a
// failure raised in another class that happens to run at the same moment. Raising
// forkEvery for build speed would silently make that test order-dependent, so the
// value is published as a system property and asserted by ForkEveryIsolationTest
// rather than left as a comment here.
tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach {
    forkEvery = 1
    // Published so `ForkEveryIsolationTest` can assert the invariant instead of trusting a
    // comment here. It is load-bearing twice over: KoinPlatform state between classes, and —
    // as of the background-handler migration — the `FileSystemContract` temp paths, which
    // rely on one class per process for their uniqueness.
    systemProperty("jvmTest.forkEvery", forkEvery.toString())
    // Forks in parallel, still one JVM per class. `forkEvery = 1` is what isolates
    // KoinPlatform state between classes; it costs a JVM start per class, and at
    // 190 classes that was 7m58s of which the tests themselves were a fraction.
    // Parallel forks keep the isolation exactly — each fork is still its own
    // process, re-created per class — and only overlap the startup cost.
    //
    // Scaled to the machine, not fixed: CI runners have 2-4 cores, and four forks
    // there oversubscribe rather than speed anything up. Half the cores, capped at
    // four, because each fork reserves `maxHeapSize` and four of them is already
    // 12 GB reserved.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
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
    // The tests above read files OUTSIDE :shared while they run, so those trees are
    // inputs to this task whether or not they feed the compiler. Without this, editing
    // a desktopApp or mcp-server test leaves :shared:jvmTest UP-TO-DATE, and an
    // architecture gate re-reports the previous run's verdict about files that have
    // since changed.
    //
    // Measured 2026-10-05: TestTagCoverageTest flagged a desktopApp class as untagged.
    // The class was given a tag, the run still failed, and the class was correctly
    // reported again and again — because nothing had invalidated the task. Only
    // --rerun-tasks produced the new, passing verdict. A gate that can report a stale
    // result is worse than no gate, because the stale result looks like a pass.
    inputs.dir(layout.projectDirectory.dir("../desktopApp/src/jvmTest/kotlin"))
        .withPropertyName("desktopAppJvmTestSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("../mcp-server/src/test"))
        .withPropertyName("mcpServerTestSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // MaestroFlowTagsTest walks up from commonMain.root to the worktree root and reads
    // Maestro/. That tree is not a compile input of :shared at all, so the gate went
    // UP-TO-DATE on every flow edit and re-reported the previous run's verdict.
    //
    // Measured 2026-10-04, with a probe rather than by reasoning: inject a valid
    // Maestro command carrying an unknown `id:` into a flow, run the gate twice with the
    // same filter, and the second run printed `:shared:jvmTest UP-TO-DATE` /
    // BUILD SUCCESSFUL with the bad selector still in the tree. That is the whole failure
    // mode — a blocking gate that reports green about a file it never re-read.
    //
    // The earlier fix on this task covered desktopApp and mcp-server because those were
    // the trees a hit happened to involve. This is the same defect one level over, and
    // the reason the invariant is now enforced by check-test-task-inputs.py rather than
    // by whoever touches it next.
    inputs.dir(layout.projectDirectory.dir("../Maestro"))
        .withPropertyName("maestroFlows")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Passed in rather than derived. MaestroFlowTagsTest used to walk up four levels from
    // commonMain.root to find Maestro/, which produced a path no build file mentioned —
    // and an undeclared path is an undeclared path. Declaring it here also makes the
    // dependency visible to scripts/check-test-task-inputs.py, which fails when a
    // path-valued system property points outside its module with no input covering it.
    // A derived path is invisible to that gate by construction, which is why deriving it
    // was the defect rather than the style.
    systemProperty(
        "maestro.root",
        layout.projectDirectory.dir("../Maestro").asFile.absolutePath,
    )
    // SyncPeriodicTriggerWiringTest reads the androidMain and jvmMain platform
    // modules. Same staleness hazard as above: edit a platform module, leave
    // :shared:jvmTest UP-TO-DATE, and the gate re-reports a verdict about the
    // binding it was supposed to have caught missing.
    inputs.dir(layout.projectDirectory.dir("src/androidMain/kotlin"))
        .withPropertyName("androidMainSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/jvmMain/kotlin"))
        .withPropertyName("jvmMainSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Scan roots for ViewModelTestCoverageTest: it matches a production ViewModel
    // against the test classes that mention it, so it needs the commonTest and
    // jvmTest trees as well as commonMain. Absent properties make its top-level
    // vals throw, which surfaces as NoClassDefFoundError on the second test —
    // the first failure hides behind an initialiser error.
    systemProperty(
        "commonTest.root",
        layout.projectDirectory.dir("src/commonTest/kotlin").asFile.absolutePath,
    )
    systemProperty(
        "jvmTest.root",
        layout.projectDirectory.dir("src/jvmTest/kotlin").asFile.absolutePath,
    )
    // Per-platform source roots for SyncPeriodicTriggerWiringTest, which checks that
    // each platform module binds exactly one SyncPeriodicTrigger.
    //
    // Narrower than a single repo-root property on purpose. A property pointing at the
    // worktree root would satisfy check-test-task-inputs.py only by declaring the whole
    // repository as an input of :shared:jvmTest — which makes the task re-run on a
    // change to any file anywhere, and re-introduces at module scale the staleness that
    // gate exists to prevent. These two paths are inside :shared, so the trees they
    // name are declared as inputs above and nothing else is dragged in.
    systemProperty(
        "androidMain.root",
        layout.projectDirectory.dir("src/androidMain/kotlin").asFile.absolutePath,
    )
    systemProperty(
        "jvmMain.root",
        layout.projectDirectory.dir("src/jvmMain/kotlin").asFile.absolutePath,
    )
    // Enable TAGS.md golden regeneration:
    //   ./gradlew :shared:jvmTest -PupdateGoldens=true
    if (project.findProperty("updateGoldens")?.toString() == "true") {
        systemProperty("update.goldens", "true")
    }
    // Build directory path for FailureContextExtension coroutine dump output.
    systemProperty(
        "shared.build.dir",
        layout.buildDirectory.get().asFile.absolutePath,
    )
    // Global test timeout: makes CoroutinesTimeoutExtension fire on a hard-hang
    // (Extension catches the exception and writes coroutines-timeout.txt before the harness
    // marks the test as failed). 5 minutes is long enough for any real test; it exists
    // to catch infinite loops and deadlocks, not to bound normal execution.
    systemProperty("junit.jupiter.timeout.default", "300000")
    // -javaagent for kotlinx-coroutines-debug: required for JDK 21+ compatibility.
    // Resolved eagerly as a plain String (not via CommandLineArgumentProvider) to avoid
    // capturing the Gradle script object, which breaks the configuration cache.
    val coroutinesDebugAgentPath: String = configurations
        .named("coroutinesDebugAgent").get()
        .resolve()
        .single { it.name.contains("debug") && it.name.endsWith(".jar") }
        .absolutePath
    jvmArgs("-javaagent:$coroutinesDebugAgentPath")
}

dependencies {
    coroutinesDebugAgent(libs.kotlinx.coroutines.debug)
    androidRuntimeClasspath(libs.compose.ui.tooling)

    // Room 3 KSP compiler — per-target so AppDatabase_Impl is generated
    // for both Android and JVM. JVM builds the same Room DB via BundledSQLiteDriver.
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)

    // androidHostTest — the Android/Robolectric-capable source set. It currently holds
    // no test files of its own: its only content is AndroidManifest.xml, and the 762
    // tests it executes come from commonTest. The Robolectric / JUnit4 stack that used
    // to be declared here went away with the tests that needed it (ADR D2's
    // AndroidPomodoroTimerTest no longer exists), and the comment about "the Koin graph
    // test" referred to a test that is also gone.
    //
    // If you add a Robolectric test here, declare the stack again in this block:
    // `libs.robolectric`, `libs.androidx.test.core`, `libs.androidx.testExt.junit`,
    // `libs.compose.ui.test.junit4` (AndroidX, NOT the JetBrains multiplatform one —
    // AndroidX is Robolectric-compatible, JetBrains is not), and
    // `libs.junit.vintage.engine`, because Robolectric is a JUnit4 runner and the task
    // uses the JUnit Platform. Without the Vintage engine those classes are silently
    // skipped, and the Vintage engine does not map Jupiter's @Tag onto Platform tags —
    // see ADR 2026-10-04-test-execution-integrity.
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
// No `kover { }` block here. Coverage is configured once, at settings level
// (settings.gradle.kts): the plugin applies itself to every project, so the
// `com.singularity.todo.*` instrumentation filter arrives here as a convention
// and the report is produced once for the whole build. A per-project report
// would measure only this project's own test tasks — which is exactly the gap
// that made every Compose flow test in desktopApp invisible to the number.

