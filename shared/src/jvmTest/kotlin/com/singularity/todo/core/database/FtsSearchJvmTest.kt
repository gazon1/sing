package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.search.TextMatchQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full-text index, against a real SQLite.
 *
 * The fakes cannot say anything about this: the whole point of the index is
 * that SQLite answers a `MATCH` from a dictionary of words rather than by
 * reading every row. A test that runs against the fakes would pass whether the
 * index existed or not.
 *
 * What is asserted here is the behaviour a user can see:
 *
 * 1. a task is found by the beginning of a word in its title;
 * 2. a task is found by the beginning of a word in its description;
 * 3. another user's task is not found;
 * 4. a task that is renamed stops being found under the old name — the index
 *    follows the row, because Room's triggers keep it in step;
 * 5. the same holds for notes, projects and tags.
 */
@Tag("slow")
class FtsSearchJvmTest {

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-fts-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private fun task(
        id: String,
        title: String,
        description: String? = null,
        userId: String = "u1",
    ) = TaskEntity(
        id = id,
        title = title,
        description = description,
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
        archivedAt = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        userId = userId,
    )

    @Test
    fun `a task is found by the beginning of a word in its title`() = runTest {
        db.taskDao().upsert(task(id = "t1", title = "Write the quarterly report"))

        val found = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("quarter"))).first()

        assertEquals(listOf("t1"), found.map { it.task.id })
    }

    @Test
    fun `a task is found by the beginning of a word in its description`() = runTest {
        db.taskDao().upsert(task(id = "t1", title = "Numbers", description = "Quarterly figures"))

        val found = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("quarter"))).first()

        assertEquals(listOf("t1"), found.map { it.task.id })
    }

    @Test
    fun `another user's task is not found`() = runTest {
        db.taskDao().upsert(task(id = "t1", title = "Write the quarterly report"))
        db.taskDao().upsert(task(id = "t2", title = "Write the quarterly report", userId = "u2"))

        val found = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("quarter"))).first()

        assertEquals(listOf("t1"), found.map { it.task.id })
    }

    @Test
    fun `a renamed task is no longer found under its old name`() = runTest {
        val dao = db.taskDao()
        val index = db.ftsDao()
        dao.upsert(task(id = "t1", title = "Write the quarterly report"))
        assertEquals(1, index.watchTasks("u1", TextMatchQuery(listOf("quarterly"))).first().size)

        dao.upsert(task(id = "t1", title = "Write the annual report"))

        assertTrue(
            index.watchTasks("u1", TextMatchQuery(listOf("quarterly"))).first().isEmpty(),
            "the index must follow the row, not keep the old text",
        )
        assertEquals(1, index.watchTasks("u1", TextMatchQuery(listOf("annual"))).first().size)
    }

    @Test
    fun `a note is found by the beginning of a word in its title`() = runTest {
        db.noteDao().upsert(
            NoteEntity(
                id = "n1",
                userId = "u1",
                title = "Ideas for the launch",
                bodyMarkdown = null,
                bodyHtml = null,
                parentNoteId = null,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                deletedAt = null,
                archivedAt = null,
            ),
        )

        val found = db.ftsDao().watchNotes("u1", TextMatchQuery(listOf("laun"))).first()

        assertEquals(listOf("n1"), found.map { it.note.id })
    }

    @Test
    fun `a project is found by the beginning of a word in its name`() = runTest {
        db.projectDao().upsert(
            ProjectEntity(
                id = "p1",
                userId = "u1",
                name = "House renovation",
                color = 0,
                icon = null,
                description = null,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                dueDate = null,
                team = null,
                deletedAt = null,
                parentId = null,
                externalId = null,
            ),
        )

        val found = db.ftsDao().watchProjects("u1", TextMatchQuery(listOf("reno"))).first()

        assertEquals(listOf("p1"), found.map { it.project.id })
    }

    @Test
    fun `a tag is found by the beginning of a word in its name`() = runTest {
        db.tagDao().upsert(
            TagEntity(
                id = "g1",
                userId = "u1",
                name = "Deep work",
                color = 0,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                deletedAt = null,
            ),
        )

        val found = db.ftsDao().watchTags("u1", TextMatchQuery(listOf("deep"))).first()

        assertEquals(listOf("g1"), found.map { it.tag.id })
    }

    @Test
    fun `letters outside ascii are searchable`() = runTest {
        db.taskDao().upsert(task(id = "t1", title = "Купить молоко"))

        val found = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("мол"))).first()

        assertEquals(listOf("t1"), found.map { it.task.id })
    }
}
