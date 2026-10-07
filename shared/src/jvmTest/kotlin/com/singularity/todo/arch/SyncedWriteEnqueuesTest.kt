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
    fun `an unscoped write is guarded, or takes its identity from the current user`() {
        // The canonical pipeline's first step, in the shape that is decidable from source.
        //
        // `GenericUserScopedRepository`'s KDoc states the sequence as
        // assertCanWrite → dao.upsert → enqueue, which reads as though implementations miss
        // the guard or have it in the wrong place. Neither is true: most of the 37 methods
        // that write a synced row take the owner *in the query* —
        // `softDeleteForUser(id, ts, uid)`, `setPinnedForUser(…, uid)` — and need no guard,
        // because the scope is in the WHERE clause. A rule reading "writes and enqueues,
        // therefore must guard" reports twenty of them and is wrong twenty times.
        //
        // What is left after discounting the scoped calls is decidable: an unscoped write
        // must be preceded by the guard, or build its row from the ambient current user —
        // which is what a `create` does by construction.
        //
        // The order is checked, not just the presence. `GUARD_BEFORE_WRITE` matches the two
        // as a sequence, because a guard *after* the write is a cross-user write that has
        // already happened, and a presence check cannot tell the two apart.
        val offenders = repositoryFiles().flatMap { file ->
            val source = file.readText()
            if (!hasSyncDependency(source)) return@flatMap emptyList()
            // The apply handlers write unscoped with no guard, and correctly so: the row is
            // being written *because* the server sent it, and the owner comes from the
            // document. Exempt for the same reason as everywhere else — derived, not listed.
            val applyHandlers = applyHandlerNames()
            methods(source)
                .filterNot { it.name in applyHandlers }
                .filter { method -> UNSCOPED_WRITE.containsMatchIn(method.body) }
                .filterNot { method -> GUARD_BEFORE_WRITE.containsMatchIn(method.body) }
                .filterNot { method -> AMBIENT_IDENTITY.containsMatchIn(method.body) }
                .map { "${file.name}:${it.line} ${it.name} writes unscoped with no guard" }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "These writes carry no owner in the query, no cross-user guard, and no ambient " +
                    "identity to take one from:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\n\nEither the query takes the current user's id (`…ForUser(…, uid)`), or " +
                    "the method guards with `currentUser.assertCanWrite(…)` **before** the " +
                    "write, or it builds the row from `currentUser.scopedUserId`. A guard " +
                    "after the write is a cross-user write that has already happened.",
            )
        }
    }

    @Test
    fun `a method that writes twice enqueues twice`() {
        // The partial-patch case. Both rules above ask whether an enqueue *exists* in a
        // method, so a method that writes two synced rows and enqueues one passes them —
        // and the row it forgot reaches this device and never reaches the server, which
        // shows up as divergence on the second device, long after the merge.
        //
        // Not a violation today: measured across the 37 write methods, none has more writes
        // than enqueues. The one with two of each is `TagGroupRepositoryImpl.delete`, which
        // enqueues the group and its released members.
        val applyHandlers = applyHandlerNames()
        val notSynced = NOT_SYNCED_WRITES.map { it.first }.toSet()
        val offenders = repositoryFiles().flatMap { file ->
            val source = file.readText()
            if (!hasSyncDependency(source)) return@flatMap emptyList()
            methods(source)
                .filterNot { it.name in applyHandlers || it.name in notSynced }
                .map { it to WRITES_ROW_IN_BODY.findAll(it.body).count() }
                .filter { (_, writes) -> writes > 1 }
                .filter { (method, writes) -> ENQUEUES_IN_BODY.findAll(method.body).count() < writes }
                .map {
                    "${file.name}:${it.first.line} ${it.first.name} writes ${it.second} rows " +
                        "and enqueues fewer"
                }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "These methods write more synced rows than they enqueue:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\n\nEach row needs its own patch, inside the same unitOfWork.write { … }. " +
                    "A method that writes two and enqueues one converges only on this device.",
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

        /** A write that carries no owner: the row's identity comes from the entity. */
        val UNSCOPED_WRITE = Regex(
            """Dao\.(upsert|insert|patch)\(""",
        )

        /**
         * The guard, *before* the write. Matched as a sequence rather than as a presence
         * check, because a guard after the write is the failure this exists to catch and
         * "contains assertCanWrite" cannot tell the two apart.
         */
        val GUARD_BEFORE_WRITE = Regex(
            """assertCanWrite\([\s\S]*?Dao\.(upsert|insert|patch)\(""",
        )

        /** Taking the owner from the ambient current user, which is a create by construction. */
        val AMBIENT_IDENTITY = Regex("""scopedUserId|currentUser""")

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
