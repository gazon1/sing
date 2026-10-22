package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.links.InternalLinkSchemes
import com.singularity.todo.core.links.InternalLinks
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The backlink queries, run against a **real** Room/SQLite database.
 *
 * Nothing used to execute these statements: the only reader was the in-memory fake,
 * and the fake answered a backlink question with a substring test on the raw JSON
 * column. A fake that says "yes" where the database says "no" hides the defect
 * instead of catching it — so the SQL itself has to be exercised here.
 *
 * ## The rule the queries implement
 *
 * A row is a backlink of a target **iff** the `outgoing_links` JSON array of that
 * row holds the target's whole URL as one of its elements — same scheme, same
 * identifier, same case, character for character.
 *
 * The old predicate was `outgoing_links LIKE '%task://' || :taskId || '%'`, which
 * broke that rule in three ways at once:
 *
 * 1. `LIKE` is case-insensitive for ASCII — `task://ABC` found `task://abc`.
 * 2. `LIKE` reads `%` and `_` as wildcards, and the identifier grammar allows `_`.
 * 3. The pattern had no closing delimiter — `task://abcd` found `task://abc`.
 *
 * Each of those is a test below, because a rule that is not tested is a rule that
 * is only true until the next edit.
 */
@Tag("slow")
class BacklinkQueryTest {

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("backlink-query-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private suspend fun task(id: String, links: List<String>, userId: String = USER_ID, archived: Boolean = false) {
        db.taskDao().upsert(
            TaskEntity(
                id = id,
                title = id,
                description = null,
                projectId = null,
                dueDate = null,
                dueTime = null,
                startDate = null,
                startTime = null,
                endDate = null,
                endTime = null,
                accentColor = null,
                emoji = null,
                completedAt = null,
                archivedAt = if (archived) 1_000L else null,
                outgoingLinks = linksJson(links),
                createdAt = 1_000L,
                updatedAt = 1_000L,
                userId = userId,
            ),
        )
    }

    private suspend fun note(id: String, links: List<String>, userId: String = USER_ID, deleted: Boolean = false) {
        db.noteDao().upsert(
            NoteEntity(
                id = id,
                userId = userId,
                title = id,
                bodyMarkdown = null,
                bodyHtml = null,
                parentNoteId = null,
                archivedAt = null,
                outgoingLinks = linksJson(links),
                createdAt = 1_000L,
                updatedAt = 1_000L,
                deletedAt = if (deleted) 1_000L else null,
            ),
        )
    }

    /**
     * A database that holds one row of every shape the queries have to tell apart:
     * the exact link, a link of another user, an archived task, a deleted note, a
     * longer identifier and a value that carries a wildcard character.
     */
    private suspend fun seed() {
        task("t-plain", listOf(taskUrl("abc")))
        task("t-other-user", listOf(taskUrl("abc")), userId = OTHER_USER_ID)
        task("t-archived", listOf(taskUrl("abc")), archived = true)
        task("t-longer", listOf(taskUrl("abcd")))
        task("t-wildcard", listOf(taskUrl("a%c")))
        note("n-note", listOf(noteUrl("abc")))
        note("n-task", listOf(taskUrl("abc")))
        note("n-deleted", listOf(taskUrl("abc")), deleted = true)
        note("n-other-user", listOf(taskUrl("abc")), userId = OTHER_USER_ID)
    }

    private suspend fun backlinkTaskIds(url: String): List<String> =
        db.taskDao().getBacklinkTasks(url, USER_ID).map { it.id }

    private suspend fun backlinkNoteIds(url: String): List<String> =
        db.noteDao().getBacklinkNotes(url, USER_ID).map { it.id }

    private suspend fun notesLinkingToTaskIds(url: String): List<String> =
        db.noteDao().getNotesLinkingToTask(url, USER_ID).map { it.id }

    // ── The exact match ────────────────────────────────────────────────────────

    @Test
    fun `a stored task link is found by its exact url`() = runTest {
        seed()

        assertEquals(
            listOf("t-plain"),
            backlinkTaskIds(taskUrl("abc")),
            "the task that links to task://abc must be found by the query for task://abc",
        )
    }

    @Test
    fun `a stored note link is found by its exact url`() = runTest {
        seed()

        assertEquals(
            listOf("n-note"),
            backlinkNoteIds(noteUrl("abc")),
            "the note that links to note://abc must be found by the query for note://abc",
        )
    }

    @Test
    fun `a stored note link to a task is found by the task url`() = runTest {
        seed()

        assertEquals(
            listOf("n-task"),
            notesLinkingToTaskIds(taskUrl("abc")),
            "the note that links to task://abc must be found by the query for task://abc",
        )
    }

    // ── The ways the old LIKE match was wrong ──────────────────────────────────

    @Test
    fun `a stored link is not found by a different case`() = runTest {
        seed()

        assertTrue(
            backlinkTaskIds(taskUrl("ABC")).isEmpty(),
            "LIKE is case-insensitive for ASCII, so task://ABC must not find task://abc",
        )
        assertTrue(
            backlinkNoteIds(noteUrl("ABC")).isEmpty(),
            "LIKE is case-insensitive for ASCII, so note://ABC must not find note://abc",
        )
    }

    @Test
    fun `a stored link is not found by an underscore`() = runTest {
        seed()

        assertTrue(
            backlinkTaskIds(taskUrl("a_c")).isEmpty(),
            "LIKE reads _ as a wildcard, so task://a_c must not find task://abc",
        )
    }

    @Test
    fun `a stored link is not found by a longer identifier`() = runTest {
        seed()

        assertEquals(
            listOf("t-longer"),
            backlinkTaskIds(taskUrl("abcd")),
            "the pattern had no closing delimiter, so task://abcd must not find task://abc",
        )
    }

    @Test
    fun `the scheme is part of the match`() = runTest {
        seed()

        assertEquals(
            listOf("n-task"),
            backlinkNoteIds(taskUrl("abc")),
            "the row that holds task://abc is the match; the row that holds note://abc is not",
        )
        assertTrue(
            backlinkTaskIds(noteUrl("abc")).isEmpty(),
            "a stored task://abc must not be found by a query for the note abc",
        )
    }

    @Test
    fun `a wildcard character stays a literal character`() = runTest {
        seed()

        assertEquals(
            listOf("t-wildcard"),
            backlinkTaskIds(taskUrl("a%c")),
            "the stored value task://a%c holds a literal %, and the query finds exactly it",
        )
        assertTrue(
            backlinkTaskIds(taskUrl("abc")).none { it == "t-wildcard" },
            "the % of the queried url must not act as a wildcard",
        )
    }

    // ── The filters that were already there ───────────────────────────────────

    @Test
    fun `another user's link is not a backlink`() = runTest {
        seed()

        assertTrue(
            backlinkTaskIds(taskUrl("abc")).none { it == "t-other-user" },
            "the query is scoped to the current user",
        )
        assertTrue(
            notesLinkingToTaskIds(taskUrl("abc")).none { it == "n-other-user" },
            "the query is scoped to the current user",
        )
    }

    @Test
    fun `an archived task and a deleted note are not backlinks`() = runTest {
        seed()

        assertTrue(
            backlinkTaskIds(taskUrl("abc")).none { it == "t-archived" },
            "an archived task is not a backlink",
        )
        assertTrue(
            notesLinkingToTaskIds(taskUrl("abc")).none { it == "n-deleted" },
            "a deleted note is not a backlink",
        )
    }

    private companion object {
        const val USER_ID = "u1"
        const val OTHER_USER_ID = "u2"

        /** `task://<id>` — the whole link, built from the shared grammar. */
        fun taskUrl(id: String): String = InternalLinks.urlFor(InternalLinkSchemes.TASK, id)

        /** `note://<id>` — the whole link, built from the shared grammar. */
        fun noteUrl(id: String): String = InternalLinks.urlFor(InternalLinkSchemes.NOTE, id)

        /** The stored form of a link list: a JSON array of URL strings. */
        fun linksJson(links: List<String>): String =
            Json.encodeToString(ListSerializer(String.serializer()), links)
    }
}
