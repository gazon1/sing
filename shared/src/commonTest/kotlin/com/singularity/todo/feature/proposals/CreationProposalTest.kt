@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.proposals

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.logic.ProposalFingerprint
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProjectValue
import com.singularity.todo.feature.proposals.domain.model.ProposalTargetKind
import com.singularity.todo.feature.proposals.domain.model.ProposedNote
import com.singularity.todo.feature.proposals.domain.model.ProposedProject
import com.singularity.todo.feature.proposals.domain.model.ProposedTask
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * The creations and the field changes the write path gained.
 *
 * ## What is new here
 *
 * A creation is a change like any other: it is proposed, the user decides, and
 * the thing comes into being with the id the proposal named. A project can be
 * changed, because the vocabulary grew a shape for it. A task's tags can be
 * *set*, not only added to and taken from.
 *
 * ## The properties that matter
 *
 * - The thing that comes into being is the thing the proposal talked about: the
 *   id in the proposal is the id in the database.
 * - A creation that cannot be parsed does not half-apply: the item stays pending
 *   and the reason is in the result.
 * - "Set the tags" replaces the set. It does not add to it.
 */
@Tag("fast")
class CreationProposalTest {

    private val clock = FakeClock()
    private val user = TestUsers.DEFAULT

    private val proposals = FakeProposalRepository(clock)
    private val tasks = FakeTaskRepository()
    private val tags = FakeTagsRepository()
    private val checklist = FakeChecklistRepository()
    private val timeTracking = FakeTimeTrackingRepository(clock)
    private val notes = FakeNotesRepository()
    private val projectsFake = FakeProjectsRepository(FakeProfileAwareCurrentUser(user))
    private val projects: ProjectsRepository = projectsFake
    private val deleteProject = DeleteProjectUseCase(projects, tasks)

    private val useCase = ApplyProposalItemUseCase(
        proposals = proposals,
        tasks = tasks,
        notes = notes,
        projects = projects,
        tags = tags,
        planner = ProposalPlanner(clock),
        dispatch = ProposalDispatch(tasks, tags, checklist, timeTracking, notes, projects, deleteProject, clock),
    )

    // ── The creations ──────────────────────────────────────────────────────────

    @Test
    fun `a confirmed creation brings the task into being with the id the proposal named`() = runTest {
        val futureId = TaskId.generate()
        val itemId = seedProposal(
            kind = ProposalItemKind.CreateTask(
                ProposedTask(
                    title = "Buy groceries",
                    priority = TaskPriority.High,
                    dueDate = LocalDate.parse("2026-09-15"),
                ),
            ),
            target = futureId.value,
        )

        val result = useCase.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val created = tasks.tasks.value[futureId.value]
        assertNotNull(created, "the task exists")
        assertEquals("Buy groceries", created.title)
        assertEquals(futureId, created.id)
        assertEquals(user, created.userId)
    }

    @Test
    fun `a confirmed creation brings the project into being`() = runTest {
        val futureId = ProjectId.generate()
        val itemId = seedProposal(
            kind = ProposalItemKind.CreateProject(ProposedProject(name = "Kitchen", color = 0xFF112233.toInt())),
            target = futureId.value,
        )

        val result = useCase.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val created = projects.get(futureId)
        assertNotNull(created, "the project exists")
        assertEquals("Kitchen", created.name)
    }

    @Test
    fun `a confirmed creation brings the note into being`() = runTest {
        val futureId = NoteId.generate()
        val itemId = seedProposal(
            kind = ProposalItemKind.CreateNote(ProposedNote(title = "Agenda", bodyHtml = "<p>Agenda</p>")),
            target = futureId.value,
        )

        val result = useCase.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val created = notes.get(futureId)
        assertNotNull(created, "the note exists")
        assertEquals("Agenda", created.title)
    }

    @Test
    fun `a creation with a blank title does not create anything`() = runTest {
        val futureId = TaskId.generate()
        val itemId = seedProposal(
            kind = ProposalItemKind.CreateTask(ProposedTask(title = "   ")),
            target = futureId.value,
        )

        val result = useCase.confirm(itemId, user)
        assertTrue(result.isFailure, "the confirmation failed")
        assertTrue(
            tasks.tasks.value[futureId.value] == null,
            "nothing was created",
        )
    }

    @Test
    fun `a confirmed creation brings the priority it named`() = runTest {
        val futureId = TaskId.generate()
        val itemId = seedProposal(
            kind = ProposalItemKind.CreateTask(ProposedTask(title = "Urgent thing", priority = TaskPriority.High)),
            target = futureId.value,
        )

        val result = useCase.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(TaskPriority.High, tasks.tasks.value[futureId.value]?.priority)
    }

    // ── The field changes ──────────────────────────────────────────────────────

    @Test
    fun `a confirmed project field change rewrites the project`() = runTest {
        val project = Project(
            id = ProjectId.generate(),
            name = "Old name",
            color = 0,
            createdAt = clock.now(),
            updatedAt = clock.now(),
            userId = user,
        )
        projectsFake.seed(project)

        val itemId = seedProposal(
            kind = ProposalItemKind.SetProjectField(ProjectValue.Name("New name")),
            target = project.id.value,
        )

        val result = useCase.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals("New name", projects.get(project.id)?.name)
    }

    @Test
    fun `a confirmed tag set replaces the tags rather than adding to them`() = runTest {
        val task = Task(
            id = TaskId.generate(),
            title = "Tagged",
            tags = listOf(TagId("old")),
            userId = user,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        tasks.seed(task)
        // The change names tags, not ids, and a name that names no tag resolves to
        // nothing — so the tag has to exist before the change can be applied.
        tags.seed(
            tag(id = "new", name = "new"),
        )

        val itemId = seedProposal(
            kind = ProposalItemKind.SetTaskTags(listOf("new")),
            target = task.id.value,
        )

        val result = useCase.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val tagsAfter = tasks.tasks.value[task.id.value]?.tags.orEmpty().map { it.value }
        assertEquals(listOf("new"), tagsAfter)
    }

    @Test
    fun `a confirmed due time change rewrites the task`() = runTest {
        val task = Task(
            id = TaskId.generate(),
            title = "Timed",
            userId = user,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        tasks.seed(task)

        val itemId = seedProposal(
            kind = ProposalItemKind.SetTaskField(TaskValue.DueTime(LocalTime.parse("14:30"))),
            target = task.id.value,
        )

        val result = useCase.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(14, tasks.tasks.value[task.id.value]?.dueTime?.hour)
        assertEquals(30, tasks.tasks.value[task.id.value]?.dueTime?.minute)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /**
     * A tag fixture.
     *
     * The domain `Tag` and JUnit's `@Tag` are different types with the same
     * name, so the domain one is not imported: `org.junit.jupiter.api.Tag`
     * above is the annotation this class carries, and the class it names is
     * spelled out where it is needed. The neighbouring
     * `ApplyProposalItemUseCaseTest` resolves the same collision the same way.
     */
    private fun tag(id: String, name: String) =
        com.singularity.todo.feature.tags.Tag(
            id = TagId(id),
            name = name,
            color = 0,
            userId = user,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )

    /** Seeds a one-item proposal for [kind] and returns the item id. */
    private suspend fun seedProposal(kind: ProposalItemKind, target: String): ProposalItemId {
        val proposalId = ProposalId.generate()
        val item = ProposalItem(
            id = ProposalItemId.generate(),
            proposalId = proposalId,
            kind = kind,
            targetId = target,
            humanSummary = "summary",
            status = ProposalItemStatus.Pending,
            fingerprint = ProposalFingerprint.of(kind, target),
        )
        proposals.save(
            AiProposal(
                id = proposalId,
                targetKind = ProposalTargetKind.Task,
                targetId = target,
                userId = user,
                source = ProposalSource.Agent,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                items = listOf(item),
            ),
        )
        return item.id
    }
}
