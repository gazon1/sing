@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.ai.tools.CreateTagInput
import com.singularity.todo.feature.ai.tools.CreateTagOutput
import com.singularity.todo.feature.ai.tools.CreateTagTool
import com.singularity.todo.feature.ai.tools.DeleteTagInput
import com.singularity.todo.feature.ai.tools.DeleteTagOutput
import com.singularity.todo.feature.ai.tools.DeleteTagTool
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tags.Tag as DomainTag
import com.singularity.todo.feature.tags.TagId
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write contract of [CreateTagTool] and [DeleteTagTool]: the call proposes,
 * the confirmation applies it, and **the refusal changes nothing**.
 *
 * ## Why the refusal is the case that matters here
 *
 * A tag is shared: every task that carries it points at it, so a tag that vanishes
 * takes the meaning with it. A tool that is classified as proposal-gated but writes
 * on its own would break the promise the whole surface makes: *the user's data
 * changes only after the user says yes*. The write is the easy half to see; the
 * refusal is the half that gets forgotten — a tool that proposes and then writes
 * anyway passes every test that only looks at the confirmation path.
 *
 * So this test drives the whole round trip and asserts on the state of the user's
 * data after each step — not on the tool's own report of what it did.
 *
 * ## Why `create_tag` is here
 *
 * `create_tag` was the last tool that wrote the user's data on its own, and it
 * was reclassified as proposal-gated by this change. A reclassification without
 * the contract behind it would leave the tool's promise unchecked in exactly the
 * place it is newest: the tag would appear in the user's list whether they said
 * yes or no, and only the proposal's own answer would say otherwise.
 */
@Tag("fast")
class TagProposalWriteContractTest {

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
        val entry = McpToolCatalog.catalog.single { it.name == "delete_tag" }
        assertEquals(
            WriteBehavior.PROPOSAL_WRITE,
            entry.writeBehavior,
            "delete_tag is a proposal-gated tool",
        )

        val create = McpToolCatalog.catalog.single { it.name == "create_tag" }
        assertEquals(
            WriteBehavior.PROPOSAL_WRITE,
            create.writeBehavior,
            "create_tag is a proposal-gated tool",
        )
    }

    // ── create_tag: the tag must not exist until the user says yes ─────────────

    @Test
    fun `create_tag proposes the tag and creates nothing`() = runTest {
        val output = decodeCreate(
            CreateTagTool(proposals, currentUser, clock).execute(CreateTagInput(name = "home")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertEquals("home", output.name, "the tool names the tag it proposed")
        assertTrue(
            tags.observeAll().first().isEmpty(),
            "no tag was created: ${tags.observeAll().first().map { it.name }}",
        )

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "the proposal holds one change")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `confirming a create_tag proposal creates the tag`() = runTest {
        val output = decodeCreate(
            CreateTagTool(proposals, currentUser, clock).execute(CreateTagInput(name = "home")),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val created = assertNotNull(tags.get(proposedTagId(output.proposalId)), "the tag exists")
        assertEquals("home", created.name, "the tag is the one that was proposed")
        assertNull(created.deletedAt, "the tag is not deleted")
    }

    @Test
    fun `declining a create_tag proposal creates nothing`() = runTest {
        val output = decodeCreate(
            CreateTagTool(proposals, currentUser, clock).execute(CreateTagInput(name = "home")),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "not a tag I want")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        assertTrue(
            tags.observeAll().first().isEmpty(),
            "no tag was created: ${tags.observeAll().first().map { it.name }}",
        )
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── delete_tag: the tag must survive the refusal ───────────────────────────

    @Test
    fun `delete_tag proposes the deletion and deletes nothing`() = runTest {
        val tag = seedTag("home")

        val output = decodeDelete(
            DeleteTagTool(proposals, currentUser, clock)
                .execute(DeleteTagInput(tagId = tag.id.value, reason = "no longer used")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        val stillThere = assertNotNull(tags.get(tag.id), "the tag is still there")
        assertNull(stillThere.deletedAt, "the tag is not deleted")

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "the proposal holds one change")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    /**
     * The tool proposes a deletion without reading the tag; the confirmation is
     * where the tag has to exist.
     *
     * The delete tools validate the id string and nothing else — they do not look
     * the entity up, so a deletion is proposed for a tag that is already gone. The
     * lookup happens on the way in, at the confirmation, where the entity is read
     * back and the deletion is applied to it.
     *
     * This is pinned here because it is the asymmetry a reader of the tool would
     * otherwise have to take on faith: the propose step and the apply step check
     * different things.
     */
    @Test
    fun `delete_tag proposes without reading the tag, and the confirmation is what needs it`() = runTest {
        val output = decodeDelete(
            DeleteTagTool(proposals, currentUser, clock)
                .execute(DeleteTagInput(tagId = "no-such-tag", reason = "no longer used")),
        )

        assertTrue(output.proposalCreated, "the tool proposed without reading the tag: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertNull(tags.get(TagId.fromString("no-such-tag")), "there is no tag to delete")
    }

    @Test
    fun `confirming a delete_tag proposal deletes the tag`() = runTest {
        val tag = seedTag("home")

        val output = decodeDelete(
            DeleteTagTool(proposals, currentUser, clock)
                .execute(DeleteTagInput(tagId = tag.id.value, reason = "no longer used")),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val deleted = assertNotNull(tags.get(tag.id), "the tag row is still readable")
        assertNotNull(deleted.deletedAt, "the confirmed deletion took the tag out of use")
    }

    @Test
    fun `declining a delete_tag proposal leaves the tag in place`() = runTest {
        val tag = seedTag("home")

        val output = decodeDelete(
            DeleteTagTool(proposals, currentUser, clock)
                .execute(DeleteTagInput(tagId = tag.id.value, reason = "no longer used")),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "keep it")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        val survivor = assertNotNull(tags.get(tag.id), "the tag survived the refusal")
        assertNull(survivor.deletedAt, "the tag was not deleted")
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private fun seedTag(name: String): DomainTag {
        val tag = DomainTag(
            id = TagId.generate(),
            name = name,
            color = 0xFF2196F3.toInt(),
            createdAt = clock.now(),
            updatedAt = clock.now(),
            userId = user,
        )
        tags.seed(tag)
        return tag
    }

    private fun decodeDelete(raw: String): DeleteTagOutput = Json.decodeFromString(DeleteTagOutput.serializer(), raw)

    private fun decodeCreate(raw: String): CreateTagOutput = Json.decodeFromString(CreateTagOutput.serializer(), raw)

    private suspend fun singleItemId(proposalId: String) =
        requireNotNull(proposals.watchProposal(ProposalId(proposalId)).first()?.items?.singleOrNull()).id

    /**
     * The id the tag will have if the user says yes.
     *
     * The tool does not return it — the proposal names the target, and an id in
     * the tool's answer would name a tag that does not exist yet — so the read
     * goes through the proposal.
     */
    private suspend fun proposedTagId(proposalId: String): TagId {
        val proposal = requireNotNull(proposals.watchProposal(ProposalId(proposalId)).first())
        return TagId.fromString(proposal.targetId)
    }
}
