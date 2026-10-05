package com.singularity.todo.arch

import com.singularity.todo.core.sync.DocType
import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every repository the `DELETED` pull handler dispatches to must delete *softly*.
 *
 * ## The defect this prevents
 *
 * `SyncBootstrapper` handles a server's `DELETED` event by calling `repo.delete(id)`
 * — one call per `DocType`, and nothing in the code checks what `delete` means. Today
 * all six implementations make it a soft delete: `TaskRepositoryImpl.delete` delegates
 * to `softDelete`, the rest call `softDeleteForUser` on their DAO. That is what keeps
 * the trashed row on the *receiving* device instead of destroying it, and therefore
 * what makes a delete reversible there at all.
 *
 * Nothing holds that property in place. The next synced type written with a genuine
 * hard delete removes the row — trash included — on every other device, and every
 * check still passes, because there is no check. The old comment claimed one:
 * "soft-delete applies if the entity supports it; repositories with hard delete are
 * ignored". No such branch existed, `deleteRemote` did not exist, and the interface
 * does not distinguish the two cases. A comment that promises a check reads as a
 * check to the next person, which is how the property came to be believed rather than
 * enforced. This test is that check.
 *
 * ## Why the source is read rather than the behaviour driven
 *
 * A behavioural test would have to stand up a real Room database, a real row, and a
 * real dispatch for each of the six types — and would still say nothing about the
 * seventh, the one this gate exists to catch. Reading the dispatch table means the
 * set of checked repositories is *derived from the code that does the deleting*, so
 * adding a `DocType` adds a case here automatically instead of needing a human to
 * remember. [every_doc_type_is_dispatched_from_a_recognised_repository] is what keeps
 * that derivation honest.
 *
 * ## Limits, stated rather than hidden
 *
 * The scanner matches a `softDelete…(` call inside the body of `delete`. A repository
 * that routed its soft delete through a differently-named private helper would be
 * reported — a false positive that names the file, which is cheap to read and cheap
 * to allow, and is the right way round to err. On the other axis the scanner errs
 * toward silence: a `delete` that is soft through some indirection the reader cannot
 * follow is reported as not soft, and a developer who reads the report and finds the
 * delete genuinely soft is looking at a gate that cried wolf. Both directions are
 * why the scanner runs over synthetic sources ([the_scanner_finds_a_soft_delete] and
 * its neighbours) rather than only over production: a gate nobody can test is a gate
 * nobody should trust, and a gate that cries wolf is worse than no gate, because
 * people learn to allowlist it.
 */
@Tag("fast")
class SyncedEntityDeleteIsSoftTest {

    @Test
    fun the_scanner_finds_a_soft_delete() {
        val source = """
            override suspend fun delete(id: NoteId): Result<Unit> = runCatchingCancellable {
                noteDao.softDeleteForUser(id.value, clock.now().toEpochMilliseconds(), uid)
            }
        """.trimIndent()
        assertTrue(deletesSoftly(source), "a softDeleteForUser call is the shape this gate accepts")
    }

    @Test
    fun the_scanner_flags_a_hard_delete() {
        val source = """
            override suspend fun delete(id: NoteId): Result<Unit> = runCatchingCancellable {
                noteDao.hardDelete(id.value)
            }
        """.trimIndent()
        assertTrue(!deletesSoftly(source), "a hard delete must be reported")
    }

    @Test
    fun the_scanner_reads_a_delete_that_delegates_to_soft_delete() {
        """`TaskRepositoryImpl` spells it as a one-line delegation; both are soft."""
        val source = "override suspend fun delete(id: TaskId): Result<Unit> = softDelete(id)"
        assertTrue(deletesSoftly(source))
    }

    @Test
    fun the_scanner_handles_an_expression_body() {
        // No braces at all after the signature. A brace matcher that *requires* an
        // opening one returns an empty body here, an empty body contains no soft
        // delete, and a real repository stops being checked.
        val source = "override suspend fun delete(id: TagId): Result<Unit> = tagDao.softDeleteForUser(id.value, 0, uid)"
        assertTrue(deletesSoftly(source))
    }

    @Test
    fun the_scanner_ignores_a_soft_delete_that_merely_appears_in_prose() {
        // The word in a comment must not stand in for the call. Otherwise a hard
        // delete whose body says "this is a soft delete" passes the gate, which is
        // precisely the failure mode the old comment had.
        val source = """
            override suspend fun delete(id: NoteId): Result<Unit> = runCatchingCancellable {
                // Mirrors TaskRepositoryImpl.softDelete so both paths converge.
                noteDao.hardDelete(id.value)
            }
        """.trimIndent()
        assertTrue(!deletesSoftly(source), "a comment mentioning softDelete is not a soft delete")
    }

    @Test
    fun the_scanner_reads_only_the_delete_body() {
        // `restore` is a different operation, and `softDelete` must not be found
        // through a neighbouring member that happens to sit below `delete`.
        val source = """
            override suspend fun delete(id: NoteId): Result<Unit> = runCatchingCancellable {
                noteDao.hardDelete(id.value)
            }

            override suspend fun restore(id: NoteId): Result<Unit> = runCatchingCancellable {
                noteDao.softDeleteForUser(id.value, 0, uid)
            }
        """.trimIndent()
        assertTrue(!deletesSoftly(source), "a soft restore does not make a hard delete soft")
    }

    @Test
    fun the_scanner_does_not_borrow_the_next_members_brace() {
        // The instance this pins: `delete` is `= softDelete(id)`, an expression with
        // no brace of its own, and the next member is a braced block. A reader that
        // searched the whole remainder for a `{` found *that* brace, returned the
        // following member as the body, and reported the one repository that soft
        // deletes by delegation as though it hard-deleted — the gate naming a correct
        // file as wrong, which is how a gate gets allowlisted.
        val source = """
            override suspend fun delete(id: TaskId): Result<Unit> = softDelete(id)

            override suspend fun softDelete(id: TaskId): Result<Unit> = runCatchingCancellable {
                taskDao.softDeleteForUser(id.value, 0, uid)
            }
        """.trimIndent()
        assertTrue(deletesSoftly(source), "an expression-bodied delete must not absorb the next member")
    }

    @Test
    fun the_dispatch_table_is_read_from_the_handler() {
        val source = """
            val outcome: Result<Unit> = when (event.entityType) {
                DocType.Task -> taskRepo.delete(TaskId.fromString(event.entityId))
                DocType.Note -> noteRepo.delete(NoteId.fromString(event.entityId))
            }
        """.trimIndent()
        assertEquals(
            mapOf("Task" to "taskRepo", "Note" to "noteRepo"),
            dispatchedDeletes(source),
        )
    }

    @Test
    fun a_multiline_delete_call_is_still_read() {
        // ktlint wraps the TimeEntry case across lines. A pattern that requires the
        // closing paren on the same line loses that row, and the type it names goes
        // unchecked — a formatting change silently disabling a gate.
        val source = """
            DocType.TimeEntry -> timeTrackingRepo.delete(
                TimeEntryId.fromString(event.entityId),
            )
        """.trimIndent()
        assertEquals(mapOf("TimeEntry" to "timeTrackingRepo"), dispatchedDeletes(source))
    }

    @Test
    fun the_repository_type_is_read_from_the_constructor() {
        val source = """
            class SyncBootstrapper(
                private val taskRepo: TaskRepository,
                private val timeTrackingRepo: TimeTrackingRepository,
            )
        """.trimIndent()
        assertEquals(
            mapOf("taskRepo" to "TaskRepository", "timeTrackingRepo" to "TimeTrackingRepository"),
            repositoryTypes(source),
        )
    }

    @Test
    fun every_doc_type_is_dispatched_from_a_recognised_repository() {
        val source = bootstrapperSource()
        val dispatched = dispatchedDeletes(source)
        val types = repositoryTypes(source)

        val unknown = dispatched.filterNot { (docType, field) -> types.containsKey(field) }
        assertTrue(
            unknown.isEmpty(),
            "these DELETED branches name a repository this gate cannot resolve, so their " +
                "delete is unchecked: $unknown",
        )

        assertEquals(
            DocType.entries.map { it.name }.toSet(),
            dispatched.keys,
            "the DELETED branch of SyncBootstrapper no longer covers exactly DocType. " +
                "A new doc type is either undeleted on receipt, or deleted by a " +
                "repository this gate does not know about.",
        )
    }

    @Test
    fun every_repository_the_delete_handler_calls_deletes_softly() {
        val source = bootstrapperSource()
        val dispatched = dispatchedDeletes(source)
        val types = repositoryTypes(source)
        val root = commonMainRoot()

        val checked = mutableListOf<String>()
        val hardDeleted = mutableListOf<String>()

        dispatched.forEach { (docType, field) ->
            val interfaceName = types.getValue(field)
            val impl = File(root, "com/singularity/todo")
                .walkTopDown()
                .filter { it.isFile && it.name == "$interfaceName" + "Impl.kt" }
                .firstOrNull()
            if (impl == null) {
                hardDeleted += "$docType -> $field ($interfaceName): no $interfaceName" +
                    "Impl.kt under commonMain, so the delete is unchecked"
                return@forEach
            }
            val body = deleteBody(impl.readText())
            if (body == null || !isSoftDelete(body)) {
                hardDeleted += "$docType -> $field (${impl.name})"
            } else {
                checked += "${impl.name} (via $field)"
            }
        }

        if (hardDeleted.isNotEmpty()) {
            fail(
                buildString {
                    appendLine(
                        "A repository the pull DELETED handler dispatches to does not " +
                            "delete softly. A hard delete removes the row — and the " +
                            "trash item — on every receiving device, irreversibly, and " +
                            "the delete can no longer be undone there:",
                    )
                    hardDeleted.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine(
                        "Checked and soft today: ${checked.joinToString(", ")}. " +
                            "If one of these is genuinely a hard delete by design, say so " +
                            "in the interface — do not let a comment stand in for the " +
                            "distinction. See #195.",
                    )
                },
            )
        }
    }

    // ── scanner ───────────────────────────────────────────────────────────────────

    /**
     * `DocType.X -> someRepo.delete(` pairs in [source], in source order.
     *
     * The receiver is captured, not the whole call: the gate's subject is *which
     * repository* answers the delete, and the arguments are already pinned by the
     * `Id.fromString` boundary the handler uses. Newlines are allowed between the
     * receiver and the call so a wrapped argument list still matches.
     */
    private fun dispatchedDeletes(source: String): Map<String, String> =
        Regex("""DocType\.(\w+)\s*->\s*(\w+)\s*\.\s*delete\s*\(""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * `private val name: Type` constructor properties in [source].
     *
     * Restricted to a type name ending in `Repository` so a `private val clock: Clock`
     * in the same class cannot be mistaken for a dispatch target, and so an unrelated
     * nested declaration cannot widen the table.
     */
    private fun repositoryTypes(source: String): Map<String, String> =
        Regex("""private\s+val\s+(\w+)\s*:\s*(\w*Repository)\b""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * The body of the first `override suspend fun delete(` in [source], or null when
     * there is no such member.
     *
     * Three shapes have to be read, and all three are in production today:
     *
     *  - `= runCatchingCancellable { … }` — five of the six repositories. The brace is
     *    *not* the first character after `=`, so a reader that expects it there takes
     *    the first line (`runCatchingCancellable {`), finds no soft delete in it, and
     *    reports a soft delete as hard. That was the first version, and it failed
     *    against the real code, not against a synthetic.
     *  - `= softDelete(id)` — `TaskRepositoryImpl`, an expression body with no brace at
     *    all. Read to the end of the line.
     *  - `= dao.hardDelete(id)` for the negative case.
     */
    private fun deleteBody(source: String): String? {
        val signature = Regex("""override\s+suspend\s+fun\s+delete\s*\(""")
            .find(source) ?: return null
        val afterSignature = source.substring(signature.range.last + 1)
        val equals = afterSignature.indexOf('=')
        if (equals < 0) return null
        val afterEquals = afterSignature.substring(equals + 1)
        val firstLine = afterEquals.lineSequence().first()
        val brace = firstLine.indexOf('{')
        // No brace on the signature's own line: the body is an expression and the
        // member ends with that line. The search is bounded to the first line on
        // purpose — an unbounded one finds the `{` of the *next* member, so
        // `= softDelete(id)` (TaskRepositoryImpl, a soft delete) was read as a block
        // belonging to whatever came next and reported as not soft.
        if (brace < 0) return firstLine.trim()
        return bracedBlock(afterEquals.substring(brace)) ?: afterEquals
    }

    /** [source] from its first `{` through the matching `}`, or null if unbalanced. */
    private fun bracedBlock(source: String): String? {
        var depth = 0
        source.forEachIndexed { index, c ->
            when (c) {
                '{' -> depth++

                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(0, index + 1)
                }
            }
        }
        return null
    }

    /**
     * True when [body] contains a real `softDelete…(` call.
     *
     * Comments and string literals are blanked before the search, because the failure
     * this gate exists to prevent arrived as a comment that described a check which
     * was not there. A gate that a comment can satisfy is the same gate, broken.
     */
    private fun deletesSoftly(source: String): Boolean =
        deleteBody(source)?.let { isSoftDelete(it) } == true

    private fun isSoftDelete(body: String): Boolean =
        SOFT_DELETE_CALL.containsMatchIn(stripped(body))

    private companion object {
        val SOFT_DELETE_CALL = Regex("""\bsoftDelete\w*\s*\(""")

        fun commonMainRoot(): String =
            System.getProperty("commonMain.root")
                ?: error(
                    "commonMain.root system property is not set — " +
                        "see the jvmTest task config in shared/build.gradle.kts",
                )

        fun bootstrapperSource(): String =
            File(
                commonMainRoot(),
                "com/singularity/todo/core/sync/SyncBootstrapper.kt",
            ).readText()

        /** [source] with comments and string literals replaced by spaces. */
        fun stripped(source: String): String {
            val out = StringBuilder(source)
            var index = 0
            while (index < source.length) {
                val rest = source.substring(index)
                when {
                    rest.startsWith("//") -> {
                        val end = rest.indexOf('\n').let { if (it < 0) rest.length else it }
                        blank(out, index, index + end)
                        index += end
                    }

                    rest.startsWith("/*") -> {
                        val end = rest.indexOf("*/").let {
                            if (it < 0) rest.length else it + 2
                        }
                        blank(out, index, index + end)
                        index += end
                    }

                    rest.startsWith("\"\"\"") -> {
                        val end = rest.indexOf("\"\"\"", 3).let {
                            if (it < 0) rest.length else it + 3
                        }
                        blank(out, index, index + end)
                        index += end
                    }

                    source[index] == '"' -> {
                        var end = index + 1
                        while (end < source.length && source[end] != '"') {
                            if (source[end] == '\\') end++
                            end++
                        }
                        blank(out, index, (end + 1).coerceAtMost(source.length))
                        index = end + 1
                    }

                    else -> index++
                }
            }
            return out.toString()
        }

        /** Overwrites [from]..<[to] in [out] with spaces, keeping every offset. */
        fun blank(out: StringBuilder, from: Int, to: Int) {
            for (i in from until to.coerceAtMost(out.length)) {
                if (out[i] != '\n') out[i] = ' '
            }
        }
    }
}
