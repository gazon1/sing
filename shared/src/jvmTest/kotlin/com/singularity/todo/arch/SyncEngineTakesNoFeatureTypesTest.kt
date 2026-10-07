package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The sync engine takes no feature type (ADR `2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`).
 *
 * ## The failure this exists to prevent
 *
 * Resolving a lost race (#203) needed the per-type document writer, so `SyncEngine` took it
 * as a constructor argument. That closed a cycle:
 *
 * ```
 * TaskRepository → SyncRepository → SyncEngine → SyncDocumentWriter → TaskRepository
 * ```
 *
 * Neither edge is wrong on its own — the writer is a table of repositories, and a
 * repository tells the server about itself by enqueueing through the engine. Koin resolved
 * it by recursing until the stack ran out, as a `StackOverflowError` whose stack named no
 * application line at all: the repeating unit was four Koin frames, so the deepest named
 * source line was arbitrary and pointed at lines that do not exist.
 *
 * ## Why the compiler did not catch it
 *
 * `koin-compiler-plugin` validates the graph at build time and stayed silent, because the
 * definition that closed the loop was written as `{ get<SyncDocumentWriter>() }` — a lambda
 * it cannot analyse across a module boundary. That is a standing property of the plugin, not
 * a one-off: the same blindness let `BackgroundJobCatalog` close a second cycle on Desktop.
 *
 * So this is checked here, statically, where the compiler is blind: **a parameter of
 * `SyncEngine` may not name a type from `com.singularity.todo.feature`.** A deferred
 * `() -> …` provider satisfies it, because the repository graph is resolved at a point
 * where those repositories already exist.
 *
 * ## What it does not cover
 *
 * A cycle that does not pass through `SyncEngine` — `BackgroundWorkScheduler` reached one
 * that way, and it was caught by the resolution test, not by this rule. The rule is here
 * because this one is cheap and because the ADR's decision is specifically "the engine
 * holds no repositories"; a rule that pretended to cover every cycle in the graph would
 * be covering none of them.
 */
@Tag("fast")
class SyncEngineTakesNoFeatureTypesTest {

    @Test
    fun `the sync engine's constructor names no feature type`() {
        val source = engineSource()
        val featureTypes = constructorParameterTypes(source).filter { it.startsWith("com.singularity.todo.feature") }

        if (featureTypes.isNotEmpty()) {
            fail(
                "SyncEngine takes feature types: ${featureTypes.joinToString()}.\n\n" +
                    "That closes TaskRepository → SyncRepository → SyncEngine → … → " +
                    "TaskRepository, which Koin resolves by exhausting the stack — and " +
                    "koin-compiler-plugin does not see it, because a `{ get<T>() }` " +
                    "definition is not analysable across a module boundary.\n\n" +
                    "Take a provider (`() -> T`) and resolve it where it is used. See ADR " +
                    "2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine.",
            )
        }
    }

    @Test
    fun `the rule can see a feature type when one is there`() {
        // A guard that has never fired is indistinguishable from one that is not looking.
        // Synthetic source, so the answer is known before it is asked for.
        val offenders = constructorParameterTypes(
            """
            internal class SyncEngine(
                private val shadowDao: ShadowDao,
                private val writer: com.singularity.todo.feature.tasks.domain.port.TaskRepository,
            ) : Clock
            """.trimIndent(),
        ).filter { it.startsWith("com.singularity.todo.feature") }

        assertTrue(
            offenders.size == 1 && offenders.single().endsWith("TaskRepository"),
            "expected the one feature parameter, got $offenders",
        )
    }

    @Test
    fun `a provider parameter is not a feature parameter`() {
        // The shape the fix takes. If this failed, the rule would forbid the only correct
        // answer and the honest response would be to weaken it.
        val offenders = constructorParameterTypes(
            """
            internal class SyncEngine(
                private val writerProvider: () -> com.singularity.todo.core.sync.SyncDocumentWriter,
            ) : Clock
            """.trimIndent(),
        ).filter { it.startsWith("com.singularity.todo.feature") }

        assertTrue(offenders.isEmpty(), "a deferred provider was read as a feature parameter: $offenders")
    }

    // ── source ─────────────────────────────────────────────────────────────────────

    private fun engineSource(): String {
        val root = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see the jvmTest task config")
        val file = File(root, "com/singularity/todo/core/sync/SyncEngine.kt")
        assertTrue(file.isFile, "SyncEngine.kt is not where this test expects it: ${file.path}")
        return file.readText()
    }

    /**
     * Types named by the primary constructor's parameters.
     *
     * Reads the `class SyncEngine(` header and the lines up to the `)` that closes it, then
     * takes whatever appears after the last `:` on each parameter line — which is the
     * declared type, fully qualified or not. Names are returned as written, so the caller
     * matches `com.singularity.todo.feature` against a qualified name and short names are
     * simply not flagged; a short name cannot be resolved to a package by text alone, and
     * guessing would be worse than missing it.
     */
    private fun constructorParameterTypes(source: String): List<String> {
        val header = Regex("""class\s+SyncEngine\s*\(""").find(source) ?: return emptyList()
        val lines = source.substring(header.range.last + 1).split("\n")
        return lines.takeWhile { it.trim() != ")" && !it.trim().startsWith(")") }
            .mapNotNull { line ->
                val afterColon = line.substringAfterLast(":", "")
                val type = afterColon.substringBefore(",").substringBefore("=").trim()
                type.takeIf { it.isNotEmpty() && it != "*" && !it.startsWith("//") }
            }
            .map { if (it.startsWith("com.")) it else "$it" }
    }
}