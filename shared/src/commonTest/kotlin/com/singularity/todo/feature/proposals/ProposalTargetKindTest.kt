package com.singularity.todo.feature.proposals

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.logic.ProposalBuilder
import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalTargetKind
import com.singularity.todo.feature.proposals.domain.model.ProposedProject
import com.singularity.todo.feature.proposals.domain.model.ProposedTask
import com.singularity.todo.feature.proposals.domain.model.ProjectValue
import com.singularity.todo.feature.proposals.domain.model.TagValue
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.tasks.domain.model.TaskId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * A change says what it is about, and the proposal is addressed accordingly.
 *
 * ## Why this is a test
 *
 * The kind of target used to be a second declaration, written beside the change:
 * thirteen places typed `"TASK"`, `"NOTE"`, `"PROJECT"` or `"TAG"` next to a change
 * that already knew. Two declarations of one fact, and nothing kept them together —
 * a caller that wrote the wrong one wrote a proposal the confirmation screen never
 * finds, and the mistake was invisible until the user looked for a change that was
 * not there.
 *
 * So the change says what it is about, and the builder reads it from there. These
 * tests pin that: the declaration is where it says it is, and a caller that gets
 * it wrong cannot.
 */
@Tag("fast")
class ProposalTargetKindTest {

    @Test
    fun `a change about a task says it is about a task`() {
        assertEquals(
            ProposalTargetKind.Task,
            ProposalItemKind.SetTaskField(TaskValue.Title("Buy milk")).target.targetKind,
        )
        assertEquals(
            ProposalTargetKind.Task,
            ProposalItemKind.DeleteTask("it is done").target.targetKind,
        )
    }

    @Test
    fun `a change about a note says it is about a note`() {
        assertEquals(
            ProposalTargetKind.Note,
            ProposalItemKind.SetNoteField(NoteValue.Title("Agenda")).target.targetKind,
        )
        assertEquals(
            ProposalTargetKind.Note,
            ProposalItemKind.DeleteNote("it is a draft").target.targetKind,
        )
    }

    @Test
    fun `a change about a project says it is about a project`() {
        assertEquals(
            ProposalTargetKind.Project,
            ProposalItemKind.SetProjectField(ProjectValue.Name("Kitchen")).target.targetKind,
        )
        assertEquals(
            ProposalTargetKind.Project,
            ProposalItemKind.DeleteProject("it is empty").target.targetKind,
        )
    }

    @Test
    fun `a change about a tag says it is about a tag`() {
        assertEquals(
            ProposalTargetKind.Tag,
            ProposalItemKind.SetTagField(TagValue.Name("chore")).target.targetKind,
        )
        assertEquals(
            ProposalTargetKind.Tag,
            ProposalItemKind.DeleteTag("it is unused").target.targetKind,
        )
    }

    @Test
    fun `a creation says what it brings into being`() {
        // The creation used to say only "something is created". What it creates is
        // the target the proposal is addressed to, so the creation has to name it.
        assertEquals(
            ProposalTargetKind.Task,
            ProposalItemKind.CreateTask(proposedTask()).target.targetKind,
        )
        assertEquals(
            ProposalTargetKind.Project,
            ProposalItemKind.CreateProject(ProposedProject(name = "Kitchen")).target.targetKind,
        )
    }

    @Test
    fun `a change this build cannot read says it is about nothing in particular`() {
        assertEquals(
            ProposalTargetKind.Unknown("UNKNOWN"),
            ProposalItemKind.Unknown("""{"type":"ArchiveEverything"}""").target.targetKind,
            "a change this build cannot read is not about anything it can name",
        )
    }

    @Test
    fun `the builder takes the kind of target from the change`() {
        val (proposal, _) = ProposalBuilder.forTarget(
            kind = ProposalItemKind.DeleteProject("it is empty"),
            targetId = "project-1",
            userId = UserId.anonymous,
            source = ProposalSource.Agent,
            clock = Clock.System,
        )

        assertEquals(
            ProposalTargetKind.Project,
            proposal.targetKind,
            "the proposal is addressed to what its change is about",
        )
    }

    @Test
    fun `a change about a note cannot be proposed against a task`() {
        // The two are separate facts: the change says what it is about, and the id
        // says which one. A caller that mixes them would write a proposal the
        // confirmation screen does not show — so it fails loudly instead.
        val failure = assertFailsWith<IllegalArgumentException> {
            ProposalBuilder.forTask(
                kind = ProposalItemKind.DeleteNote("it is a draft"),
                taskId = TaskId.fromString("task-1"),
                userId = UserId.anonymous,
                source = ProposalSource.Agent,
                clock = Clock.System,
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("NOTE"),
            "the refusal names what the change is about: ${failure.message}",
        )
    }

    @Test
    fun `a proposal about two things at once is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ProposalBuilder.multiForTarget(
                items = listOf(
                    ProposalBuilder.item(ProposalItemKind.DeleteTask("it is done"), "task-1"),
                    ProposalBuilder.item(ProposalItemKind.DeleteProject("it is empty"), "project-1"),
                ),
                targetId = "task-1",
                userId = UserId.anonymous,
                source = ProposalSource.Agent,
                clock = Clock.System,
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("one thing"),
            "the refusal says what a proposal holds: ${failure.message}",
        )
    }

    private fun proposedTask() = ProposedTask(title = "Buy milk")
}
