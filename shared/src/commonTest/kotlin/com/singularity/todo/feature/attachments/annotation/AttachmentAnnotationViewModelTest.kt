package com.singularity.todo.feature.attachments.annotation

import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.annotation.AnchorResolution
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotation
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationId
import com.singularity.todo.core.attachments.annotation.TextRange
import kotlinx.coroutines.test.advanceUntilIdle
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeAttachmentAnnotationRepository
import com.singularity.todo.core.coroutines.testScope
import kotlinx.coroutines.launch
import com.singularity.todo.test.helpers.testVm
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The panel's state machine: loading the document, creating, editing, deleting, and — the
 * one that matters — keeping a stale note visible instead of dropping it.
 *
 * Fakes, not mocks: the repository is a value-holder, so a mock would only assert that the
 * ViewModel calls what the test already told it to call.
 *
 * `fast`: no database, no file, no Compose harness — the whole feature under test is
 * state reduction over a `MutableStateFlow`.
 */
@Tag("fast")
class AttachmentAnnotationViewModelTest {

    private val attachment = AttachmentId.fromString("att_1")
    private val document = "alpha beta gamma"

    /**
     * The view model on a scope the test owns.
     *
     * [testScope] gives a **child Job** on the test's own context. Both halves are
     * needed and neither alone works:
     *
     * - On the test's context directly, the collectors run — they are dispatched by the
     *   test scheduler — but they are children of the test body, so `runTest` ends with
     *   every one of them still active and fails with `UncompletedCoroutinesError`.
     * - On `backgroundScope`, they are exempt from that wait, but in this project they
     *   are not driven by `advanceUntilIdle`, so nothing runs and the state stays
     *   `Loading`.
     *
     * A child Job runs on the test scheduler *and* can be cancelled on its own, which is
     * what [AutoCloseableCoroutineScope.job] exists for. Every test therefore ends with
     * `vmScope.job?.cancel()`.
     */
    private fun newVm(
        repo: FakeAttachmentAnnotationRepository,
        scope: AutoCloseableCoroutineScope,
    ) = AttachmentAnnotationViewModel(
        repository = repo,
        attachmentId = attachment,
        scope = scope,
    )

    private fun annotation(
        id: AttachmentAnnotationId,
        quote: String = "beta",
        note: String = "the second word",
    ) = AttachmentAnnotation(
        id = id,
        range = TextRange(attachmentId = attachment, start = 6, end = 10, quote = quote),
        note = note,
        userId = UserId.anonymous,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `a seeded note appears in the panel`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        val id = AttachmentAnnotationId.generate()
        repo.seed(annotation(id))

        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }

        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded(document)) }
        val content = ctx.assertIs<AttachmentAnnotationUiState.Content>()

        assertEquals(listOf(id), content.annotations.map { it.annotation.id })
        assertFalse(content.annotations.single().isStale, "the quote is in the document")
        vmScope.job?.cancel()
    }

    @Test
    fun `a note whose quote has left the file stays listed and is marked stale`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        val id = AttachmentAnnotationId.generate()
        repo.seed(annotation(id))

        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded("a rewritten document")) }

        val content = ctx.assertIs<AttachmentAnnotationUiState.Content>()

        assertEquals(1, content.annotations.size, "a stale note must not be dropped")
        val stale = content.annotations.single()
        assertTrue(stale.isStale, "it must be reported as stale")
        assertEquals(AnchorResolution.Stale, stale.resolution)
        assertEquals(null, stale.anchorStart, "there is nothing to point at")
        vmScope.job?.cancel()
    }

    @Test
    fun `a note whose offsets moved keeps its anchor at the quote's new position`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        repo.seed(annotation(AttachmentAnnotationId.generate()))

        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded("XXalpha beta gamma")) }

        val resolved = ctx.assertIs<AttachmentAnnotationUiState.Content>().annotations.single()

        assertFalse(resolved.isStale, "the words are still there, just further along")
        assertEquals(8, resolved.anchorStart)
        vmScope.job?.cancel()
    }

    @Test
    fun `the form opens prefilled with the range it was given`() = runTest {
        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(FakeAttachmentAnnotationRepository(), vmScope)
        }
        ctx.act {
            onIntent(AttachmentAnnotationIntent.OpenEditor(start = 6, end = 10, quote = "beta"))
        }

        val draft = assertNotNull(ctx.assertIs<AttachmentAnnotationUiState.Content>().draft)

        assertEquals("6", draft.start)
        assertEquals("10", draft.end)
        assertEquals("beta", draft.quote)
        assertEquals(null, draft.editing, "a new annotation is not an edit")
        vmScope.job?.cancel()
    }

    @Test
    fun `submitting the form writes a note against the range`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act {
            onIntent(AttachmentAnnotationIntent.OpenEditor(start = 6, end = 10, quote = "beta"))
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DraftNoteChanged("worth remembering")) }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded(document)) }
        ctx.act { onIntent(AttachmentAnnotationIntent.SubmitEditor) }

        val content = ctx.assertIs<AttachmentAnnotationUiState.Content>()
        assertEquals(listOf("create"), repo.calls, "exactly one write, and it was a create")
        assertEquals("worth remembering", content.annotations.single().annotation.note)
        assertEquals(null, content.draft, "the form closes once the write is handed off")
        vmScope.job?.cancel()
    }

    @Test
    fun `submitting an edit changes the note and does not move the range`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        val id = AttachmentAnnotationId.generate()
        repo.seed(annotation(id))
        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded(document)) }
        ctx.act { onIntent(AttachmentAnnotationIntent.EditAnnotation(id)) }
        ctx.act { onIntent(AttachmentAnnotationIntent.DraftNoteChanged("reworded")) }
        ctx.act { onIntent(AttachmentAnnotationIntent.SubmitEditor) }

        val updated = ctx.assertIs<AttachmentAnnotationUiState.Content>().annotations.single().annotation

        assertEquals(listOf("update"), repo.calls, "an edit must not create a second row")
        assertEquals("reworded", updated.note)
        assertEquals("beta", updated.range.quote, "editing a note must not re-anchor it")
        vmScope.job?.cancel()
    }

    @Test
    fun `an offset past the end of the file is refused and the form keeps its contents`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded(document)) }
        ctx.act {
            onIntent(AttachmentAnnotationIntent.OpenEditor(start = 0, end = 9999, quote = "everything"))
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DraftNoteChanged("half written")) }
        ctx.act { onIntent(AttachmentAnnotationIntent.SubmitEditor) }

        val content = ctx.assertIs<AttachmentAnnotationUiState.Content>()
        assertEquals(emptyList(), repo.calls, "an invalid range must not reach the repository")
        assertNotNull(content.draft, "the form keeps what the user typed")
        assertEquals("half written", content.draft!!.note)
        vmScope.job?.cancel()
    }

    @Test
    fun `non-numeric offsets are refused`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded(document)) }
        ctx.act {
            onIntent(AttachmentAnnotationIntent.OpenEditor(start = 0, end = 4, quote = "alph"))
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DraftStartChanged("-")) }
        ctx.act { onIntent(AttachmentAnnotationIntent.SubmitEditor) }

        assertEquals(emptyList(), repo.calls, "a half-typed offset must not be coerced to 0")
        vmScope.job?.cancel()
    }

    @Test
    fun `a failed write reports a message and leaves the note unsaved`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        repo.failNextWrite = IllegalStateException("disk on fire")
        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded(document)) }
        ctx.act {
            onIntent(AttachmentAnnotationIntent.OpenEditor(start = 6, end = 10, quote = "beta"))
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DraftNoteChanged("important")) }
        val errors = mutableListOf<String>()
        // On the test's own scope, not `backgroundScope`: the same reason the view
        // model's scope is a child job rather than background work. An event emitted into
        // a collector that never starts is indistinguishable from an event never sent.
        val collector = vmScope.launch {
            ctx.vm.events.collect { event ->
                errors += (event as AttachmentAnnotationUiEvent.ShowError).message
            }
        }

        ctx.act { onIntent(AttachmentAnnotationIntent.SubmitEditor) }
        collector.cancel()

        assertTrue(
            errors.any { it.contains("disk on fire") },
            "the failure must reach the user, got: $errors",
        )
        assertTrue(
            ctx.assertIs<AttachmentAnnotationUiState.Content>().annotations.isEmpty(),
            "a failed write must not appear as a saved note",
        )
        vmScope.job?.cancel()
    }

    @Test
    fun `deleting removes the note from the panel`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        val id = AttachmentAnnotationId.generate()
        repo.seed(annotation(id))
        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded(document)) }
        ctx.act { onIntent(AttachmentAnnotationIntent.Delete(id)) }

        assertTrue(
            ctx.assertIs<AttachmentAnnotationUiState.Content>().annotations.isEmpty(),
            "a soft-deleted note must leave the list",
        )
        assertEquals(listOf("delete"), repo.calls)
        vmScope.job?.cancel()
    }

    @Test
    fun `only this attachment's notes are watched`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        val mine = annotation(AttachmentAnnotationId.generate())
        val theirs = annotation(AttachmentAnnotationId.generate()).copy(
            range = TextRange(
                attachmentId = AttachmentId.fromString("att_other"),
                start = 0,
                end = 1,
                quote = "x",
            ),
        )
        repo.seed(mine)
        repo.seed(theirs)

        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded(document)) }

        assertEquals(
            listOf(mine.id),
            ctx.assertIs<AttachmentAnnotationUiState.Content>().annotations.map { it.annotation.id },
        )
        vmScope.job?.cancel()
    }

    @Test
    fun `a note seeded after the panel opened still appears`() = runTest {
        val repo = FakeAttachmentAnnotationRepository()
        val vmScope = testScope(this)
        val ctx = testVm(stateAccessor = { vm: AttachmentAnnotationViewModel -> vm.stateFlow }) {
            newVm(repo, vmScope)
        }
        ctx.act { onIntent(AttachmentAnnotationIntent.DocumentLoaded(document)) }
        // Seeded while the collector is already running — the only way it can appear is
        // through the flow, not through a one-shot read at construction.
        repo.seed(annotation(AttachmentAnnotationId.generate()))
        // The seed lands after `act` advanced the scheduler, so the collector has not
        // seen it yet. Advancing again is what lets it through — without this the test
        // would be asserting that a flow emits without anything giving it a chance to.
        advanceUntilIdle()

        assertEquals(1, ctx.assertIs<AttachmentAnnotationUiState.Content>().annotations.size)
        vmScope.job?.cancel()
    }
}
