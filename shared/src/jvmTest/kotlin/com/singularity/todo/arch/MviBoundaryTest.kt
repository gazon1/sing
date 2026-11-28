package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `:mvi` module's dependency boundary, as something a build can fail on.
 *
 * `docs/architecture/mvi-library-boundary.md` states the module's whole acceptance criterion in
 * one sentence: its `commonMain` names nothing of the application's. That claim is worth nothing
 * as prose — a module that grows one convenient import at a time compiles, passes its own
 * contract tests, and only stops being reusable the day someone else tries to consume it.
 *
 * The scan is lexical because it does not need to be anything more: an import is a line of text,
 * and every forbidden dependency would appear as one.
 *
 * `mvi.root` is handed over by the `jvmTest` task. It is deliberately *not* defaulted: a missing
 * root would make this scan pass over zero files, which is the failure mode
 * `CommonMainKonsistScope`'s own KDoc warns about — a scan of less code reports green exactly
 * like a scan of all of it.
 */
@Tag("slow")
@Tag("slow:konsist")
class MviBoundaryTest {
    private val root: File =
        System.getProperty("mvi.root")
            ?.let(::File)
            ?: error("mvi.root system property is not set — see the jvmTest task config in shared/build.gradle.kts")

    private val sources: List<File> by lazy {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val imports: List<Pair<File, String>> by lazy {
        sources.flatMap { file ->
            file.readLines()
                .filter { it.startsWith("import ") }
                .map { file to it.removePrefix("import ").trim() }
        }
    }

    @Test
    fun `the scan reads the module`() {
        // A scan over nothing is the defect this test exists to prevent, so it asserts its own
        // subject first. Five files is the runtime the boundary document lists.
        assertTrue(sources.isNotEmpty(), "no Kotlin source found under $root")
        assertTrue(imports.isNotEmpty(), "no import found under $root")
    }

    @Test
    fun `the runtime imports no application type`() {
        // `com.singularity.todo` is the application's package root; `:mvi` uses it for its own
        // package, so the allowed prefix is its own packages and nothing else.
        val own =
            listOf(
                "com.singularity.todo.core.ui.",
                "com.singularity.todo.mvi.",
            )
        val foreign =
            imports
                .filter { (_, fqn) -> fqn.startsWith("com.singularity.todo.") }
                .filterNot { (_, fqn) -> own.any { fqn.startsWith(it) } }

        assertEquals(
            emptyList(),
            foreign.map { (f, fqn) -> "${f.name}: $fqn" },
            ":mvi must not import the application — AppError, Either, CrashReportingPort and " +
                "com.singularity.todo.core.coroutines are the boundary this module exists to keep",
        )
    }

    @Test
    fun `the runtime imports none of the application's libraries`() {
        // Each of these is a decision the boundary document records as rejected, and every one
        // of them would arrive as a single plausible import.
        val forbidden =
            mapOf(
                "co.touchlab.kermit" to "Kermit — logging policy belongs to the consumer",
                "androidx.compose" to "Compose — a runtime has no UI",
                "io.insert-koin" to "Koin — DI is the application's",
                "androidx.room" to "Room — persistence is the application's",
                "androidx.navigation" to "Navigation — screens, not the runtime",
                "kotlinx.serialization" to "serialization — nothing here is serialized",
                "kotlinx.collections.immutable" to "immutable collections — not needed by the loop",
                "io.ktor" to "networking — not the runtime's",
                "ai.koog" to "Koog — feature/AI, never the core",
            )

        val found =
            imports.mapNotNull { (file, fqn) ->
                val hit = forbidden.entries.firstOrNull { fqn.startsWith(it.key) }
                hit?.let { (prefix, why) -> "${file.name}: $fqn — $why ($prefix)" }
            }

        assertEquals(emptyList(), found, ":mvi's dependency set is coroutines and lifecycle-viewmodel only")
    }

    @Test
    fun `the runtime imports no test library from production sources`() {
        // `kotlinx-coroutines-test` is a `commonTest` dependency. Reaching it from `commonMain`
        // would compile here and break every consumer's production classpath.
        val found =
            imports
                .filter { (_, fqn) -> fqn.startsWith("kotlinx.coroutines.test") }
                .map { (file, fqn) -> "${file.name}: $fqn" }

        assertEquals(emptyList(), found, "kotlinx-coroutines-test is test-only")
    }

    @Test
    fun `the runtime does not expose a defaulted or factory-built scope`() {
        // The contract: `MviViewModel` takes a caller-owned `CoroutineScope` and cancels its Job.
        // A default, a factory or a closeable-scope type would let a non-MVI component depend on
        // the library just to cancel a scope — the inversion the boundary document forbids.
        val declarations = sources.joinToString("\n") { it.readText() }

        assertTrue(
            !Regex("""scope\s*:\s*CoroutineScope\s*=""").containsMatchIn(declarations),
            "MviViewModel's scope must have no default — the caller owns it",
        )
        assertTrue(
            "AutoCloseableCoroutineScope" !in declarations,
            "the closeable scope type stays in :shared; :mvi accepts a plain CoroutineScope",
        )
        assertTrue(
            "createBackgroundScope" !in declarations && "reportingScope" !in declarations,
            "no scope factory: dispatcher policy and failure handling belong to the application",
        )
    }
}
