import java.util.Properties
import java.time.Duration as JavaDuration
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
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
    // Koin Compiler Plugin — validates classic DSL (single { ... }) at compile time.
    // No @Single/@Factory annotations needed; koin-annotations 4.x is incompatible (see AGENTS.md).
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.detekt)
}

koinCompiler {
    // userLogs = true // uncomment to see detected definitions during development
}

// The stability contract — the list of types the Compose compiler treats as
// stable. It is what makes a list row skip a recomposition when its task did
// not change: an unstable parameter is compared by identity, and the Room
// mapper builds a new instance on every read.
//
// The file is a single source of truth shared by every module that compiles
// composables; `scripts/check-compose-stability-config.py` checks that the
// names in it exist and that it is wired into each of them.
//
// `stabilityConfigurationFiles` (plural) rather than `stabilityConfigurationFile`
// (singular): the singular form is deprecated and fails the build with an
// error in this compiler version.
val composeStabilityConfiguration: RegularFile =
    rootProject.layout.projectDirectory.file("config/compose/stability_config.conf")

composeCompiler {
    stabilityConfigurationFiles.addAll(composeStabilityConfiguration)
}

// The contract is a compiler input, but the Compose compiler plugin passes it
// as a plugin option — and plugin options are not part of the compile task's
// up-to-date inputs. Without this declaration, editing the contract leaves the
// compilation UP-TO-DATE: the new classification never reaches the bytecode,
// and a contract change looks like it took effect when nothing changed.
//
// The same trap made the stability report unreliable, which is why the report
// block below forces the compilation to re-run instead of relying on inputs.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    inputs.file(composeStabilityConfiguration)
        .withPropertyName("composeStabilityConfiguration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/** kotlinx-coroutines-debug agent for jvmTest only. */
val coroutinesDebugAgent = configurations.create("coroutinesDebugAgent") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// ---------------------------------------------------------------------------
// Compose stability report — an audit instrument, not a build gate
// ---------------------------------------------------------------------------
//
// The Compose compiler can write a report naming every composable parameter it
// considers unstable. It is the authoritative answer to "which parameter costs
// a recomposition", and grep cannot answer it: grep does not tell List from
// ImmutableList, does not see parameters that arrive inside lambdas, and knows
// nothing about slots.
//
// It is opt-in (`-PcomposeStabilityReport=true`) because the report is a build
// artifact that no gate may depend on: it exists only when the compilation
// actually ran, the jvm and android compilations write the same file names, and
// the directory is git-ignored. Nothing in CI reads it.
//
// What protects the contract the report is used to design lives in the sources:
// `scripts/check-compose-stability-config.py` (the stability contract names
// types that exist and are wired) and the two detekt rules that forbid raw
// collection parameters and mutable casts.
//
// One directory per compilation: jvm and android compile the same sources, so
// a shared destination would have one report overwrite the other.
val composeStabilityReportEnabled: Boolean =
    providers.gradleProperty("composeStabilityReport").orNull?.toBoolean() ?: false

val composeStabilityReportsRoot: Provider<Directory> =
    layout.buildDirectory.dir("compose_compiler_reports")

// A report that was not regenerated is worse than no report: it is read as a
// measurement of the current code and is not one. The compiler's plugin options
// are not part of the compile task's up-to-date inputs, so a changed stability
// contract alone does not make the task re-run — the task would be UP-TO-DATE
// and the report on disk would still describe the previous contract.
//
// So when the report is asked for, the compilation is not allowed to be
// up-to-date. The cost is one full compile, and it is paid only by whoever
// asked for the measurement.
if (composeStabilityReportEnabled) {
    tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
        outputs.upToDateWhen { false }
    }
}

// The compiler reads the destination from a plugin option, so the flag is
// `-P plugin:<plugin-id>:<option>=<value>` — the id is the one the compiler
// registers its command line processor under.
fun composeStabilityReportArgs(target: String): List<String> = listOf(
    "-P",
    "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
        composeStabilityReportsRoot.get().asFile.absolutePath + "/" + target,
)

kotlin {
    // The one JDK every target is compiled with and every consumer runs on —
    // the same 21 gradle-daemon-jvm.properties pins the daemon to. Makes the
    // choice explicit instead of inherited from whichever JDK launched Gradle
    // (the launcher on this host is 25); android's explicit `jvmTarget = 11`
    // below keeps its bytecode level untouched (#413).
    jvmToolchain(21)

    jvm {
        compilerOptions {
            if (composeStabilityReportEnabled) {
                freeCompilerArgs.addAll(composeStabilityReportArgs("jvm"))
            }
        }
    }

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
            if (composeStabilityReportEnabled) {
                freeCompilerArgs.addAll(composeStabilityReportArgs("android"))
            }
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

    // The device-test source set cannot resolve its own classpath.
    //
    // `commonTest` declares `kotlin-test-junit5` explicitly (the common tests are
    // JUnit 5), and the umbrella `kotlin-test` is added by the Kotlin Gradle Plugin
    // for variant inference. `androidDeviceTest` inherits `commonTestImplementation`,
    // so its compile classpath carries both — and the umbrella resolves to the
    // **JUnit 4** variant there, because the device tests run on the
    // AndroidJUnitRunner. Both `kotlin-test-junit` and `kotlin-test-junit5` provide
    // the `kotlin-test-framework-impl` capability, so two providers of one capability
    // land in one configuration and the resolution fails outright:
    //
    //     Cannot select module with conflict on capability
    //     'org.jetbrains.kotlin:kotlin-test-framework-impl'
    //
    // (the version is the one the catalog pins; it is not spelled out here because
    //  a coordinate with a version in it is a hardcoded literal to the
    //  version-catalog gate, and this comment would trip it)
    //
    // The failure is latent: it needs a task that actually resolves the classpath,
    // and the device-test source set is empty (`shared/src/androidDeviceTest` holds
    // no Kotlin), so nothing in the normal build touched it. `:shared:kspAndroidDeviceTest`
    // does — and it fails, with or without any other plugin in the build.
    //
    // Excluding the JUnit 5 artifact from the device-test configurations leaves a
    // single provider, and it is the one the device tests actually run under.
    // The source set is empty today; the fix is here so that the first device test
    // added to it does not arrive with an unresolvable classpath.
    //
    // Scoped by configuration *name*, deliberately: `configurations.configureEach`
    // inside a source-set block resolves to the project's configurations, not the
    // source set's, and excluding junit5 from all of them takes `kotlin.test.Test`
    // out of commonTest as well — which fails every test file with
    // "Unresolved reference 'Test'".
    configurations.matching { it.name.startsWith("androidDeviceTest") }.configureEach {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit5")
    }

    sourceSets {
        commonMain.dependencies {
            // ADR rules live in `:adr-core` so that `:adr-cli` can use them without
            // depending on this module. `api` because the Koog adapters in
            // `feature/ai/tools/AdrTools.kt` hand `AdrStorage.AdrFile` back to callers.
            api(project(":adr-core"))

            // Compose
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive.navigation.suite)
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

            // Persistent collections — the Compose compiler treats ImmutableList /
            // ImmutableSet / ImmutableMap as known-stable, so a leaf or a section
            // declared with them is compared by equals instead of by identity.
            // That is what lets a list row skip a recomposition. See
            // docs/architecture/compose-stability.md.
            //
            // `api`, not `implementation`: the types are in this module's public
            // API now — a state class holds an `ImmutableList`, a composable takes
            // an `ImmutableMap` — so a consumer that calls those APIs has to be
            // able to name the types.
            api(libs.kotlinx.collections.immutable)

            // Serialization
            implementation(libs.kotlinx.serialization.json)

            // Room 3 (KMP)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite)

			// Koin
			implementation(libs.koin.core)
			implementation(libs.koin.compose)
			implementation(libs.koin.compose.viewmodel)

			// Navigation 3 multiplatform runtime (NavKey, NavBackStack, NavEntry)
			// Platform-specific implementations (entryProvider, rememberNavBackStack) are in
			// the platform AARs: navigation3-runtime-android (androidMain).
			implementation(libs.androidx.navigation3.runtime)
			// Navigation 3 UI (NavDisplay) — the JetBrains KMP root, so `NavDisplay` is
			// available in commonMain: on Android it redirects to the androidx artifact,
			// on Desktop it carries the Compose Multiplatform port. The androidx line
			// publishes no desktop variant, so this is the only way to put a nested
			// graph's NavDisplay into common code.
			implementation(libs.androidx.navigation3.ui)

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

            // Supabase
            implementation(libs.auth.kt)
            implementation(libs.postgrest.kt)
            implementation(libs.functions.kt)
            // The remote half of the backup feature: the archive goes into a
            // Supabase Storage bucket. Without it the "push to cloud" button can
            // only pretend.
            implementation(libs.storage.kt)

            // The Google account sign-in. The library is used for the credential
            // chooser and the platform's failure modes; the token exchange is this
            // project's, because the library has no refresh token.
            implementation(libs.kmpauth.google)

            // The Supabase credential model. The library's backend supplies the
            // sign-up, sign-in and sign-out calls; the operations it cannot
            // express (a magic link that must not create an account, the
            // magic-link exchange, and a refresh that must be able to say
            // "rejected") stay as direct calls into the SDK.
            //
            // The library is published against auth-kt 3.7.0 while this project
            // pins 3.8.0; Gradle resolves the higher one, so the binding between
            // the two is checked by the seam's own tests.
            implementation(libs.kmpauth.supabase)
        }

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling)
            implementation(libs.compose.ui.tooling.preview)

            // Google's authorization API — the offline authorization request that
            // produces the one-time code the server redeems.
            //
            // Declared here, not taken from KMPAuth: KMPAuth declares Play Services as
            // an `implementation` dependency, so it reaches the *runtime* classpath and
            // not the *compile* one. The classes are then missing exactly where they
            // are needed — in this project's own code, which asks Google for the grant.
            implementation(libs.play.services.auth)

            // Room Android
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)

            // Koin Android
            implementation(libs.koin.android)

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

            // NO AppTracer here. The SDK is proprietary (© VK, "Tracer's License
            // Agreement", not OSI-approved) and this module is about to be published
            // under Apache-2.0. It lives in the source-available `pro` catalogue
            // instead — see pro/build.gradle.kts and ADR
            // 2026-10-05-provenance-audit §3. The free Android binding is
            // FileCrashReportingPort, which writes to the rolling Kermit log.
        }

        jvmMain.dependencies {
            // Desktop UI — LocalAwtWindow for AwtMenuBarInstaller.
            implementation(libs.compose.ui.desktop)

            // Agent Client Protocol — the client half, used by the external-agent
            // screen. JVM-only on purpose: the SDK needs JDK 21, and this project
            // keeps the Android target at JVM_11 bytecode. Nothing in commonMain or
            // androidMain may depend on it — the mobile build must not acquire this.
            implementation(libs.acp)
            implementation(libs.acp.model)

            // OS keyring access (replaces the secret-tool CLI shell-out).
            implementation(libs.java.keyring)

            // The MCP tool server. It lives here rather than in the `:mcp-server`
            // module because the desktop application hands an external agent its tool
            // access by running *this* server in its own `--mcp-server` mode: the agent
            // must reach the same tools the application documents, and two copies of
            // the server would be two toolsets that drift apart.
            //
            // JVM-only, like the protocol library above: the MCP Kotlin SDK has no
            // Android artifact, and the mobile build must not acquire it.
            implementation(libs.kotlin.sdk)
            implementation(libs.ktor.io.jvm)
            implementation(libs.kotlinx.io.core.jvm)

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
            // `verify()` walks every definition and reports the unresolvable ones. The graph test
            // otherwise asserts a hand-picked list, which cannot notice a definition that is
            // present, correct-looking, and never satisfiable.
            implementation(libs.koin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            // Kotest assertions — matchers only (shouldBe, shouldNotThrowAny, shouldContain).
            // Does NOT replace kotlin.test.Test — use alongside it in any test file.
            implementation(libs.kotest.assertions.core)
            // Allure test reporting — generates JSON results for CI integration
            implementation(libs.allure.kotlin.junit5)
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

// ── The memory budget ───────────────────────────────────────────────────────
//
// The heap of a test fork is a MEASUREMENT, and the measurement lives in
// `config/docs/gradle-memory-budget.properties` — the record, one file, read by
// the build. The tables and the failed measurements behind it are in
// `docs/decisions/2026-10-22-gradle-memory-budget.md`.
//
// This module reads its OWN line of the record, so the number its test JVMs
// run with is the number the record holds for this module. `scripts/check-memory-budget.py`
// is what keeps the two in step.
val memoryBudget = Properties().apply {
    rootProject.file("config/docs/gradle-memory-budget.properties").inputStream().use { load(it) }
}
val testTaskHeap = memoryBudget.getProperty("test.heap.module.shared")
    ?: throw GradleException(
        "config/docs/gradle-memory-budget.properties declares no test.heap.module.shared — " +
            "the heap of this module's test tasks has to be recorded somewhere."
    )

// ── The architecture suite has a task of its own ─────────────────────────────
//
// The gates in `com.singularity.todo.arch` read the WHOLE tree. One of them —
// `CommonMainKonsistScope` — parses 1,238 files of `commonMain` with Konsist,
// and that scope is built once per JVM. Four forks built it four times: a
// quarter of the suite's CPU spent parsing the same tree four times over.
//
// So the arch suite runs in its own task with ONE fork. The scope is built
// once, and the classes that do not read the tree keep their four forks.
//
// The split is by PACKAGE and not by moving files: both tasks run the same
// compiled test classes and partition them with a filter, so an `internal`
// declaration is the same object in both and the shared fixtures stay shared.
//
// The arch gates' inputs are declared on THIS task and not on `jvmTest` — see
// the `archTask` flag in the block below. A gate that cannot be invalidated by
// the change it watches reports a verdict about a file it never re-read, and
// that is worse than no gate at all.
val archTest = tasks.register<Test>("archTest") {
    description = "The architecture gates — the ones that read the whole tree."
    group = "verification"
    val jvmTestSources = sourceSets.getByName("jvmTest")
    testClassesDirs = jvmTestSources.output.classesDirs
    classpath = jvmTestSources.runtimeClasspath
    filter { includeTestsMatching("com.singularity.todo.arch.*") }
    // The fork count is set in the `jvmTest`/`archTest` block below, where both
    // tasks are configured together: one fork for this one, four for the rest.
    // The merge is the same — a retried class must not be counted twice by the
    // run floor.
    reports.junitXml.mergeReruns.set(true)
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
            // `testAndroidHostTest` is exempt from an explicit tag list, and NOT by
            // accident. Robolectric is a JUnit4 runner, so its classes execute under the
            // Vintage engine — and the Vintage engine does not map Jupiter's `@Tag` onto
            // Platform tags, so `includeTags("fast","slow")` excludes them exactly like
            // an untagged class. `AndroidSyncDiGraphResolutionTest` compiled, was tagged
            // `@Tag("slow")`, and still did not run: 172 result files, none of them its.
            //
            // Applying a filter that provably cannot select anything in this source set
            // is worse than applying none: the filter reads as "these were considered"
            // and the class that #227 exists to protect was silently skipped. The
            // exemption keeps the task honest — it runs everything it contains, which is
            // what a source set holding exactly one test means.
            //
            // The default branch still excludes `slow`, so a plain `./gradlew
            // :shared:testAndroidHostTest` skips it; only an explicit tag list loses the
            // filter, which is the case CI uses.
            //
            // The hole itself is now checked, not just documented:
            // TestTagCoverageTest.tag_filtered_source_sets_run_on_an_engine_that_honours_tags
            // fails any *filtered* source set that declares a JUnit4 test, so this
            // exemption can no longer quietly spread to a source set the filter applies
            // to (#362).
            name == "testAndroidHostTest" && tags.isNotEmpty() && tags != listOf("all") -> {
                // Intentionally no filter — see above.
            }
            tags.isEmpty() -> {
                // Default: run everything EXCEPT @Tag("slow") — slow requires -Ptest.tags=slow
                excludeTags("slow")
            }
            // `-Ptest.tags=all` applies no tag filter at all. This is the only
            // setting that runs untagged tests, and JUnit's includeTags()
            // excludes them — so a CI step passing a tag list silently skips
            // every test that carries no @Tag. TestTagCoverageTest (arch) fails
            // the build for a class in a tag-filtered source set that has no
            // @Tag; the finding and the reasoning behind that gate's scope are in
            // `docs/decisions/2026-10-04-ci-test-tag-filter-and-vacuous-gates.md`.
            // (This pointer read `docs/decisions/deferred/an-untagged-test-class-...`,
            // which PR #645 deleted with the whole deferred/ tree; the deferred
            // entry had been promoted into that ADR.)
            tags == listOf("all") -> Unit
            else -> includeTags(*tags.toTypedArray())
        }
    }
    // Was the default ~512 MB, then 3 GB. The heap is MEASURED, not chosen: the full
    // population dies with `OutOfMemoryError: Java heap space` at 1024 MB (with the
    // Kover agent attached), and 1536 MB carries the whole suite without one.
    // Forked test JVMs do NOT inherit org.gradle.jvmargs (that's the daemon only).
    // HeapDumpPath is module-local so parallel test runs don't overwrite each
    // other's dumps. ADR 2026-10-22-gradle-memory-budget.
    //
    // The number itself is READ, not written here: `config/docs/gradle-memory-budget.properties`
    // holds it, and the gate checks that this module's heap is the recorded one.
    maxHeapSize = testTaskHeap
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/test-heap-dumps",
        // JVM-level crashes (SIGSEGV, SIGABRT, internal error) write an hs_err file here.
        //
        // Added because a fork of this task has died at least once with no failing test
        // and nothing to show for it afterwards. `build/test-heap-dumps/` is the one
        // directory under `build/` that no later run clears, so it is the only place a
        // crash report can be written and still be there to read tomorrow. The heap dump
        // directory, the reports and the results XML are all replaced by the next
        // successful run — which is exactly why that failure could not be diagnosed
        // afterwards. See `docs/decisions/2026-10-07-a-fork-that-died-left-no-evidence.md`.
        //
        // This catches the JVM dying, not the kernel killing it: SIGKILL from the OOM
        // killer produces no hs_err, and only the exit code Gradle prints. That case is
        // still open, and the file being absent is the evidence that distinguishes them.
        "-XX:ErrorFile=build/test-heap-dumps/hs_err_pid%p.log",
    )
}

// Per-class JVM forking is disabled (forkEvery = 0).
//
// It used to be 1 for three reasons, all of which no longer hold:
//   - Koin global state: cleaned up per class by KoinIsolationExtension
//     (stopKoin() in @AfterAll).
//   - BackgroundFailureHandler: now a value, not process-wide mutable state.
//   - FileSystemContract temp paths: unique via RUN_ID (UUID) + atomic counter.
//
// The value is published as a system property and asserted by ForkEveryIsolationTest.
tasks.withType<Test>().matching { it.name == "jvmTest" || it.name == "archTest" }.configureEach {
    // True for the architecture task and false for the JVM test task. The arch
    // gates read trees the other tests do not, and their inputs are declared on
    // their own task — see the `archTest` task above.
    val archTask = name == "archTest"
    forkEvery = 0

    // The run manifest is written by the ROOT build script, for every module.
    // It used to be written here, by this module alone, and the gate that asks
    // "were the slow classes supposed to run here?" could only answer for
    // `shared`: for `desktopApp` — where 49 of 62 classes are slow — the
    // answer was a fallback that cannot tell "selected no slow class" from
    // "deliberately fast-only". That distinction is the whole question, so the
    // manifest is a convention, not a copy in one module.

    // Published so `ForkEveryIsolationTest` can assert the invariant instead of trusting a
    // comment here. Per-class forking is now off; Koin state is cleaned up per class by
    // KoinIsolationExtension.
    systemProperty("jvmTest.forkEvery", forkEvery.toString())

    // mergeReruns=true: retry attempts (when -Dretry.maxAttempts > 1 is passed) merge
    // into one <testcase> with a <flakyFailure> child, matching desktopApp. Without it
    // a retried shared test produces two <testcase> elements, and check-test-runs.py
    // counts both — the flake-rate aggregator reads the <flakyFailure> signal instead.
    reports.junitXml.mergeReruns.set(true)

    // The architecture gates run in `archTest` — they read the whole tree, and the
    // Konsist scope they build is per-JVM. See the `archTest` task above.
    if (!archTask) {
        filter { excludeTestsMatching("com.singularity.todo.arch.*") }
    }
    // Forks in parallel, still one JVM per class. `forkEvery = 1` is what isolates
    // KoinPlatform state between classes; it costs a JVM start per class, and at
    // 190 classes that was 7m58s of which the tests themselves were a fraction.
    // Parallel forks keep the isolation exactly — each fork is still its own
    // process, re-created per class — and only overlap the startup cost.
    //
    // Scaled to the machine AND capped by the budget: CI runners have 2-4 cores,
    // and four forks there oversubscribe rather than speed anything up. Half the
    // cores, and never more than the budget's count — the budget is the ceiling
    // the memory arithmetic fixes, and the core count is what the machine can
    // actually run at once. Taking only the budget's number oversubscribed the
    // small runners, which is what the ADR promised they would not do.
    //
    // It used to be `availableProcessors / 2` capped at eight, which on a
    // 16-core host made it eight — 12 GB of forks plus the 6144 MB daemon, half
    // again over the ceiling `config/docs/gradle-memory-budget.properties`
    // records. The count belongs there, beside the heap it multiplies.
    val budgetForks = memoryBudget.getProperty("test.forks.module.shared")?.toIntOrNull() ?: 4
    maxParallelForks = if (archTask) {
        // One fork for the architecture suite. The Konsist scope these gates
        // build is per-JVM, and building it costs a quarter of the suite's CPU:
        // four forks built the same scope four times. One fork builds it once.
        1
    } else {
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, budgetForks)
    }
    // Apply heap directly to the jvmTest fork — the global configureEach above
    // also sets this, but being explicit avoids ordering ambiguity.
    maxHeapSize = testTaskHeap
    // JFR profiling — enable with `-PJFR=true`. Records to build/reports/jfr/.
    // `jfc` settings file is in project root so it's not buried in build/.
    if (project.hasProperty("JFR")) {
        // Azul JDK 21: all JFR options go into `-XX:StartFlightRecording=...`
        // (no separate `-XX:FlightRecordingOptions=`). Path is absolute so it resolves
        // regardless of the fork's working directory (Gradle workers run from
        // shared/build/allure-results/, not project root).
        //
        // The path comes from the build directory, so it is this worktree's own.
        // It used to be an absolute path into one checkout, and every other
        // worktree wrote its recording into a directory that belonged to
        // somebody else — or, once that checkout was gone, nowhere at all.
        val jfrDir = layout.buildDirectory.dir("reports/jfr")
        doFirst { jfrDir.get().asFile.mkdirs() }
        jvmArgs(
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
            "-XX:StartFlightRecording=" +
                "disk=false," +
                "filename=" + jfrDir.get().asFile.absolutePath + "/" + name + "-%p.jfr," +
                "dumponexit=true",
        )
    }
    // Absolute path to commonMain sources for ArchitectureTest (Konsist scope).
    // Passed as a system property instead of relying on the test JVM working dir,
    // which is not guaranteed to be the project directory.
    systemProperty(
        "commonMain.root",
        layout.projectDirectory.dir("src/commonMain/kotlin").asFile.absolutePath,
    )
    // Absolute paths to :shared's own test sources and resources, for the tests that
    // read files rather than calling code.
    //
    // `workingDir` below is set to `build/allure-results` so the Allure listener
    // writes where the upload step looks. That silently redefined what every
    // relative path in a test means: they used to resolve against the module
    // directory and now resolve against a build output directory. The symptom was
    // loud for the tests that read a known file — `GenuiSurfaceCorpusTest` and
    // `GenuiFirstAttemptReplayTest` failed with `FileNotFoundException` on
    // run 39005284790 — and quiet for the ones that walk a directory:
    // `SharedHarnessConventionsTest` found nothing and reported green over zero
    // files, which is the failure mode that costs the most to notice.
    //
    // Same reason as `commonMain.root` above: a property that says where the tree is,
    // rather than an assumption about where the JVM was started.
    systemProperty(
        "jvmTestSources.root",
        layout.projectDirectory.dir("src/jvmTest/kotlin").asFile.absolutePath,
    )
    systemProperty(
        "jvmTestResources.root",
        layout.projectDirectory.dir("src/jvmTest/resources").asFile.absolutePath,
    )
    // `PlatformSeamGuardTest` answers "is this seam injected anywhere?", and the seam
    // registry is a project-wide declaration — so the scan has to be project-wide too.
    // `HostEnvironmentPort`'s only production consumer is `AdrStorage`, which moved to
    // `:adr-core` when `:adr-cli` was cut off from `:shared` (#542). Without this the
    // guard reported an injected seam as an orphan: true for `:shared`, false for the
    // repository.
    systemProperty(
        "adrCoreCommonMain.root",
        rootProject.layout.projectDirectory.dir("adr-core/src/commonMain/kotlin").asFile.absolutePath,
    )
    // Absolute path to desktopApp jvmTest sources for ViewModelTestCoverageTest, which
    // aggregates test roots across modules and so cannot be written relatively.
    //
    // `DesktopTestHarnessEnforcementTest` used to read this too, and no longer does:
    // it enforces :desktopApp's own conventions, so it moved to
    // `desktopApp/src/jvmTest` where the path is relative and the property was
    // unnecessary. The property stays for the remaining reader — see #153.
    systemProperty(
        "desktopAppJvmTest.root",
        layout.projectDirectory.dir("../desktopApp/src/jvmTest/kotlin").asFile.absolutePath,
    )
    // Allure test reporting — results written to build/allure-results/
    // allure-kotlin's `allure.results.directory` key is NOT read by allure-junit5's listener;
    // the listener computes its results path relative to the forked JVM's working directory.
    // Setting workingDir on the forked JVM makes it write to <module>/build/allure-results/,
    // which is where the CI upload step and the existing .gitignore `**/build/` entry
    // expect it. The directory is per-task to avoid collisions when tests run in parallel.
    val allureDir = layout.buildDirectory.dir("allure-results").get().asFile
    workingDir = allureDir.also { it.mkdirs() }
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
    //
    // These are the ARCH gates' inputs, and the arch gates have their own task
    // (`archTest`). Declaring them here would make a change to a desktopApp test
    // re-run the four hundred classes that do not read it.
    if (archTask) {
        inputs.dir(layout.projectDirectory.dir("../desktopApp/src/jvmTest/kotlin"))
            .withPropertyName("desktopAppJvmTestSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir(layout.projectDirectory.dir("../mcp-server/src/test"))
            .withPropertyName("mcpServerTestSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    // The build files themselves, for `DetektSourceSetsAreAllScannedTest`, which reads
    // each module's `detekt.source` list. Found by being bitten: that test watches for
    // a source set being left out of the list, and editing the list did not re-run the
    // test — `:shared:jvmTest` was UP-TO-DATE and reported a verdict about a file it
    // had not re-read. A gate that cannot be invalidated by the change it watches is
    // worse than no gate, because its silence looks like a pass.
    // The arch gates read the build files, the flows and the skills — and the
    // arch gates have their own task, so these inputs are declared on it.
    if (archTask) {
        inputs.files(
            rootProject.file("shared/build.gradle.kts"),
            rootProject.file("androidApp/build.gradle.kts"),
            rootProject.file("desktopApp/build.gradle.kts"),
            rootProject.file("mcp-server/build.gradle.kts"),
        ).withPropertyName("moduleBuildFiles")
         .withPathSensitivity(PathSensitivity.RELATIVE)
    }
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
    if (archTask) {
        inputs.dir(layout.projectDirectory.dir("../Maestro"))
            .withPropertyName("maestroFlows")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
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
    // The search query grammar, read by `SearchQueryGrammarAgreementTest`.
    //
    // That test answers "does every rule in the grammar have a counterpart in the
    // code, and does every rule the code claims have one in the grammar?" — which
    // means it has to read the document itself, not a copy of it. The path is declared
    // rather than derived for the reason `maestro.root` is declared: a derived path
    // is invisible to scripts/check-test-task-inputs.py, which fails when a path-valued
    // system property points outside its module with no input covering it.
    systemProperty(
        "searchQueryGrammar.spec",
        rootProject.file("docs/specs/search-query-grammar.md").absolutePath,
    )
    // The document is not a compile input of :shared, so without this the task went
    // UP-TO-DATE on every grammar edit and re-reported the previous run's verdict —
    // a gate that cannot be invalidated by the change it watches.
    inputs.file(rootProject.file("docs/specs/search-query-grammar.md"))
        .withPropertyName("searchQueryGrammarSpec")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The backup bucket's RLS policy, read by `BackupBucketPolicyTest`.
    //
    // That test answers "is every policy on the backups bucket scoped to the
    // caller's own uid?" — a policy that checked only the bucket would let any
    // signed-in account read and delete another account's archives, so the check
    // has to read the migration itself rather than a copy of it. The path is
    // declared for the reason `searchQueryGrammar.spec` is declared: a derived
    // path is invisible to scripts/check-test-task-inputs.py, which fails when a
    // path-valued system property points outside its module with no input
    // covering it.
    systemProperty(
        "backupBucketPolicy.migration",
        rootProject.file("supabase/migrations/2026-10-24-backup_storage_bucket.sql").absolutePath,
    )
    inputs.file(rootProject.file("supabase/migrations/2026-10-24-backup_storage_bucket.sql"))
        .withPropertyName("backupBucketPolicyMigration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The text-field inventory in the skill, read by `TextFieldShapeConventionTest`.
    //
    // That test answers "is every text field in the app classified?" — and the
    // classification is published in two places: the registry in the test, and the
    // inventory table in the skill a developer reads before adding a field. The test
    // compares the two, so the skill is an input of the test and has to be declared:
    // edit the inventory, leave :shared:jvmTest UP-TO-DATE, and the gate re-reports a
    // verdict about a table it never re-read — the same failure mode as the Maestro
    // flows above.
    if (archTask) {
        inputs.dir(rootProject.layout.projectDirectory.dir(".agents/skills"))
            .withPropertyName("agentSkills")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    systemProperty(
        "skills.root",
        rootProject.layout.projectDirectory.dir(".agents/skills").asFile.absolutePath,
    )
    // The Desktop packaging script, read by `JvmReminderSchedulerLauncherPathTest`.
    //
    // That test pins `/opt/singularity-todo/bin/singularity-todo` — the path a
    // `systemd --user` reminder unit executes — against jpackage's `packageName`, which
    // lives in this file. Two files, two modules, and until now zero references:
    // renaming the package would leave every Desktop reminder silently unable to fire,
    // discovered after installation.
    //
    // It also carries the premise: jpackage's default `installationPath` is
    // `/opt/<packageName>`, and no `/usr/bin` symlink is created. The test used to pin
    // `/usr/bin/<packageName>` on the belief that it was, which no package ever
    // produced; it was a constant compared with a constant and passed against the exact
    // bug it existed to prevent. The release pipeline now installs the real package and
    // runs the launcher before publishing (#664), so the path is checked against an
    // artifact rather than only against this file's opinion.
    //
    // The path is declared rather than derived. Deriving it from `commonMain.root` by
    // walking parent directories is exactly what the `maestro.root` comment above calls
    // out as the defect — an invisible path is invisible to the invalidation wiring too.
    // The file is already an input of this task via `moduleBuildFiles`, so editing it
    // re-runs the test rather than letting it report a stale pass.
    systemProperty(
        "desktopApp.buildScript",
        rootProject.file("desktopApp/build.gradle.kts").absolutePath,
    )
    // SyncPeriodicTriggerWiringTest reads the androidMain and jvmMain platform
    // modules. Same staleness hazard as above: edit a platform module, leave
    // :shared:jvmTest UP-TO-DATE, and the gate re-reports a verdict about the
    // binding it was supposed to have caught missing.
    if (archTask) {
        inputs.dir(layout.projectDirectory.dir("src/androidMain/kotlin"))
            .withPropertyName("androidMainSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir(layout.projectDirectory.dir("src/jvmMain/kotlin"))
            .withPropertyName("jvmMainSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
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
    // The exported Room schemas, read at runtime by the generated round-trip test.
    //
    // `MigrationRoundTripSupport.schemaFile(version)` resolves
    // `shared/schemas/com.singularity.todo.core.database.AppDatabase/<version>.json`
    // and opens it. That tree is not a compile input of :shared, so the task's
    // up-to-date check did not see it: an export that changed — a re-export with
    // different columns, which is exactly the defect `check-room-schema-integrity.py`
    // now catches statically — left :shared:jvmTest UP-TO-DATE, and the round-trip
    // test re-reported a verdict about a schema it had never re-read.
    //
    // Declared as a directory rather than per version, because the test reads one file
    // per step and the set of steps changes with the chain.
    inputs.dir(layout.projectDirectory.dir("schemas"))
        .withPropertyName("exportedRoomSchemas")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The positive control of UnannotatedTestMemberTest reads a committed class that is
    // broken on purpose, and this tree is on no compile path, so nothing else declares
    // it. Declared here for the same reason as every input above: a gate that cannot be
    // invalidated by the change it watches reports a verdict about a file it never re-read.
    if (archTask) {
        inputs.dir(layout.projectDirectory.dir("src/jvmTest/fixtures"))
            .withPropertyName("jvmTestFixtures")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
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

    // androidHostTest — the Android/Robolectric-capable source set. Its only content is
    // AndroidManifest.xml; every test `testAndroidHostTest` executes comes from
    // commonTest. That left the task green while `PlatformModule.android.kt` — the half
    // of the graph where the sync cycle shipped as a StackOverflowError at app start —
    // was resolved by nothing at all (#227).
    //
    // The stack below was declared 2026-10-07 for `AndroidSyncDiGraphResolutionTest`,
    // which needs a real `Context` because `platformModule()` reads `get<Context>()`
    // inside several `single` bodies. Each entry is load-bearing:
    //
    // - `robolectric` + `androidx-test-core` + `androidx-testExt-junit`: Robolectric and
    //   the AndroidX JUnit4 runner, which supplies the shadowed Android runtime.
    // - `junit-vintage-engine` (RuntimeOnly): **required**, not optional. Robolectric is
    //   a JUnit4 runner and this task runs the JUnit Platform, so without the Vintage
    //   engine the class is silently skipped and the task passes having run nothing.
    //
    //   The engine has a consequence that cost a full verification cycle to find: it does
    //   not map Jupiter's `@Tag` onto Platform tags, so `includeTags("fast","slow")` — which
    //   this task applies like every other — excludes a Robolectric class no matter what
    //   it is tagged. The test was tagged `@Tag("slow")` and still did not run: 172 result
    //   files, none of them its. `testAndroidHostTest` is therefore exempted from an
    //   explicit tag filter above, because a filter that provably cannot select anything
    //   in this source set reads as "considered" while skipping the one class that
    //   matters.
    //
    // - `compose-ui-test-junit4` (AndroidX, NOT the JetBrains multiplatform artifact —
    //   AndroidX is Robolectric-compatible, JetBrains is not) is deliberately NOT
    //   declared: a graph-resolution test composes no UI, and a dependency with no
    //   consumer keeps upgrading while `check-dependency-usage.py` flags it unused.
    //
    // **A test landed here on 2026-10-08** — `AndroidNotifierTest`, which covers the
    // `POST_NOTIFICATIONS` matrix on `AndroidNotifier.isSupported`. It needs no Room and
    // no native library, which is why it runs where `AndroidSyncDiGraphResolutionTest`
    // could not: `ApplicationProvider` and `ShadowNotificationManager` are pure framework
    // shadows. The SQLite limitation below is therefore a property of *that* test, not a
    // property of this source set, and the two must not be conflated — "the source set
    // holds no tests" was true for a day and false the next.
    //
    // The finding that made it so: Room's bundled SQLite ships an Android `.so` that
    // Robolectric cannot load on this host, so `AndroidSyncDiGraphResolutionTest`
    // compiled, ran, and then failed with `UnsatisfiedLinkError: no sqliteJni in
    // java.library.path` the moment it built the database. The stack stays for that test
    // because the fix is a native library in `jniLibs`, not a build change. The finding
    // and the smallest fix were recorded as
    // "the-android-graph-test-runs-but-cannot-open-a-database" in
    // `docs/decisions/deferred/`, a tree PR #645 deleted along with 128 other
    // deferred entries migrated to GitHub issues; the finding itself is not lost,
    // it just has no file to point at.
    add("androidHostTestRuntimeOnly", libs.junit.vintage.engine)
    // The other three were described above as load-bearing and were not declared; the
    // Vintage engine alone puts JUnit4 on the Platform but supplies no Android runtime,
    // so a class annotated `@RunWith(RobolectricTestRunner::class)` needs the runner and
    // `ApplicationProvider` on the compile classpath to exist at all.
    add("androidHostTestImplementation", libs.robolectric)
    add("androidHostTestImplementation", libs.androidx.test.core)
    add("androidHostTestImplementation", libs.androidx.testExt.junit)
    // sqlite-bundled-jvm explicitly (not transitively from androidMain), so that
    // the linux_x64 native ends up in androidHostTestRuntimeOnly for extraction below.
    // Robolectric on Linux x86_64 needs this instead of sqlite-bundled-android.
    add("androidHostTestRuntimeOnly", "androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqlite.get()}")
}

// ---------------------------------------------------------------------------
// sqlite-bundled-jvm native extraction for Robolectric on Linux
// ---------------------------------------------------------------------------
// Robolectric on Linux x86_64 needs the native library from sqlite-bundled-jvm,
// which ships `natives/linux_x64/libsqliteJni.so`.  The android target's
// `sqlite-bundled` resolves to sqlite-bundled-android (ARM ABIs only), which
// Robolectric cannot load on this host.  The task below extracts the linux_x64
// native from the JVM artifact into build/sqlite-natives/linux_x64/ and the
// test JVM is started with that directory on java.library.path.

val sqliteNativesDir = layout.buildDirectory.dir("sqlite-natives/linux_x64")

// sqlite-bundled-jvm ships `natives/linux_x64/libsqliteJni.so`.  Robolectric loads it
// before any test code runs, so this is a process-level JVM argument, not a test system
// property.  The task walks the jar inside the resolvable extract configuration and
// extracts the one .so into build/sqlite-natives/linux_x64/ — a directory that is on
// java.library.path when the test JVM starts.
val extractSqliteNatives = tasks.register<Copy>("extractSqliteNatives") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val sqliteJar = configurations
        .named("sqliteBundledJvmExtractedFiles")
        .get()
        .files
        .single { it.name.contains("sqlite-bundled-jvm") }
    from(zipTree(sqliteJar).filter { entry ->
        entry.name == "libsqliteJni.so"
    })
    into(sqliteNativesDir)
}

// A resolvable configuration that extends androidHostTestRuntimeOnly so that
// extractSqliteNatives can iterate over its resolved files.  Without this, the
// runtime-only configuration is declared `canBeResolved=false` by the Android
// Gradle Plugin and cannot be used in a task's DOCl.
val sqliteBundledJvmExtractedFiles = configurations.create("sqliteBundledJvmExtractedFiles") {
    extendsFrom(configurations.getByName("androidHostTestRuntimeOnly"))
    isTransitive = false
}
dependencies.add("sqliteBundledJvmExtractedFiles",
    "androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqlite.get()}"
)

// The test JVM must start with the native library path pointing at the extracted
// directory.  Robolectric loads it before any test code runs, so this is
// jvmArgs (process start) rather than a test system property.  afterEvaluate is
// required because withHostTest {} registers the task after the script evaluates.
afterEvaluate {
    tasks.named<Test>("testAndroidHostTest") {
        jvmArgs("-Djava.library.path=${sqliteNativesDir.get().asFile.absolutePath}")
        dependsOn(extractSqliteNatives)
        // commonTest is inherited by androidHostTest by default and would re-run
        // ~2600 tests already covered by :shared:jvmTest. Narrow to this source
        // set's own classes only (Robolectric platform seams).
        include(
            "com/singularity/todo/mcp/ToolSurfaceWiringTest.class",
            "com/singularity/todo/core/di/AndroidGraphResolutionTest.class",
            "com/singularity/todo/core/notifications/AndroidNotifierTest.class",
            "com/singularity/todo/core/observability/AndroidRecordRetentionTest.class",
            "com/singularity/todo/core/security/ArchiveCryptoGoldenAndroidTest.class",
        )
    }
}

// Room 3 KSP schema export
room3 {
    schemaDirectory("$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// resolvedArtifacts — the mapping Gradle has and the catalog does not (#205)
// ---------------------------------------------------------------------------
// A Gradle coordinate does not determine an import package:
// `org.jetbrains.compose.material3:material3` is imported as
// `androidx.compose.material3`. So "is this dependency used?" cannot be answered
// from libs.versions.toml — it needs the resolved files each configuration
// actually contributes, next to the coordinate they came from. This task prints
// exactly that, as `sourceSet<TAB>group:artifact<TAB>file`, and
// `scripts/check-dependency-usage.py` reads it.
//
// Prints rather than asserts: the comparison needs the package roots inside each
// jar, which is not a Gradle concern, and the gate is where a finding becomes a
// failure.
tasks.register("printResolvedArtifacts") {
    group = "verification"
    description = "Prints every resolved artifact per source set with its coordinate."

    // The configuration cache rejects a captured Project. A `doLast` closure that
    // touches `configurations` captures one implicitly, and the failure is
    // deferred: the task prints its output and *then* the build fails on store,
    // so the first run looked fine and every run after it failed with
    // "cannot serialize DefaultProject".
    //
    // The supported escape is to declare the Gradle model as an @Internal input
    // and read it inside the action. Nothing here is a real input — the point of
    // the task is to report resolution state, which by definition is not known
    // until execution — so declaring it uncacheable is the honest description.
    notCompatibleWithConfigurationCache("Reads dependency resolution state, which is only known at execution time.")

    val sourceSets = listOf("commonMain", "androidMain", "jvmMain")
    doLast {
        sourceSets.forEach { name ->
            // The `${sourceSet}Implementation` configuration is declared
            // canBeResolved=false by the KMP plugin, so the resolved view comes
            // from the `…ResolvableDependenciesMetadata` sibling. Resolving the
            // metadata variant is also the right granularity for a *usage*
            // question: it is what the compiler sees for that source set, before
            // platform narrowing.
            val configuration = configurations.findByName("${name}ResolvableDependenciesMetadata")
                ?: error("no resolvable configuration for $name")
            configuration.incoming.artifactView { lenient(true) }.artifacts.forEach { artifact ->
                val id = artifact.id.componentIdentifier
                val coordinate = if (id is ModuleComponentIdentifier) "${id.group}:${id.module}" else id.displayName
                println("$name\t$coordinate\t${artifact.file.absolutePath}")
            }
        }
    }
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
        "src/androidMain/kotlin",
        // `androidHostTest` is declared and configured — `androidHostTestRuntimeOnly`
        // pulls the JUnit Vintage engine — but holds no Kotlin sources yet; only a
        // manifest and a `.gitkeep`. The Robolectric graph test for #227 is **not
        // written**: `SyncDiGraphResolutionTest` lives in `jvmTest` and resolves the
        // common DI module, so it never loads the Android platform modules, which is
        // the half that has never been resolved by a test.
        //
        // It is listed here anyway because a source set that exists but is not scanned
        // is worse than one that does not exist: detekt reports nothing about a path it
        // was never given, so a defect in it would be invisible rather than absent.
        // `DetektSourceSetsAreAllScannedTest` keeps the list honest — it fails on any
        // source set that contains Kotlin and is not scanned, and on any listed path that
        // does not exist at all.
        //
        // No parentheses in these comments: `DetektSourceSetsAreAllScannedTest` extracts
        // this call's argument list with a regex that stops at the first closing paren
        // anywhere inside the call — including one written inside a comment — and would
        // then read a truncated list and report this source set as unscanned.
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
// No `kover { }` block here. Coverage is configured once, at settings level
// (settings.gradle.kts): the plugin applies itself to every project, so the
// `com.singularity.todo.*` instrumentation filter arrives here as a convention
// and the report is produced once for the whole build. A per-project report
// would measure only this project's own test tasks — which is exactly the gap
// that made every Compose flow test in desktopApp invisible to the number.

// ---------------------------------------------------------------------------
// exportSyncFieldAllowlist — the derived field lists, exported from the models
// ---------------------------------------------------------------------------
//
// The derivation lives in Kotlin, because only the compiler knows what a model's
// serial descriptor holds. The tools that need the lists are Python, and a gate
// that runs a compiler is a gate nobody runs. So the lists are exported from the
// compiled classes, and the export is committed; the seed generator and the gate
// read that instead of parsing the Kotlin source.
//
// This replaces a regular expression that read the Kotlin source and stopped at
// the first closing parenthesis — inside a comment — and so read a list that was
// three names short.

val exportSyncFieldAllowlist = tasks.register<JavaExec>("exportSyncFieldAllowlist") {
    description = "Exports the derived sync field lists to config/docs/sync-field-allowlist.json"
    group = "sync"
    val jvmClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    val target = rootProject.layout.projectDirectory.file("config/docs/sync-field-allowlist.json")
    mainClass.set("com.singularity.todo.core.sync.SyncFieldAllowlistExportKt")
    classpath(files(jvmClasses), configurations.named("jvmRuntimeClasspath"))
    args(target.asFile.absolutePath)
    inputs.files(jvmClasses)
    outputs.file(target)
    dependsOn("compileKotlinJvm")
    doFirst {
        target.asFile.parentFile.mkdirs()
    }
}

// ---------------------------------------------------------------------------
// generateSyncFieldAllowlistSql — the seed, generated from the derived lists
// ---------------------------------------------------------------------------

tasks.register<DefaultTask>("generateSyncFieldAllowlistSql") {
    description = "Generates the sync_field_allowlist seed SQL from the derived field lists"
    group = "sync"
    inputs.file(rootProject.file("config/docs/sync-field-allowlist.json"))
    outputs.file(rootProject.file("supabase/sync_field_allowlist_seed.sql"))
    dependsOn("exportSyncFieldAllowlist")
    doLast {
        providers.exec {
            commandLine("python3", rootProject.file("infra/gen_sync_allowlist_sql.py").absolutePath, rootProject.projectDir.absolutePath)
        }
    }
}

// ---------------------------------------------------------------------------
// verifySyncFieldAllowlist — the committed export is what the code exports
// ---------------------------------------------------------------------------
//
// The export task writes the file, so it cannot also report that the file is
// wrong: it would have already overwritten it. This check asks the exporter
// whether the committed file says what the models declare, and the exporter
// answers by comparing the parsed lists — the same comparison the JVM test
// (`SyncFieldAllowlistExportTest`) makes, through the same function, so the
// two cannot drift into calling the same file current and stale.
//
// It is cheap on purpose: it compiles the JVM classes and runs one main. The
// price of the fast answer is that it says "the export is stale" without saying
// what changed; the export task and the diff say that.
val verifySyncFieldAllowlist = tasks.register<JavaExec>("verifySyncFieldAllowlist") {
    description = "Fails when config/docs/sync-field-allowlist.json is not what the code exports"
    group = "verification"
    val jvmClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    val committed = rootProject.layout.projectDirectory.file("config/docs/sync-field-allowlist.json")
    mainClass.set("com.singularity.todo.core.sync.SyncFieldAllowlistExportKt")
    classpath(files(jvmClasses), configurations.named("jvmRuntimeClasspath"))
    args("--check", committed.asFile.absolutePath)
    inputs.files(jvmClasses)
    // The committed file is an input, not an output: this task reads it and
    // compares, and it must not write over what it is checking.
    inputs.file(committed)
    dependsOn("compileKotlinJvm")
}

// ---------------------------------------------------------------------------
// exportToolSurface — the tool surface, exported from the compiled declarations
// ---------------------------------------------------------------------------
//
// The drift gate reads the tool surface out of the Kotlin *sources*, because a gate
// that runs a compiler is a gate nobody runs. This task is the other half: it reads
// the same surface out of the *compiled* classes and writes it to
// `config/docs/tool-surface.json`, and a test says the committed file is current. The
// gate then compares what it parsed with what the compiler saw — parsed == exported ==
// compiled — so a parser that misreads a class cannot pass unnoticed.
//
// The file is generated, and it is generated from the code: there is no second copy of
// a description anywhere, only a projection of the one that exists.

val exportToolSurface = tasks.register<JavaExec>("exportToolSurface") {
    description = "Exports the declared MCP tool surface to config/docs/tool-surface.json"
    group = "documentation"
    // The compiled JVM classes and the JVM runtime classpath, named the way the
    // module names them. The KMP target objects are awkward to reach from a build
    // script, and the class directories are what the task actually runs against.
    val jvmClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    val target = rootProject.layout.projectDirectory.file("config/docs/tool-surface.json")
    mainClass.set("com.singularity.todo.mcp.ToolSurfaceExportKt")
    classpath(files(jvmClasses), configurations.named("jvmRuntimeClasspath"))
    args(target.asFile.absolutePath)
    inputs.files(jvmClasses)
    outputs.file(target)
    dependsOn("compileKotlinJvm")
    doFirst {
        target.asFile.parentFile.mkdirs()
    }
}

// acpBridgeProbe — the application's ACP client, against a real bridge.
//
// The tests talk to a fake agent: ours, taught to answer what our client expects.
// That proves the client and the fake agree with each other, and says nothing about
// whether they agree with the protocol — which is written by somebody else. This task
// runs the same client against a real bridge and prints what came back.
//
// It is a task and not a test because it needs a real `npx`, a real network and a
// real bridge: a test that cannot run is a skipped test, and this repository does not
// allow skipped tests. `scripts/acp-bridge-probe.sh` is the way in.
//
// The task does not fail the build on a failed probe: the probe's verdict is its exit
// code, and the script is what reports it. A task that failed the build would make
// every build on this machine depend on whether the network was up.
//
// The default bridge lives in the probe, so that the task and the probe cannot
// disagree about it. The script carries the same default, because a script has to
// know what it is about to drive before it hands it over — the two copies are the
// same string, and the script is the one a reader meets first.
// The task passes nothing when nobody said which bridge to drive, and the probe
// falls back to the Claude one — the bridge this repository registered first.
val acpBridgeProbe = tasks.register<JavaExec>("acpBridgeProbe") {
    description = "Drives the ACP client against a real agent bridge and prints what came back"
    group = "verification"
    val jvmClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    mainClass.set("com.singularity.todo.feature.ai.acp.AcpBridgeProbeKt")
    classpath(files(jvmClasses), configurations.named("jvmRuntimeClasspath"))
    // The bridge's command line travels in a file, one argument per line: a command
    // line that travels as a string comes back as a string, and a path with a space
    // in it does not survive being split on spaces.
    val commandFile = providers.gradleProperty("acpBridgeFile").orNull
    val bridge = if (commandFile != null) {
        rootProject.file(commandFile).readLines().filter { it.isNotBlank() }
    } else {
        emptyList()
    }
    args(listOf(rootProject.layout.projectDirectory.asFile.absolutePath) + bridge)
    inputs.files(jvmClasses)
    dependsOn("compileKotlinJvm")
    // The bridge may be an `npx` invocation downloading its package on first use,
    // and that download is not the handshake.
    timeout.set(JavaDuration.ofMinutes(10))
    // The probe's own verdict is its exit code, and the script reads the verdict out
    // of the output — so a probe that did not connect is a report, not a failed build.
    isIgnoreExitValue = true
}

// verifyToolSurface — the committed catalogue is what the code exports, checked
// without running the whole test suite.
//
// The export task writes the file, so it cannot also report that the file is
// wrong: it would have already overwritten it. The check therefore exports to a
// scratch file and compares. That is the fast half of the answer — the JVM test
// (`ToolSurfaceExportTest`) says the same thing, but it lives in a suite that takes
// twenty minutes, and a tool description that changed shape is found in CI instead
// of in the loop where it was written.
//
// It is cheap on purpose: it compiles the JVM classes and runs one main. The price
// of the fast answer is that it says "the catalogue is stale" without saying what
// changed; the export task and the diff say that.
val verifyToolSurface = tasks.register<JavaExec>("verifyToolSurface") {
    description = "Fails when config/docs/tool-surface.json is not what the code exports"
    group = "verification"
    val jvmClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    val committed = rootProject.layout.projectDirectory.file("config/docs/tool-surface.json")
    val scratch = layout.buildDirectory.file("tool-surface-verify.json")
    mainClass.set("com.singularity.todo.mcp.ToolSurfaceExportKt")
    classpath(files(jvmClasses), configurations.named("jvmRuntimeClasspath"))
    args(scratch.get().asFile.absolutePath)
    inputs.files(jvmClasses)
    // The committed file is an input, not an output: this task reads it and
    // compares, and it must not write over what it is checking.
    inputs.file(committed)
    outputs.file(scratch)
    dependsOn("compileKotlinJvm")
    doFirst {
        scratch.get().asFile.parentFile.mkdirs()
    }
    doLast {
        val exported = scratch.get().asFile.readText()
        val current = committed.asFile.takeIf { it.isFile }?.readText()
        if (exported != current) {
            throw GradleException(
                "config/docs/tool-surface.json is not what the code exports. " +
                    "Regenerate it with: ./gw :shared:exportToolSurface"
            )
        }
    }
}

// Retro: stale shared-jvm.jar dependency — ensure test compile sees main classes/jar.
tasks.named("compileTestKotlinJvm") {
    dependsOn("jvmJar")
}
