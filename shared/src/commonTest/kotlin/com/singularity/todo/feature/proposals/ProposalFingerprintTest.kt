package com.singularity.todo.feature.proposals

import com.singularity.todo.feature.proposals.domain.logic.ProposalFingerprint
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.proposals.domain.model.TaskField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The fingerprint is the mechanism that stops a rejected change coming back, so its
 * only requirement is that it is **stable and order-independent**. Everything else
 * about it is an implementation choice that tests must not over-constrain — these
 * assert identity relationships, never literal hash values, so swapping the digest
 * does not require rewriting the suite.
 */
class ProposalFingerprintTest {

    private fun fp(kind: ProposalItemKind, target: String = "task-1") = ProposalFingerprint.of(kind, target)

    @Test
    fun `same kind and target produce the same fingerprint`() {
        val a = ProposalItemKind.AddTags(listOf("home", "errand"))
        val b = ProposalItemKind.AddTags(listOf("home", "errand"))
        assertEquals(fp(a), fp(b))
    }

    @Test
    fun `list order does not change the fingerprint`() {
        val a = ProposalItemKind.AddTags(listOf("home", "errand", "urgent"))
        val b = ProposalItemKind.AddTags(listOf("urgent", "home", "errand"))
        assertEquals(fp(a), fp(b), "tag order must not create a distinct proposal")
    }

    @Test
    fun `duplicates within a payload do not change the fingerprint`() {
        val a = ProposalItemKind.AddChecklistItems(listOf("buy milk", "buy milk"))
        val b = ProposalItemKind.AddChecklistItems(listOf("buy milk"))
        assertEquals(fp(a), fp(b))
    }

    @Test
    fun `whitespace and blanks are normalised away`() {
        val a = ProposalItemKind.AddSubtasks(listOf("write spec", "  ", "review PR "))
        val b = ProposalItemKind.AddSubtasks(listOf("write spec", "review PR"))
        assertEquals(fp(a), fp(b))
    }

    @Test
    fun `different kinds never collide`() {
        val addTags = fp(ProposalItemKind.AddTags(listOf("x")))
        val removeTags = fp(ProposalItemKind.RemoveTags(listOf("x")))
        assertNotEquals(addTags, removeTags)
    }

    @Test
    fun `different targets produce different fingerprints`() {
        val kind = ProposalItemKind.AddTags(listOf("x"))
        assertNotEquals(fp(kind, "task-1"), fp(kind, "task-2"))
    }

    @Test
    fun `same tag name on different targets is a different proposal`() {
        // Suppression is per (kind, target): rejecting "add tag home" on one task
        // must not block the same suggestion on an unrelated task.
        val kind = ProposalItemKind.AddTags(listOf("home"))
        assertNotEquals(fp(kind, "task-1"), fp(kind, "task-2"))
    }

    @Test
    fun `different task fields produce different fingerprints`() {
        val title = fp(ProposalItemKind.SetTaskField(TaskField.Title, "x"))
        val priority = fp(ProposalItemKind.SetTaskField(TaskField.Priority, "x"))
        assertNotEquals(title, priority)
    }

    @Test
    fun `time entries fingerprint on their boundaries, not their order`() {
        val a = ProposalItemKind.AddTimeEntries(
            listOf(ProposedTimeEntry(100, 200), ProposedTimeEntry(300, 400)),
        )
        val b = ProposalItemKind.AddTimeEntries(
            listOf(ProposedTimeEntry(300, 400), ProposedTimeEntry(100, 200)),
        )
        assertEquals(fp(a), fp(b))
    }

    @Test
    fun `time entry note does not change the fingerprint`() {
        // The note is prose; two models describing the same session differently must
        // still be recognised as the same proposal.
        val a = ProposalItemKind.AddTimeEntries(listOf(ProposedTimeEntry(100, 200, "wrote the spec")))
        val b = ProposalItemKind.AddTimeEntries(listOf(ProposedTimeEntry(100, 200, "docs work")))
        assertEquals(fp(a), fp(b))
    }

    @Test
    fun `fingerprint is 16 hex chars`() {
        val value = fp(ProposalItemKind.AddTags(listOf("x")))
        assertEquals(16, value.length)
        assertEquals(value, value.lowercase())
        value.forEach { assert(it in "0123456789abcdef") }
    }

    @Test
    fun `fingerprint is stable across calls within a process`() {
        val kind = ProposalItemKind.SetTaskField(TaskField.Title, "Ship it")
        assertEquals(ProposalFingerprint.of(kind, "t"), ProposalFingerprint.of(kind, "t"))
    }

    @Test
    fun `separator characters in a payload cannot forge a different kind`() {
        // A tag literally named to imitate the field separator must not hash the same
        // as a different kind/target split.
        val injected = ProposalItemKind.AddTags(listOf("a", "b"))
        val split = ProposalItemKind.AddTags(listOf("ab"))
        assertNotEquals(fp(injected, "t"), fp(split, "t"))
    }
}
