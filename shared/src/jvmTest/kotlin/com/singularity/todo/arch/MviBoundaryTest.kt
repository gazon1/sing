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
        val foreign = foreignImports(imports)

        assertEquals(
            emptyList(),
            foreign,
            ":mvi must not import the application — AppError, Either, CrashReportingPort and " +
                "com.singularity.todo.core.coroutines are the boundary this module exists to keep",
        )
    }

    @Test
    fun `the allow-list names this module's types, not its whole package`() {
        // The allow-list was the package prefix `com.singularity.todo.core.ui.`, which treats
        // every type in that package as the library's own. `:mvi` and `:shared` share the package
        // by design (the boundary document keeps package names unchanged), so
        // `AppMviDiagnostics`, `DraftMviViewModel` and `DraftState` live in the same package and
        // are *application* types. A prefix rule accepts all three.
        //
        // A rule that only ever sees the correct tree proves nothing about the imports it has to
        // refuse — see ADR `2026-10-05-no-direct-dispatchers-rule-was-a-no-op`, where a rule that
        // could not fire for any input shipped registered and green.
        val applicationTypes =
            listOf(
                "com.singularity.todo.core.ui.AppMviDiagnostics",
                "com.singularity.todo.core.ui.DraftMviViewModel",
                "com.singularity.todo.core.ui.DraftState",
                "com.singularity.todo.core.ui.DraftUiState",
                "com.singularity.todo.core.ui.DraftUiEvent",
            )

        assertEquals(
            applicationTypes,
            foreignImports(applicationTypes.map { FAKE_SOURCE to it }),
            "an application type in the shared package must be refused, or the allow-list is a " +
                "package prefix again",
        )
    }

    @Test
    fun `the allow-list accepts this module's own types`() {
        // The control for the test above: a rule that refuses everything is as useless as one
        // that accepts everything. These are the runtime's published types — every one must pass.
        assertEquals(
            emptyList(),
            foreignImports(LIBRARY_TYPES.map { FAKE_SOURCE to it }),
            "the runtime's own types must be allowed, including the ones in the shared package",
        )
    }

    @Test
    fun `the allow-list accepts a nested declaration of its own type`() {
        // `MviIntent` is a top-level interface today, but the rule has to survive a type moving
        // under an enclosing declaration: `EventBus.Companion` is the same file as `EventBus`.
        assertEquals(
            emptyList(),
            foreignImports(listOf(FAKE_SOURCE to "com.singularity.todo.core.ui.EventBus.Companion")),
            "a nested type is owned when its enclosing type is",
        )
    }

    /**
     * The application-side imports among [candidates], as `file: fqn`.
     *
     * The allow-list is an explicit set of *type* names, not a package prefix. `:mvi` and
     * `:shared` share `com.singularity.todo.core.ui` on purpose, so a prefix cannot tell the
     * runtime from the adapter that binds it to the application — and the adapter is exactly the
     * thing this module must not depend on.
     */
    private fun foreignImports(candidates: List<Pair<File, String>>): List<String> {
        val ownPrefix = "com.singularity.todo.mvi."
        return candidates
            .filter { (_, fqn) -> fqn.startsWith("com.singularity.todo.") }
            .filterNot { (_, fqn) -> fqn.startsWith(ownPrefix) }
            .filterNot { (_, fqn) ->
                // A nested type is owned when its enclosing type is: `Markers.MviIntent` and
                // `IntentActions.Companion` are the same file's declarations.
                LIBRARY_TYPES.any { own -> fqn == own || fqn.startsWith("$own.") }
            }
            .map { (file, fqn) -> "${file.name}: $fqn" }
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

    private companion object {
        /**
         * Every type `:mvi` publishes, and nothing else.
         *
         * Kept as names rather than a package prefix on purpose: see
         * `the allow-list is this module's own files, not its whole package`. Adding a public
         * type to the runtime means adding it here, which is the point — the list is the
         * published surface, and a new name on it should be a deliberate act.
         */
        val LIBRARY_TYPES: Set<String> =
            setOf(
                "com.singularity.todo.core.ui.MviIntent",
                "com.singularity.todo.core.ui.MviEvent",
                "com.singularity.todo.core.ui.EventBus",
                "com.singularity.todo.core.ui.IntentActions",
                "com.singularity.todo.core.ui.MviViewModel",
                "com.singularity.todo.core.ui.MviDiagnostics",
                "com.singularity.todo.core.ui.MviFailure",
                "com.singularity.todo.core.ui.MviLogLevel",
                "com.singularity.todo.core.ui.NoOpMviDiagnostics",
            )

        /** A file stand-in for a hypothetical import; only its name reaches the message. */
        val FAKE_SOURCE: File = File("Hypothetical.kt")
    }
}
