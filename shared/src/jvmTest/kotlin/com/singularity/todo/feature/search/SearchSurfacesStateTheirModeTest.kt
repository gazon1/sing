@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.search

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.data.NotesRepositoryImpl
import com.singularity.todo.feature.search.data.InternalLinkRepositoryImpl
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.helpers.MutableClock
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
import kotlin.time.Clock

/**
 * Every search surface states the mode it searches in (F8), and answers in
 * that mode.
 *
 * The mode was a property of one screen, and the other surfaces went on
 * without one: the difference between them was silent, and the search screen
 * could answer a question the others could not. Now the mode is a parameter of
 * every surface's contract, and this test asks each of them the same question
 * in both modes — against a real SQLite, so that the index answers the word
 * mode the way it answers it in the application.
 *
 * See `docs/decisions/2026-10-23-the-mode-is-a-property-of-every-search-surface.md`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Tag("slow")
@Tag("slow:data")
class SearchSurfacesStateTheirModeTest {

    private val user = UserId("u1")

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var notes: NotesRepositoryImpl
    private lateinit var tasks: TaskRepositoryImpl
    private lateinit var links: InternalLinkRepositoryImpl
    private val clock = MutableClock()

    @BeforeTest
    fun setUp() = runTest {
        tempDir = Files.createTempDirectory("singularity-search-modes-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
        val currentUser = FakeProfileAwareCurrentUser(
            FakeAuthRepository(Session.Anonymous(user)),
            scope = backgroundScope,
        )
        notes = NotesRepositoryImpl(
            noteDao = db.noteDao(),
            textIndex = db.textIndex(currentUser),
            clock = clock,
            currentUser = currentUser,
            syncRepository = FakeSyncRepository(this),
            unitOfWork = FakeUnitOfWork(),
        )
        tasks = TaskRepositoryImpl(
            taskDao = db.taskDao(),
            textIndex = db.textIndex(currentUser),
            clock = Clock.System,
            currentUser = currentUser,
            syncRepository = FakeSyncRepository(this),
            dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
            unitOfWork = FakeUnitOfWork(),
        )
        links = InternalLinkRepositoryImpl(
            noteDao = db.noteDao(),
            taskDao = db.taskDao(),
            textIndex = db.textIndex(currentUser),
            currentUser = currentUser,
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `the note search answers in the mode it is given`() = runTest {
        notes.createWithContent(NoteId("n1"), "Airport transfer", "# body", "<p>body</p>")

        assertEquals(
            listOf("n1"),
            notes.search("air", SearchMode.Word).first().map { it.id.value },
            "the word mode finds the beginning of a word — that is the index's rule",
        )
        assertEquals(
            emptyList(),
            notes.search("port", SearchMode.Word).first().map { it.id.value },
            "the word mode does not find a word by its middle — that is what the contains mode is for",
        )
        assertEquals(
            listOf("n1"),
            notes.search("port", SearchMode.Contains).first().map { it.id.value },
            "the contains mode finds the term wherever it occurs",
        )
    }

    @Test
    fun `the task filter answers in the mode it is given`() = runTest {
        tasks.create(task("t1", title = "Airport transfer"))

        assertEquals(
            listOf("t1"),
            tasks.observeByFilter(TaskFilter.Search("air", SearchMode.Word)).first().map { it.id.value },
            "the word mode finds the beginning of a word",
        )
        assertEquals(
            emptyList(),
            tasks.observeByFilter(TaskFilter.Search("port", SearchMode.Word)).first().map { it.id.value },
            "the word mode does not find a word by its middle",
        )
        assertEquals(
            listOf("t1"),
            tasks.observeByFilter(TaskFilter.Search("port", SearchMode.Contains)).first().map { it.id.value },
            "the contains mode finds the term wherever it occurs",
        )
    }

    @Test
    fun `a task is searched by its title and its description in either mode`() = runTest {
        tasks.create(task("t1", title = "Trip", description = "Airport transfer"))

        assertEquals(
            emptyList(),
            tasks.observeByFilter(TaskFilter.Search("port", SearchMode.Word)).first().map { it.id.value },
            "the word mode does not find the middle of a word",
        )
        assertEquals(
            listOf("t1"),
            tasks.observeByFilter(TaskFilter.Search("port", SearchMode.Contains)).first().map { it.id.value },
            "the contains mode finds the term in the description as well as in the title",
        )
        assertTrue(
            tasks.observeByFilter(TaskFilter.Search("port", SearchMode.Contains)).first().isNotEmpty(),
            "the task is found by the surface it is searched by (F3)",
        )
    }

    @Test
    fun `the link autocomplete answers in the mode it is given`() = runTest {
        tasks.create(task("t1", title = "Trip", description = "Airport transfer"))

        assertEquals(
            emptyList(),
            links.searchTasks("port", SearchMode.Word).map { it.id.value },
            "the word mode does not find the middle of a word",
        )
        assertEquals(
            listOf("t1"),
            links.searchTasks("port", SearchMode.Contains).map { it.id.value },
            "the contains mode finds the term in the description as well as in the title",
        )
    }

    @Test
    fun `the notes of the contains mode come back newest first`() = runTest {
        notes.createWithContent(NoteId("n1"), "Airport transfer", "# body", "<p>body</p>")
        clock.advanceBy(1_000)
        notes.createWithContent(NoteId("n2"), "Airport transfer", "# body", "<p>body</p>")
        // The manual order of the notes is the list's own order, and the
        // list puts the note with the lower sort order first — the older one.
        // Without the sort the search would answer in that order, and the
        // answer would not be the index's.
        clock.advanceBy(1_000)
        notes.setSortOrder(NoteId("n2"), 5)

        assertEquals(
            listOf("n2", "n1"),
            notes.search("port", SearchMode.Contains).first().map { it.id.value },
            "the contains mode answers in the same order as the index does",
        )
    }

    private fun task(id: String, title: String, description: String? = null): Task = Task(
        id = TaskId(id),
        title = title,
        description = description,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        userId = user,
    )
}
