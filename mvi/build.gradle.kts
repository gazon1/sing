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
        description = "Fails when :mvi's production graph reaches anything but coroutines and lifecycle."

        // The configuration is resolved on the task's own `Configuration`, held as a value the
        // action can read. Reaching for `project.configurations` at execution time is the thing
        // the configuration cache refuses, and the failure is deferred — the task prints its
        // verdict and *then* the build fails to store, so the first run looks fine.
        val allowedProductionCoordinates =
            listOf(
                // The two declared dependencies.
                "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel",
                // Their transitive closure: the stdlib, coroutines' own atomicfu, the metadata
                // variant of coroutines (no code of its own), and the three AndroidX artifacts
                // lifecycle-viewmodel depends on.
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-",
                "org.jetbrains.kotlinx:atomicfu",
                "org.jetbrains.kotlin:kotlin-stdlib",
                "androidx.lifecycle:lifecycle-viewmodel",
                "androidx.annotation:annotation",
                "androidx.collection:collection",
            )

        val dependencyBoundaryReport = layout.buildDirectory.file("reports/mvi-dependency-boundary.txt")

        val resolved = configurations.named("commonMainResolvableDependenciesMetadata")
        // Resolution state is only known at execution time, so this task cannot be cached.
        notCompatibleWithConfigurationCache("Reads dependency resolution state.")

        val report = dependencyBoundaryReport
        outputs.file(report)

        doLast {
            // `lenient` so one unresolvable coordinate is named instead of aborting resolution:
            // a boundary check that cannot resolve the graph must say so, never report a green
            // over an empty set.
            val artifacts = resolved.get().incoming.artifactView { lenient(true) }.artifacts

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

            check(seen.isNotEmpty()) {
                "MviDependencyBoundary: 'commonMainResolvableDependenciesMetadata' resolved to no " +
                    "artifact at all. A boundary check over an empty graph passes without judging " +
                    "anything — the configuration name or the module is wrong."
            }

            val offenders =
                seen.filterNot { coordinate ->
                    allowedProductionCoordinates.any { coordinate.startsWith(it) }
                }
            check(offenders.isEmpty()) {
                buildString {
                    appendLine("MviDependencyBoundary: :mvi's production graph reaches forbidden dependencies:")
                    offenders.forEach { appendLine("  + $it") }
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

            artifacts.failures.forEach { failure ->
                error(
                    "MviDependencyBoundary: part of :mvi's graph did not resolve, so this check " +
                        "judged less than the whole graph: ${failure.message}",
                )
            }

            report
                .get()
                .asFile
                .apply {
                    parentFile.mkdirs()
                    writeText(seen.joinToString("\n", postfix = "\n"))
                }
        }
    }

// A module's `check` is where its own verdicts live, and an unrun boundary task is the
// "works but is never called" defect one layer down.
tasks.named("check") { dependsOn(verifyDependencyBoundary) }
