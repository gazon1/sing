@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.notes.data.NotesRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The persistence contract of a Knowledge article, against real SQLite.
 *
 * ## What is being pinned
 *
 * An article is a note attached to exactly one project or task, and one owner may
 * own many of them. The reads that answer "which articles does this owner have"
 * have to say the same thing the note model says, so the questions here are:
 *
 * - several articles of one owner are all there, and each is its own note;
 * - a legacy brief — a note written before the article contract existed — is still
 *   one, without anybody rewriting it;
 * - an ordinary attached note, a folder, a daily note and a template are not
 *   articles;
 * - the classification happens **before** the page is cut: an older article does not
 *   disappear behind newer ordinary notes;
 * - the read is scoped to the active profile and to the exact owner, and it is
 *   bounded;
 * - archived and deleted articles are not in the active library;
 * - the order is deterministic when two notes carry the same timestamp.
 *
 * ## Why real SQLite and not a fake
 *
 * The classification lives in the statement — it has to, or the page would be cut
 * before the articles were known. A fake that filtered in Kotlin would agree with
 * this test and still be wrong in production, so the test runs the query.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
@Tag("slow:data")
class KnowledgeArticleRepositoryTest {
    private val user = UserId("u1")
    private val other = UserId("u2")

    private lateinit var fixture: DatabaseTestFixture
    private lateinit var db: AppDatabase
    private lateinit var repo: NotesRepositoryImpl

    private val project = ProjectId.generate()
    private val task = TaskId.generate()

    @BeforeTest
    fun setUp() =
        runTest {
            fixture = DatabaseTestFixture.inMemory()
            db = fixture.database()
            val currentUser =
                FakeProfileAwareCurrentUser(
                    FakeAuthRepository(Session.Anonymous(user)),
                    scope = backgroundScope,
                )
            repo =
                NotesRepositoryImpl(
                    noteDao = db.noteDao(),
                    textIndex = db.textIndex(currentUser),
                    clock = Clock.System,
                    currentUser = currentUser,
                    syncRepository = FakeSyncRepository(this),
                    unitOfWork = FakeUnitOfWork(),
                )
            // The foreign keys are real here, so the rows the notes point at exist.
            db.projectDao().upsert(
                ProjectEntity(
                    id = project.value,
                    userId = user.value,
                    name = "Kitchen",
                    color = 0,
                    icon = null,
                    description = null,
                    createdAt = 0,
                    updatedAt = 0,
                    dueDate = null,
                    team = null,
                    externalId = null,
                    deletedAt = null,
                    parentId = null,
                ),
            )
            db.taskDao().upsert(
                TaskEntity(
                    id = task.value,
                    title = "Draft",
                    description = null,
                    projectId = project.value,
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
                    createdAt = 0,
                    updatedAt = 0,
                    userId = user.value,
                ),
            )
        }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `one owner may have several articles, and each is its own note`() =
        runTest {
            seed("article-a", projectId = project, createdAtMs = 1_000)
            seed("article-b", projectId = project, createdAtMs = 2_000)

            val articles = repo.watchKnowledgeArticlesForProject(project, 20).first()

            assertEquals(
                listOf("article-b", "article-a"),
                articles.map { it.id.value },
                "both articles are there, newest first",
            )
        }

    @Test
    fun `a legacy brief is an article, and nobody rewrote it to become one`() =
        runTest {
            // A brief written before the article contract existed: an attached note
            // whose body carries the provenance section.
            seed("legacy-brief", projectId = project, createdAtMs = 1_000)

            val articles = repo.watchKnowledgeArticlesForProject(project, 20).first()

            assertEquals(listOf("legacy-brief"), articles.map { it.id.value })
            assertEquals(NoteKind.Plain, articles.single().kind, "the note is still a plain note")
            assertTrue(
                articles.single().bodyHtml!!.contains("<h2>Sources</h2>"),
                "the body was not rewritten",
            )
        }

    @Test
    fun `an ordinary attached note is not an article`() =
        runTest {
            seed("logbook", projectId = project, createdAtMs = 1_000, withSources = false)

            val articles = repo.watchKnowledgeArticlesForProject(project, 20).first()

            assertTrue(articles.isEmpty(), "a plain note without the section is not an article")
        }

    @Test
    fun `a folder, a daily note and a template are not articles`() =
        runTest {
            seed("folder", projectId = project, createdAtMs = 1_000, isFolder = true)
            seed("daily", projectId = project, createdAtMs = 2_000, kind = NoteKind.Daily)
            seed("template", projectId = project, createdAtMs = 3_000, kind = NoteKind.Template)

            val articles = repo.watchKnowledgeArticlesForProject(project, 20).first()

            assertTrue(articles.isEmpty(), "only a plain, non-folder note can be an article")
        }

    @Test
    fun `an older article does not disappear behind newer ordinary notes`() =
        runTest {
            seed("article", projectId = project, createdAtMs = 1_000)
            repeat(60) { index ->
                seed(
                    "ordinary-$index",
                    projectId = project,
                    createdAtMs = 2_000L + index,
                    withSources = false,
                )
            }

            val articles = repo.watchKnowledgeArticlesForProject(project, 20).first()

            assertEquals(
                listOf("article"),
                articles.map { it.id.value },
                "the classification happens before the page is cut",
            )
        }

    @Test
    fun `the page is bounded`() =
        runTest {
            repeat(5) { index -> seed("article-$index", projectId = project, createdAtMs = 1_000L + index) }

            val articles = repo.watchKnowledgeArticlesForProject(project, 2).first()

            assertEquals(2, articles.size, "the read is bounded")
        }

    @Test
    fun `the order is deterministic when the timestamps match`() =
        runTest {
            seed("b-same", projectId = project, createdAtMs = 1_000)
            seed("a-same", projectId = project, createdAtMs = 1_000)

            val first = repo.watchKnowledgeArticlesForProject(project, 20).first()
            val second = repo.watchKnowledgeArticlesForProject(project, 20).first()

            assertEquals(listOf("a-same", "b-same"), first.map { it.id.value })
            assertEquals(first.map { it.id.value }, second.map { it.id.value }, "two reads agree")
        }

    @Test
    fun `archived and deleted articles are not in the active library`() =
        runTest {
            seed("archived", projectId = project, createdAtMs = 1_000, archivedAtMs = 5_000)
            seed("deleted", projectId = project, createdAtMs = 2_000, deletedAtMs = 5_000)
            seed("active", projectId = project, createdAtMs = 3_000)

            val articles = repo.watchKnowledgeArticlesForProject(project, 20).first()

            assertEquals(listOf("active"), articles.map { it.id.value })
        }

    @Test
    fun `another profile's articles are not read`() =
        runTest {
            seed("mine", projectId = project, createdAtMs = 1_000)
            seed(
                "theirs",
                projectId = project,
                createdAtMs = 2_000,
                userId = other,
            )

            val articles = repo.watchKnowledgeArticlesForProject(project, 20).first()

            assertEquals(listOf("mine"), articles.map { it.id.value })
        }

    @Test
    fun `a project's articles are not the articles of its tasks`() =
        runTest {
            seed("project-article", projectId = project, createdAtMs = 1_000)
            seed("task-article", taskId = task, createdAtMs = 2_000)

            val byProject = repo.watchKnowledgeArticlesForProject(project, 20).first()
            val byTask = repo.watchKnowledgeArticlesForTask(task, 20).first()

            assertEquals(listOf("project-article"), byProject.map { it.id.value })
            assertEquals(listOf("task-article"), byTask.map { it.id.value })
        }

    @Test
    fun `a note attached to a project and to a task is in neither library`() =
        runTest {
            // The note model says a note hangs on a project or on a task, never on
            // both, and the write path refuses the pair. A row that says otherwise
            // can still arrive from a remote document, so the reads answer for the
            // owner it names alone: an article under two owners would answer two
            // questions with the same note.
            seed("both-owners", projectId = project, taskId = task, createdAtMs = 1_000)

            val byProject = repo.watchKnowledgeArticlesForProject(project, 20).first()
            val byTask = repo.watchKnowledgeArticlesForTask(task, 20).first()

            assertTrue(byProject.isEmpty(), "the project does not own a note that hangs on a task")
            assertTrue(byTask.isEmpty(), "the task does not own a note that hangs on a project")
        }

    @Test
    fun `the statement and the code agree on what carries the section`() =
        runTest {
            val bodies =
                listOf(
                    // The heading, in the forms the app writes it.
                    Body("<h2>Sources</h2><p>https://example.com</p>", null, true),
                    Body("<h1>Sources</h1>", null, true),
                    Body("<h2 class=\"lead\">Sources</h2>", null, true),
                    Body("<h2> Sources </h2>", null, true),
                    Body("<h2>Sources of truth</h2>", null, false),
                    // The word has to be one word: a rule that deleted the whitespace
                    // would call this the heading "Sources".
                    Body("<h2>Source s</h2>", null, false),
                    // The forms the brief shape accepted before the rule moved here.
                    Body("<p><strong>Sources</strong></p>", null, true),
                    Body("<b>Sources</b>", null, true),
                    Body("<h2>\nSources\n</h2>", null, true),
                    // The closing tag has to be a whole one: `</header>` and
                    // `</blockquote>` start like a heading and like a bold element,
                    // and they are neither.
                    Body("<h3>Sources</h3>", null, true),
                    Body("<h6>Sources</h6>", null, true),
                    Body("<header>Sources</header>", null, false),
                    Body("<blockquote>Sources</blockquote>", null, false),
                    Body(null, "## Sources\n\n- https://example.com\n", true),
                    Body(null, "# Sources\n", true),
                    Body(null, "###### Sources\n", true),
                    Body(null, "##Sources\n", true),
                    Body(null, "**Sources**\n", true),
                    Body(null, "intro\n**Sources**\n- url\n", true),
                    Body(null, "#\nSources\n", true),
                    Body(null, "# Source s\n", false),
                    Body(null, "**Source s**\n", false),
                    // A mention is prose, and prose is not a section.
                    Body("<p>Sources</p>", null, false),
                    Body("<h2>Summary</h2><p>text</p>", null, false),
                    Body("<p>the sources are unknown</p>", null, false),
                    Body(null, "see **Sources** below\n", false),
                    Body(null, "C# Sources\n", false),
                    Body(null, "# Data Sources\n", false),
                    Body(null, "Sources\n", false),
                    Body(null, "## Sources and notes\n", false),
                    Body(null, "potential sources include the docs\n", false),
                    // A run of spaces is padding of any length: the rule deletes the
                    // whitespace inside a line, so how long the run is does not
                    // matter, and the statement and the code answer the same.
                    Body("<h2>" + " ".repeat(100) + "Sources</h2>", null, true),
                    Body("<h2>" + " ".repeat(1_000) + "Sources</h2>", null, true),
                    Body(null, "##" + " ".repeat(100) + "Sources\n", true),
                    Body(null, "##" + " ".repeat(1_000) + "Sources\n", true),
                    // The Markdown body keeps its lines, so a label is a line of its
                    // own and the text around it stays text.
                    Body(null, "intro\n## Sources\n- url\n", true),
                    Body(null, "intro\n## Sources\n", true),
                )
            bodies.forEachIndexed { index, body ->
                seed(
                    "body-$index",
                    projectId = project,
                    createdAtMs = 1_000L + index,
                    bodyHtml = body.html,
                    bodyMarkdown = body.markdown,
                )
                assertEquals(
                    body.carriesSection,
                    KnowledgeArticle.carriesSources(body.html, body.markdown),
                    "the code's answer for ${body.html} / ${body.markdown}",
                )
            }

            // The page is asked for more bodies than it holds: this question is
            // about the classification, and a page that cut the list would hide
            // the bodies the two forms of the rule disagree about.
            val articles = repo.watchKnowledgeArticlesForProject(project, bodies.size + 1).first()

            assertEquals(
                bodies.indices
                    .filter { bodies[it].carriesSection }
                    .map { "body-$it" }
                    .sorted(),
                articles.map { it.id.value }.sorted(),
                "the statement classifies exactly what the code classifies",
            )
        }

    /** A body, and what the two forms of the rule have to answer about it. */
    private data class Body(val html: String?, val markdown: String?, val carriesSection: Boolean)

    @Test
    fun `a markdown-only brief is an article too`() =
        runTest {
            seed(
                "markdown-brief",
                projectId = project,
                createdAtMs = 1_000,
                bodyHtml = null,
                bodyMarkdown = "## Sources\n\n- https://example.com\n",
            )

            val articles = repo.watchKnowledgeArticlesForProject(project, 20).first()

            assertEquals(listOf("markdown-brief"), articles.map { it.id.value })
        }

    @Test
    fun `a non-positive limit is refused rather than becoming unbounded`() =
        runTest {
            val failure = runCatching { repo.watchKnowledgeArticlesForProject(project, -1) }
            assertTrue(failure.isFailure, "a negative limit is not a bound")
        }

    /** Seeds a note row directly, with the kind and attachments the reads filter on. */
    private suspend fun seed(
        id: String,
        projectId: ProjectId? = null,
        taskId: TaskId? = null,
        createdAtMs: Long,
        kind: NoteKind = NoteKind.Plain,
        isFolder: Boolean = false,
        withSources: Boolean = true,
        bodyHtml: String? = "<h2>Sources</h2><p>https://example.com</p>",
        bodyMarkdown: String? = null,
        archivedAtMs: Long? = null,
        deletedAtMs: Long? = null,
        userId: UserId = user,
    ) {
        val createdAt = Instant.fromEpochMilliseconds(createdAtMs)
        db.noteDao().upsert(
            NoteEntity(
                id = id,
                userId = userId.value,
                title = id,
                bodyMarkdown = bodyMarkdown,
                bodyHtml = if (withSources) bodyHtml else "<p>ordinary note</p>",
                isFolder = isFolder,
                kind = kind,
                parentNoteId = null,
                projectId = projectId?.value,
                taskId = taskId?.value,
                createdAt = createdAtMs,
                updatedAt = createdAtMs,
                archivedAt = archivedAtMs,
                deletedAt = deletedAtMs,
            ),
        )
    }
}
