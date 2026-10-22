package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.core.search.TextMatchRange
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
 * The index says where the word is.
 *
 * ## Why a real SQLite
 *
 * The position of a match is the answer of the index, and the index is
 * SQLite's `unicode61` tokenizer and FTS4's `offsets()`. Neither of them can
 * be faked: a fake would have to answer with a rule of its own, and the rule
 * would be a second answer to a question only the index can answer.
 *
 * So the position is asserted where the index is — over a real database.
 *
 * ## What is asserted
 *
 * 1. the row the index returns carries the position of the match;
 * 2. the position is in the *characters* of the text, not in its bytes — a
 *    title with an accent is where the two answers part company;
 * 3. a record that matched in its description carries no range in its title.
 */
@Tag("slow")
class FtsMatchOffsetsTest {

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-fts-offsets-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `the row carries the position of the match`() = runTest {
        seed(task(id = "t1", title = "Architecture of the house"))

        val row = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("arch"))).first().single()

        val ranges = FtsOffsets.ranges(
            row.offsets,
            listOf(FtsColumn(SearchColumn.TITLE, row.task.title)),
        )

        assertEquals(listOf(TextMatchRange(SearchColumn.TITLE, 0, 12)), ranges)
        assertEquals(
            "Architecture",
            row.task.title.substring(ranges.single().start, ranges.single().end),
        )
    }

    @Test
    fun `the position of a match in an accented title is in characters`() = runTest {
        // "Café au lait" — the accent is two bytes, so the byte offset and the
        // character offset of the match are not the same number.
        seed(task(id = "t1", title = "Café au lait"))

        val row = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("cafe"))).first().single()

        val ranges = FtsOffsets.ranges(
            row.offsets,
            listOf(FtsColumn(SearchColumn.TITLE, row.task.title)),
        )

        assertEquals(listOf(TextMatchRange(SearchColumn.TITLE, 0, 4)), ranges)
        assertEquals(
            "Café",
            row.task.title.substring(ranges.single().start, ranges.single().end),
        )
    }

    @Test
    fun `the position of a match after an accented character is in characters`() = runTest {
        // "Ünicode plan" — the word stands after a two-byte character.
        seed(task(id = "t1", title = "Ünicode plan"))

        val row = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("plan"))).first().single()

        val ranges = FtsOffsets.ranges(
            row.offsets,
            listOf(FtsColumn(SearchColumn.TITLE, row.task.title)),
        )

        assertEquals(
            "plan",
            row.task.title.substring(ranges.single().start, ranges.single().end),
        )
    }

    @Test
    fun `a record that matched in its description carries no range in its title`() = runTest {
        seed(task(id = "t1", title = "The plan", description = "an architecture of the house"))

        val row = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("architecture"))).first().single()

        val ranges = FtsOffsets.ranges(
            row.offsets,
            listOf(
                FtsColumn(SearchColumn.TITLE, row.task.title),
                FtsColumn(SearchColumn.DESCRIPTION, row.task.description.orEmpty()),
            ),
        )

        assertTrue(ranges.none { it.column == SearchColumn.TITLE }, "the title does not carry the word")
        assertEquals(
            "architecture",
            row.task.description.orEmpty().substring(
                ranges.single { it.column == SearchColumn.DESCRIPTION }.start,
                ranges.single { it.column == SearchColumn.DESCRIPTION }.end,
            ),
        )
    }

    private suspend fun seed(entity: TaskEntity) {
        db.taskDao().upsert(entity)
    }

    private fun task(id: String, title: String, description: String? = null): TaskEntity = TaskEntity(
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
        userId = "u1",
    )
}
