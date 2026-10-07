package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A write to a synced table is followed by a patch about it (the other direction of
 * `SyncWriteIsAtomicTest`).
 *
 * ## The hole this closes
 *
 * `SyncWriteIsAtomicTest` answers "is the patch inside the transaction as the row". It
 * cannot answer "is there a patch at all", because that question is about which methods
 * write and which do not — not about where a statement sits.
 *
 * That asymmetry is why the missing-patch bug ships. A method that writes a row and
 * forgets to enqueue looks exactly like a method that writes a row and deliberately does
 * not: both are a DAO call and no `syncRepository.enqueue`. There is no test in the
 * repository that tells them apart, and the class of bug is not hypothetical — it is what
 * #187 was, time tracking present on one platform and not the other, every suite green.
 *
 * ## Why the exemption is derived, not written down
 *
 * The methods that legitimately write without enqueueing are the **remote apply handlers**:
 * the row is being written *because* the server said so, and enqueueing it would push the
 * same document straight back. Those are exactly the `repo.upsert(...)` calls in
 * `SyncDocumentWriter.upsert` — so the exemption set is read from that file rather than
 * maintained beside it.
 *
 * That is the same decision `SyncWriteIsAtomicTest` makes for the receiver name, and for
 * the same reason: a hand-written list of method names goes stale silently, and the tree
 * it goes stale on still reports green. Two lists drifting apart in one session is enough
 * evidence; the third is derived instead.
 *
 * ## What is left over
 *
 * Two writes are not apply handlers and still do not enqueue, because what they write is
 * not a synced column: `setInheritedForProject` writes a denormalised inheritance flag
 * that the cascade recomputes from `parentId`, and `saveOutgoingLinks` writes internal
 * links, which no `DocType` describes. They are listed with the reason attached, because
 * the alternative — a rule with no exceptions for them — would either be wrong about them
 * or drive the author to add a fake enqueue.
 *
 * ## What this does not prove
 *
 * It is a lexical rule over method bodies: it sees DAO calls whose names start with a
 * write verb and an `enqueue` somewhere in the same method. A write performed by a helper
 * with a non-obvious name, or a field that becomes synced later without the method
 * changing, will pass. It is a ratchet against losing an enqueue in a refactor, not a
 * proof that every synced field is propagated.
 */
@Tag("fast")
class SyncedWriteEnqueuesTest {

    @Test
    fun `every write to a synced table enqueues a patch`() {
        val applyHandlers = applyHandlerNames()
        assertTrue(
            applyHandlers.isNotEmpty(),
            "no apply handlers were derived from SyncDocumentWriter.upsert — the exemption " +
                "set is empty, which would make this rule report every apply handler as a " +
                "missing patch. The path or the pattern has moved.",
        )

        val offenders = repositoryFiles().flatMap { file ->
            val source = file.readText()
            if (!hasSyncDependency(source)) return@flatMap emptyList()
            methods(source)
                .filter { WRITES_ROW_IN_BODY.containsMatchIn(it.body) }
                .filter { it.name !in applyHandlers }
                .filter { method -> NOT_SYNCED_WRITES.none { it.first == method.name } }
                .filter { !ENQUEUES_IN_BODY.containsMatchIn(it.body) }
                .map { "${file.name}:${it.line} ${it.name} writes a synced row with no enqueue" }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "These writes are not remote-apply handlers and do not enqueue, so the row " +
                    "reaches this device and the server never learns about it:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\n\nEither enqueue inside the same unitOfWork.write { … }, or — if the " +
                    "write is not a synced column — add it to NOT_SYNCED_WRITES with the " +
                    "reason. Do not leave it unremarked: a skipped write and a forgotten " +
                    "one look identical from here, and only one of them is a bug.",
            )
        }
    }

    @Test
    fun `the exemption set comes from the writer, not from a list`() {
        // The control for the derivation above: a writer that dispatches to `upsert` must
        // yield those method names, and a writer that dispatches to something else must not
        // make them exempt. A rule whose exemption set is empty passes this test and then
        // fails every real apply handler, which is a loud failure; a rule whose exemption
        // set is too wide passes silently, which is not.
        val derived = applyHandlerNamesIn(
            """
            class SyncDocumentWriter(private val taskRepo: TaskRepository) {
                suspend fun upsert(type: DocType, document: JsonObject) {
                    when (type) {
                        DocType.Task -> taskRepo.upsert(decode(document))
                        DocType.Note -> noteRepo.restore(decode(document))
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals2(setOf("upsert"), derived, "only repo.upsert( is a remote apply")
    }

    // ── derivation ────────────────────────────────────────────────────────────────

    private fun applyHandlerNames(): Set<String> = applyHandlerNamesIn(writerSource())

    /** Method names invoked as `<repo>.upsert(` inside the writer's dispatch. */
    private fun applyHandlerNamesIn(source: String): Set<String> =
        APPLY_CALL.findAll(source).map { it.groupValues[1] }.toSet()

    private fun writerSource(): String {
        val root = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see the jvmTest task config")
        val file = File(root, "com/singularity/todo/core/sync/SyncDocumentWriter.kt")
        assertTrue(file.isFile, "SyncDocumentWriter.kt is not where this test expects it: ${file.path}")
        return file.readText()
    }

    private fun repositoryFiles(): List<File> {
        val root = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see the jvmTest task config")
        return File(root, "com/singularity/todo/feature")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("RepositoryImpl.kt") }
            .toList()
    }

    private fun hasSyncDependency(source: String): Boolean =
        Regex("""private val \w+\s*:\s*SyncRepository""").containsMatchIn(source)

    /** A `fun name(...)` block, with the line it starts on so a finding can be jumped to. */
    private data class Method(val name: String, val line: Int, val body: String)

    private fun methods(source: String): List<Method> {
        val declaration = Regex("""(?m)^[ \t]*(?:override |private |internal |suspend )*fun (\w+)\s*\(""")
        val starts = declaration.findAll(source).map { it.range.first to it.groupValues[1] }.toList()
        return starts.mapIndexed { index, (offset, name) ->
            val end = starts.getOrNull(index + 1)?.first ?: source.length
            Method(name, source.take(offset).count { it == '\n' } + 1, source.substring(offset, end))
        }
    }

    private fun assertEquals2(expected: Set<String>, actual: Set<String>, message: String) {
        if (expected != actual) {
            fail("$message: expected <$expected> but was <$actual>")
        }
    }

    private companion object {
        val APPLY_CALL = Regex("""\w+Repo\.(upsert)\(""")

        val ENQUEUES_IN_BODY = Regex("""\.enqueue\(|enqueueFresh\(|enqueuePatch\(""")

        val WRITES_ROW_IN_BODY = Regex(
            """Dao\.(upsert|insert|update\w*|softDelete\w*|set\w*|patch\w*|delete\w*)\(""",
        )

        /**
         * Writes that are not synced columns.
         *
         * `setInheritedForProject` writes a denormalised flag recomputed from `parentId`;
         * the synced column is the parent itself, which has its own method and its own
         * enqueue. `saveOutgoingLinks` writes internal links, which no `DocType` describes
         * and which the server has no table for.
         *
         * Named with the reason inline rather than in a comment above the list, because a
         * reason separated from its entry is a reason nobody reads when the entry is
         * questioned.
         */
        val NOT_SYNCED_WRITES = setOf(
            "setInheritedForProject" to "denormalised from parentId, which has its own enqueue",
            "saveOutgoingLinks" to "internal links; no DocType describes them",
        )
    }
}
