import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/*
 * `:mvi` — the reusable MVI runtime, and nothing else.
 *
 * `MviIntent`, `MviEvent`, `IntentActions`, `EventBus`, `MviViewModel` and one library-owned
 * diagnostics seam (`MviDiagnostics`). It depends on kotlinx-coroutines and AndroidX
 * `ViewModel` — the sole source of ViewModel lifecycle — and on nothing else. No Compose,
 * Koin, Room, Navigation, feature/domain types, AI, persistence or app error vocabulary.
 *
 * Coroutine *infrastructure* is deliberately absent: no closeable scope, no scope factory, no
 * dispatcher policy, no crash-reporting adapter. `MviViewModel` takes a `CoroutineScope` its
 * caller owns and cancels that scope's `Job` at teardown, so a non-MVI component never has to
 * depend on this module to cancel a scope.
 *
 * Boundary and reasoning: `docs/architecture/mvi-library-boundary.md`,
 * ADR `docs/decisions/2026-11-27-mvi-library-module-boundary.md`.
 *
 * Both dependencies are `api` because their types are in the public signatures: `StateFlow`,
 * `Flow`, `Job` and `CoroutineScope` in the base class's own contract, and
 * `androidx.lifecycle.ViewModel` in its supertype.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "com.singularity.todo.mvi"
        compileSdk =
            libs.versions.sdk.compile
                .get()
                .toInt()
        minSdk =
            libs.versions.sdk.min
                .get()
                .toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test.junit5)
            implementation(libs.junit.jupiter)
            // `runTest`/`TestScope`/`advanceUntilIdle` — test-only, never a production dependency.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The heap of this module's test JVMs, read from the one record that holds every module's.
// `scripts/check-memory-budget.py` is what keeps the two in step: a module that runs tests with
// a heap nobody wrote down is a module whose heap nobody can check.
val memoryBudget =
    Properties().apply {
        rootProject.file("config/docs/gradle-memory-budget.properties").inputStream().use { load(it) }
    }
val testTaskHeap =
    memoryBudget.getProperty("test.heap.module.mvi")
        ?: throw GradleException(
            "config/docs/gradle-memory-budget.properties declares no test.heap.module.mvi — " +
                "the heap of this module's test tasks has to be recorded somewhere.",
        )

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = testTaskHeap
    // Discovering nothing is a configuration error, not a pass — the same rule every module
    // carries. A module whose tests nobody runs is the "works but is never called" defect one
    // layer down.
    failOnNoDiscoveredTests = true
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    ignoreFailures = false
    source.setFrom("src/commonMain/kotlin", "src/commonTest/kotlin")
}

dependencies {
    detektPlugins(project(":detekt-rules"))
}

// ---------------------------------------------------------------------------
// The production dependency graph, verified as a graph (#3270 review)
// ---------------------------------------------------------------------------
// `MviBoundaryTest` reads imports, and an import list cannot see a dependency that
// nobody has used yet. Adding
//
//     commonMain.dependencies { implementation(libs.compose.runtime) }
//
// to this file compiles, changes no source, and leaves the import scan green — while
// every consumer of `:mvi` already compiles against Compose. The acceptance
// criterion is about the *dependency*, so it is checked where dependencies exist.
//
// The allow-list is a list of coordinates, not a prefix: `org.jetbrains.kotlinx:*`
// would accept kotlinx-serialization and kotlinx-datetime, and the point is that a
// new coordinate is a deliberate edit to this list. Transitives are checked too —
// `implementation` can hide a library one level down, and it reaches the consumer's
// classpath either way.
//
// The list is the contract in `docs/architecture/mvi-library-boundary.md`. Changing
// one without the other is the "exception nobody wrote down" this exists to prevent.
val verifyDependencyBoundary =
    tasks.register("verifyDependencyBoundary") {
        group = "verification"
        description = "Fails when :mvi's production graph, on any target, reaches anything but coroutines and lifecycle."

        // Every production source set the module has, not just `commonMain`. A dependency added
        // to `androidMain` or `jvmMain` is on that target's classpath and on its consumers'
        // classpath, so a check of the common graph alone leaves the target-specific half of
        // the boundary unguarded — the half a "this only runs on Android" argument is most
        // likely to add something to.
        //
        // The configuration comes from the source sets themselves rather than a literal list:
        // a target added to this module later appears here without anyone remembering to edit
        // the check, which is the failure mode a hand-written list always has.
        val productionSourceSets =
            listOf("commonMain", "androidMain", "jvmMain").filter { name ->
                configurations.findByName("${name}ResolvableDependenciesMetadata") != null
            }
        check(productionSourceSets.size >= 2) {
            "MviDependencyBoundary: found ${productionSourceSets.size} production source set(s) " +
                "($productionSourceSets). This module has Android and JVM targets, so a check " +
                "over fewer graphs than that judges less than the module ships."
        }

        // Exact coordinates, and nothing by prefix. An earlier version allowed
        // `org.jetbrains.kotlinx:kotlinx-coroutines-core-`, which accepts any future artifact
        // starting with that string — a prefix is a hole in a list whose whole purpose is that
        // every entry was written down. The platform and metadata variants of coroutines do NOT
        // appear in these graphs (the `…ResolvableDependenciesMetadata` view resolves module
        // names), so no prefix was needed in the first place.
        val allowedProductionCoordinates =
            listOf(
                // The two declared dependencies.
                "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel",
                // Their transitive closure: the stdlib, coroutines' own atomicfu, and the three
                // AndroidX artifacts lifecycle-viewmodel depends on.
                "org.jetbrains.kotlinx:atomicfu",
                "org.jetbrains.kotlin:kotlin-stdlib",
                "androidx.lifecycle:lifecycle-viewmodel",
                "androidx.annotation:annotation",
                "androidx.collection:collection",
            )

        val dependencyBoundaryReport = layout.buildDirectory.file("reports/mvi-dependency-boundary.txt")

        // Looked up at configuration time and held as a value the action reads: reaching for
        // `project.configurations` at execution time is what the configuration cache refuses,
        // and that failure is deferred — the task prints its verdict and *then* the build fails.
        val graphs = productionSourceSets.map { configurations.named("${it}ResolvableDependenciesMetadata") }
        val sourceSetNames = productionSourceSets
        // Resolution state is only known at execution time, so this task cannot be cached.
        notCompatibleWithConfigurationCache("Reads dependency resolution state.")

        val report = dependencyBoundaryReport
        outputs.file(report)

        doLast {
            val problems = mutableListOf<String>()
            val reportLines = mutableListOf<String>()

            graphs.forEachIndexed { index, graph ->
                val sourceSet = sourceSetNames[index]
                // `lenient` so one unresolvable coordinate is named instead of aborting
                // resolution: a boundary check that cannot resolve the graph it is judging must
                // say so, never report a green over an empty set.
                val artifacts = graph.get().incoming.artifactView { lenient(true) }.artifacts

                val seen = sortedSetOf<String>()
                artifacts.artifacts.forEach { artifact ->
                    val id = artifact.id.componentIdentifier
                    seen +=
                        if (id is ModuleComponentIdentifier) {
                            "${id.group}:${id.module}"
                        } else {
                            id.displayName
                        }
                }

                if (seen.isEmpty()) {
                    problems +=
                        "$sourceSet: resolved to no artifact at all. A boundary check over an " +
                        "empty graph passes without judging anything."
                    return@forEachIndexed
                }

                seen.filterNot { coordinate -> allowedProductionCoordinates.any { coordinate.startsWith(it) } }
                    .forEach { problems += "$sourceSet: $it is not in the allowed set" }

                artifacts.failures.forEach { failure ->
                    problems += "$sourceSet: part of the graph did not resolve — ${failure.message}"
                }

                reportLines += seen.map { "$sourceSet\t$it" }
            }

            check(problems.isEmpty()) {
                buildString {
                    appendLine("MviDependencyBoundary: :mvi's production graph reaches forbidden dependencies.")
                    problems.forEach { appendLine("  + $it") }
                    appendLine()
                    appendLine(
                        "Allowed (docs/architecture/mvi-library-boundary.md): " +
                            allowedProductionCoordinates.joinToString(", "),
                    )
                    appendLine(
                        "A deliberate new dependency changes this list and that document in the " +
                            "same commit — an exception nobody wrote down is a decision nobody made.",
                    )
                }
            }

            report
                .get()
                .asFile
                .apply {
                    parentFile.mkdirs()
                    writeText(reportLines.joinToString("\n", postfix = "\n"))
                }
        }
    }

// Deliberately NOT `check.dependsOn(...)`.
//
// The task resolves dependency graphs, which is only knowable at execution time, so it is
// incompatible with the configuration cache — and an incompatible task in a build discards the
// cache entry for the WHOLE invocation. Wiring it into `check` would make every `./gw check`
// and every `:mvi:check` pay for that, to judge a graph that changes when the build file
// changes and at no other time.
//
// So it runs where the loop already runs such checks: as its own step in `hk.pkl` (the
// `mvi:dependencyBoundary` entry) and as part of `:mvi`'s checks in `scripts/changed-modules.py`.
// `println`-style report tasks in this repository — `:shared:printResolvedArtifacts` — are wired
// the same way for the same reason.
