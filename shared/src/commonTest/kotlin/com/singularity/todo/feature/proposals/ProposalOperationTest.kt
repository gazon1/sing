package com.singularity.todo.feature.proposals

import com.singularity.todo.feature.proposals.domain.logic.ProposalOperation
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The vocabulary the write tool speaks.
 *
 * ## Why this is a control for
 *
 * The tool takes an operation name and a value as text — the model types them. So
 * there is a place where a value the task cannot hold can still be offered, and the
 * question is what happens to it.
 *
 * The answer used to be "it becomes a proposal, and it fails at apply time, in front
 * of the user". It is now "it is refused where the model can still fix it" — the
 * reader is the same reader the proposal machinery uses ([TaskValue.read]), so a
 * value that does not read never reaches the database.
 *
 * That is why the case that used to be guarded here — an unreadable value reaching
 * the apply path — is guarded here instead: it never gets that far.
 */
@Tag("fast")
class ProposalOperationTest {

    @Test
    fun `a value that does not read is refused with the reason`() {
        val outcome = ProposalOperation.toItem("set_priority", "whenever", emptyList())

        val refused = assertIs<ProposalOperation.Outcome.UnreadableValue>(outcome)
        assertTrue(refused.reason.contains("whenever"), "the reason says what was wrong: ${refused.reason}")
    }

    @Test
    fun `a value that reads becomes the change it names`() {
        val outcome = ProposalOperation.toItem("set_priority", "high", emptyList())

        val proposed = assertIs<ProposalOperation.Outcome.Proposed>(outcome)
        assertEquals(
            ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
            proposed.kind,
            "the value is read the way the proposal machinery reads it",
        )
    }

    @Test
    fun `an operation that is not ours is refused`() {
        val outcome = ProposalOperation.toItem("set_everything", "x", emptyList())

        assertIs<ProposalOperation.Outcome.UnknownOperation>(outcome)
    }

    @Test
    fun `an operation without a value says the value is missing`() {
        val outcome = ProposalOperation.toItem("set_title", null, emptyList())

        assertIs<ProposalOperation.Outcome.MissingValue>(outcome)
    }

    @Test
    fun `a list operation with nothing in it proposes nothing`() {
        val outcome = ProposalOperation.toItem("add_tags", null, emptyList())

        assertIs<ProposalOperation.Outcome.EmptyChange>(outcome)
    }

    @Test
    fun `a list operation with entries proposes the change`() {
        val outcome = ProposalOperation.toItem("add_tags", null, listOf("home"))

        val proposed = assertIs<ProposalOperation.Outcome.Proposed>(outcome)
        assertEquals(ProposalItemKind.AddTags(listOf("home")), proposed.kind)
    }

    @Test
    fun `an empty date clears the due date`() {
        val outcome = ProposalOperation.toItem("set_due_date", "", emptyList())

        val proposed = assertIs<ProposalOperation.Outcome.Proposed>(outcome)
        assertEquals(
            ProposalItemKind.SetTaskField(TaskValue.DueDate(null)),
            proposed.kind,
            "an empty date is how the tools have always said 'no due date' — and a task " +
                "that can be given a date must be able to lose it",
        )
    }

    @Test
    fun `an empty time clears the due time`() {
        val outcome = ProposalOperation.toItem("set_due_time", "", emptyList())

        val proposed = assertIs<ProposalOperation.Outcome.Proposed>(outcome)
        assertEquals(ProposalItemKind.SetTaskField(TaskValue.DueTime(null)), proposed.kind)
    }
}
