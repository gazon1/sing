@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.ai.tools.CreateProjectInput
import com.singularity.todo.feature.ai.tools.CreateProjectOutput
import com.singularity.todo.feature.ai.tools.CreateProjectTool
import com.singularity.todo.feature.ai.tools.DeleteProjectInput
import com.singularity.todo.feature.ai.tools.DeleteProjectOutput
import com.singularity.todo.feature.ai.tools.DeleteProjectTool
import com.singularity.todo.feature.ai.tools.UpdateProjectInput
import com.singularity.todo.feature.ai.tools.UpdateProjectOutput
import com.singularity.todo.feature.ai.tools.UpdateProjectTool
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The write contract of the project tools that propose instead of writing.
 *
 * ## Why the refusal is the case that matters here
 *
 * A project is a container for work, so an unwanted one is worse than an unwanted
 * task. A tool that is classified as proposal-gated but writes on its own would
 * break the promise the whole surface makes: *the user's data changes only after the
 * user says yes*. The write is the easy half to see; the refusal is the half that
 * gets forgotten — a tool that proposes and then writes anyway passes every test
 * that only looks at the confirmation path.
 *
 * So this test drives the whole round trip and asserts on the state of the user's
 * data after each step — not on the tool's own report of what it did.
 *
 * ## The three tools
 *
 * - [CreateProjectTool] — the project does not exist until the user says yes.
 * - [UpdateProjectTool] — the model names the fields to change, and the tool reads
 *   the project first so it can propose only what really differs.
 * - [DeleteProjectTool] — the project must still be there when the user says no.
 */
@Tag("fast")
class ProjectProposalWriteContractTest {

    private val clock = FakeClock()
    private val user: UserId = TestUsers.DEFAULT

    private val proposals = FakeProposalRepository(clock)
    private val tasks = FakeTaskRepository()
    private val tags = FakeTagsRepository()
    private val checklist = FakeChecklistRepository()
    private val timeTracking = FakeTimeTrackingRepository(clock)
    private val notes = FakeNotesRepository()
    private val projectsFake = FakeProjectsRepository(FakeProfileAwareCurrentUser(user))
    private val deleteProject = DeleteProjectUseCase(projectsFake, tasks)

    private val currentUser = FakeProfileAwareCurrentUser(user)

    private val apply = ApplyProposalItemUseCase(
        proposals = proposals,
        tasks = tasks,
        notes = notes,
        projects = projectsFake,
        tags = tags,
        planner = ProposalPlanner(clock),
        dispatch = ProposalDispatch(tasks, tags, checklist, timeTracking, notes, projectsFake, deleteProject, clock),
    )

    // ── The catalogue and the behaviour must agree ──────────────────────────────

    @Test
    fun `the catalogue classifies the tools this test drives as proposal-gated`() {
        listOf("create_project", "update_project", "delete_project").forEach { name ->
            val entry = McpToolCatalog.catalog.single { it.name == name }
            assertEquals(
                WriteBehavior.PROPOSAL_WRITE,
                entry.writeBehavior,
                "$name is a proposal-gated tool",
            )
        }
    }

    // ── create_project: the project does not exist until the user says yes ──────

    @Test
    fun `create_project proposes a project and creates nothing`() = runTest {
        val output = decodeCreate(
            CreateProjectTool(proposals, currentUser, clock).execute(CreateProjectInput(name = "Garden")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertEquals("Garden", output.name, "the tool says what it proposed")
        assertTrue(liveProjects().isEmpty(), "nothing was created: ${liveProjects()}")

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "the proposal holds one change")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `confirming a create_project proposal creates the project`() = runTest {
        val output = decodeCreate(
            CreateProjectTool(proposals, currentUser, clock).execute(CreateProjectInput(name = "Garden")),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val created = liveProjects().singleOrNull()
        assertNotNull(created, "the project exists")
        assertEquals("Garden", created.name)
        assertEquals(user, created.userId)
    }

    @Test
    fun `declining a create_project proposal creates nothing`() = runTest {
        val output = decodeCreate(
            CreateProjectTool(proposals, currentUser, clock).execute(CreateProjectInput(name = "Garden")),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "not needed")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        assertTrue(liveProjects().isEmpty(), "nothing was created: ${liveProjects()}")
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── update_project: only what really differs is proposed ───────────────────

    @Test
    fun `update_project proposes a change and writes nothing`() = runTest {
        val project = seedProject("Home")

        val output = decodeUpdate(
            UpdateProjectTool(proposals, projectsFake, currentUser, clock)
                .execute(UpdateProjectInput(projectId = project.id.value, name = "House")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertEquals(
            "Home",
            projectsFake.get(project.id)?.name,
            "the project was not touched by the call",
        )

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "one field changed, so one change is proposed")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `update_project refuses a project that does not exist and proposes nothing`() = runTest {
        val output = decodeUpdate(
            UpdateProjectTool(proposals, projectsFake, currentUser, clock)
                .execute(UpdateProjectInput(projectId = "no-such-project", name = "House")),
        )

        assertFalse(output.proposalCreated, "no proposal was made: ${output.error}")
        assertNotNull(output.error, "the model is told why nothing was proposed")
        assertTrue(output.proposalId.isBlank(), "there is no proposal to find")
        assertTrue(liveProjects().isEmpty(), "nothing was written: ${liveProjects()}")
    }

    @Test
    fun `confirming an update_project proposal writes the change`() = runTest {
        val project = seedProject("Home")

        val output = decodeUpdate(
            UpdateProjectTool(proposals, projectsFake, currentUser, clock)
                .execute(UpdateProjectInput(projectId = project.id.value, name = "House")),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(
            "House",
            projectsFake.get(project.id)?.name,
            "the confirmed change is in the user's project",
        )
    }

    @Test
    fun `declining an update_project proposal leaves the project unchanged`() = runTest {
        val project = seedProject("Home")

        val output = decodeUpdate(
            UpdateProjectTool(proposals, projectsFake, currentUser, clock)
                .execute(UpdateProjectInput(projectId = project.id.value, name = "House")),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "not now")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(
            "Home",
            projectsFake.get(project.id)?.name,
            "the project is exactly as it was",
        )
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── delete_project: the project must survive the refusal ───────────────────

    @Test
    fun `delete_project proposes the deletion and deletes nothing`() = runTest {
        val project = seedProject("Home")

        val output = decodeDelete(
            DeleteProjectTool(proposals, currentUser, clock)
                .execute(DeleteProjectInput(projectId = project.id.value, reason = "no longer needed")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        val stillThere = assertNotNull(projectsFake.get(project.id), "the project is still there")
        assertFalse(stillThere.isDeleted, "the project is not deleted")

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "the proposal holds one change")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `confirming a delete_project proposal deletes the project`() = runTest {
        val project = seedProject("Home")

        val output = decodeDelete(
            DeleteProjectTool(proposals, currentUser, clock)
                .execute(DeleteProjectInput(projectId = project.id.value, reason = "no longer needed")),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val deleted = assertNotNull(projectsFake.get(project.id), "the project row is still readable")
        assertTrue(deleted.isDeleted, "the confirmed deletion took the project out of the user's list")
        assertTrue(liveProjects().isEmpty(), "the project is gone: ${liveProjects()}")
    }

    @Test
    fun `declining a delete_project proposal leaves the project in place`() = runTest {
        val project = seedProject("Home")

        val output = decodeDelete(
            DeleteProjectTool(proposals, currentUser, clock)
                .execute(DeleteProjectInput(projectId = project.id.value, reason = "no longer needed")),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "keep it")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        val survivor = assertNotNull(projectsFake.get(project.id), "the project survived the refusal")
        assertFalse(survivor.isDeleted, "the project was not deleted")
        assertEquals(1, liveProjects().size, "the project is still in the user's list")
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    /**
     * A project with no tasks.
     *
     * The delete use case refuses to delete a project that still has tasks, so a
     * project that carries one would never reach the deletion this test is about.
     */
    private fun seedProject(name: String): Project {
        val project = Project(
            id = ProjectId.generate(),
            name = name,
            color = 0xFF2196F3.toInt(),
            createdAt = clock.now(),
            updatedAt = clock.now(),
            userId = user,
        )
        projectsFake.seed(project)
        return project
    }

    /** The projects the user can see — the list an un-deleted project belongs to. */
    private suspend fun liveProjects(): List<Project> = projectsFake.observeAll().first()

    private fun decodeCreate(raw: String): CreateProjectOutput =
        Json.decodeFromString(CreateProjectOutput.serializer(), raw)

    private fun decodeUpdate(raw: String): UpdateProjectOutput =
        Json.decodeFromString(UpdateProjectOutput.serializer(), raw)

    private fun decodeDelete(raw: String): DeleteProjectOutput =
        Json.decodeFromString(DeleteProjectOutput.serializer(), raw)

    private suspend fun singleItemId(proposalId: String) =
        requireNotNull(proposals.watchProposal(ProposalId(proposalId)).first()?.items?.singleOrNull()).id
}
