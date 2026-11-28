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
