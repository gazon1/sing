package com.singularity.todo.feature.attachments.annotation

import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.annotation.AnchorResolution
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotation
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationId
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationRepository
import com.singularity.todo.core.attachments.annotation.TextRange
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One annotation as the panel renders it: the note, plus where it currently points.
 *
 * The resolution is computed here rather than in the composable so a stale note is a
 * property of state, and a test can assert it without a Compose runtime.
 */
data class ResolvedAnnotation(val annotation: AttachmentAnnotation, val resolution: AnchorResolution) {
    /** True when the words this note was written against are no longer in the file. */
    val isStale: Boolean get() = resolution is AnchorResolution.Stale

    /** Where the note points now, or `null` when nothing in the file matches. */
    val anchorStart: Int?
        get() = when (resolution) {
            is AnchorResolution.Exact -> resolution.start
            is AnchorResolution.QuoteFound -> resolution.start
            AnchorResolution.Stale -> null
        }
}

/**
 * What the create/edit form holds.
 *
 * Offsets are strings rather than `Int`s because they come out of a text field: a half-typed
 * `-` has no integer value, and the form must survive that without losing the rest.
 */
data class AnnotationDraft(
    val start: String = "0",
    val end: String = "0",
    val quote: String = "",
    val note: String = "",
    /** Set while editing an existing annotation; `null` means "create a new one". */
    val editing: AttachmentAnnotationId? = null,
)

sealed interface AttachmentAnnotationUiState {
    data object Loading : AttachmentAnnotationUiState

    data class Content(
        val annotations: List<ResolvedAnnotation>,
        /** Text of the file, as of the last emission. Drives stale resolution. */
        val documentText: String,
        val sheetOpen: Boolean,
        val draft: AnnotationDraft?,
    ) : AttachmentAnnotationUiState

    data class Error(val message: String) : AttachmentAnnotationUiState
}

sealed interface AttachmentAnnotationIntent : MviIntent {
    /**
     * The viewer's text, delivered once the file has loaded.
     *
     * The ViewModel holds it because that is what makes staleness computable: a note whose
     * quote has left the file is stale, and that cannot be answered without the file.
     */
    data class DocumentLoaded(val text: String) : AttachmentAnnotationIntent

    data object OpenSheet : AttachmentAnnotationIntent
    data object CloseSheet : AttachmentAnnotationIntent

    /** Opens the create form, prefilled with a range the caller already knows. */
    data class OpenEditor(val start: Int, val end: Int, val quote: String) : AttachmentAnnotationIntent

    /** Opens the form on an existing annotation, editing its note only — never its range. */
    data class EditAnnotation(val id: AttachmentAnnotationId) : AttachmentAnnotationIntent

    data object CloseEditor : AttachmentAnnotationIntent
    data class DraftStartChanged(val value: String) : AttachmentAnnotationIntent
    data class DraftEndChanged(val value: String) : AttachmentAnnotationIntent
    data class DraftQuoteChanged(val value: String) : AttachmentAnnotationIntent
    data class DraftNoteChanged(val value: String) : AttachmentAnnotationIntent
    data object SubmitEditor : AttachmentAnnotationIntent
    data class Delete(val id: AttachmentAnnotationId) : AttachmentAnnotationIntent
}

sealed interface AttachmentAnnotationUiEvent : MviEvent {
    data class ShowError(val message: String) : AttachmentAnnotationUiEvent
}

/**
 * The annotations panel for one text attachment.
 *
 * ## Why the offsets are typed rather than selected
 *
 * Compose Multiplatform 1.12 exposes no supported way to read a text selection back:
 * `SelectionController` is present but is not public API, and `SelectionContainer` reports
 * no observable range. Rather than fake a selection, the create form asks for the two
 * offsets and prefills the quote from the file — so the note is anchored against real text
 * and resolved by the same `resolveAnchor` every other path uses. See ADR
 * `2026-10-07-annotation-anchor-model`.
 *
 * ## Stale notes stay
 *
 * A note whose quote has left the file is still listed, flagged, editable and deletable.
 * Dropping it would take the user's words with it and report nothing.
 *
 * @param repository resolves the owning profile itself; see
 *   [AttachmentAnnotationRepository].
 * @param attachmentId the attachment whose notes this panel edits.
 * @param crashReporter mandatory: every write here can fail, and a note the user just typed
 *   vanishing without a report is the failure this parameter exists to prevent.
 * @param scope last, so `viewModel { }` stays unambiguous about which argument is which.
 */
class AttachmentAnnotationViewModel(
    private val repository: AttachmentAnnotationRepository,
    private val attachmentId: AttachmentId,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<AttachmentAnnotationUiState, AttachmentAnnotationIntent, AttachmentAnnotationUiEvent>(
        initialState = AttachmentAnnotationUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /**
     * The loaded file text.
     *
     * Held outside the state because it is an *input* to staleness rather than something
     * the panel renders, and because `Content` is rebuilt on every repository emission — a
     * field kept only there would be dropped each time.
     *
     * Declared above [init] on purpose: `ViewModelInitOrderTest` exists because a property
     * read from an `init` block and declared below it is a null read waiting for a busy
     * machine.
     */
    private var documentText: String = ""

    init {
        scope.launch {
            repository.watchForAttachment(attachmentId)
                .map { annotations -> resolve(annotations, documentText) }
                .catch { e -> updateState { AttachmentAnnotationUiState.Error(e.toMessage()) } }
                .collect { resolved ->
                    updateState { current ->
                        val content = current as? AttachmentAnnotationUiState.Content
                        AttachmentAnnotationUiState.Content(
                            annotations = resolved,
                            documentText = documentText,
                            sheetOpen = content?.sheetOpen ?: false,
                            draft = content?.draft,
                        )
                    }
                }
        }
    }

    override fun onIntent(intent: AttachmentAnnotationIntent) {
        when (intent) {
            is AttachmentAnnotationIntent.DocumentLoaded -> scope.launch { onDocumentLoaded(intent.text) }

            AttachmentAnnotationIntent.OpenSheet -> editContent { it.copy(sheetOpen = true) }

            AttachmentAnnotationIntent.CloseSheet -> editContent { it.copy(sheetOpen = false) }

            is AttachmentAnnotationIntent.OpenEditor -> editContent {
                it.copy(
                    sheetOpen = true,
                    draft = AnnotationDraft(
                        start = intent.start.toString(),
                        end = intent.end.toString(),
                        quote = intent.quote,
                    ),
                )
            }

            is AttachmentAnnotationIntent.EditAnnotation -> editContent { content ->
                val target = content.annotations.firstOrNull { it.annotation.id == intent.id }
                content.copy(
                    sheetOpen = true,
                    draft = target?.let {
                        AnnotationDraft(
                            start = it.annotation.range.start.toString(),
                            end = it.annotation.range.end.toString(),
                            quote = it.annotation.range.quote,
                            note = it.annotation.note,
                            editing = it.annotation.id,
                        )
                    } ?: content.draft,
                )
            }

            AttachmentAnnotationIntent.CloseEditor -> editContent { it.copy(draft = null) }

            is AttachmentAnnotationIntent.DraftStartChanged -> editDraft { it.copy(start = intent.value) }

            is AttachmentAnnotationIntent.DraftEndChanged -> editDraft { it.copy(end = intent.value) }

            is AttachmentAnnotationIntent.DraftQuoteChanged -> editDraft { it.copy(quote = intent.value) }

            is AttachmentAnnotationIntent.DraftNoteChanged -> editDraft { it.copy(note = intent.value) }

            AttachmentAnnotationIntent.SubmitEditor -> scope.launch { submitEditor() }

            is AttachmentAnnotationIntent.Delete -> scope.launch { delete(intent.id) }
        }
    }

    /**
     * Re-resolves every note against the newly loaded text.
     *
     * The notes themselves did not change, but staleness did — and nothing else will emit,
     * because the repository has no reason to.
     */
    private suspend fun onDocumentLoaded(text: String) {
        documentText = text
        updateState { current ->
            when (current) {
                is AttachmentAnnotationUiState.Content -> current.copy(
                    documentText = text,
                    annotations = resolve(current.annotations.map { it.annotation }, text),
                )

                else -> AttachmentAnnotationUiState.Content(emptyList(), text, sheetOpen = false, draft = null)
            }
        }
    }

    /**
     * Validates the draft and writes it.
     *
     * The far side validates by construction — [TextRange] throws on a backwards range —
     * so what is left to check here is that the offsets parse and fit the loaded text. A
     * rejected draft becomes a `ShowError` event and the form keeps its contents: a
     * half-written note must not disappear because one field is mid-edit.
     */
    private suspend fun submitEditor() {
        val content = currentState as? AttachmentAnnotationUiState.Content ?: return
        val draft = content.draft ?: return
        val start = draft.start.toIntOrNull()
        val end = draft.end.toIntOrNull()

        when {
            start == null || end == null ->
                emitError(
                    errorLabel = "Annotation offsets rejected",
                    errorEvent = AttachmentAnnotationUiEvent::ShowError,
                ) {
                    Result.failure<Unit>(
                        IllegalArgumentException("Start and end must be whole numbers"),
                    )
                }

            end > content.documentText.length ->
                emitError(
                    errorLabel = "Annotation offsets rejected",
                    errorEvent = AttachmentAnnotationUiEvent::ShowError,
                ) {
                    Result.failure<Unit>(
                        IllegalArgumentException(
                            "End ($end) is past the end of the file " +
                                "(${content.documentText.length} characters)",
                        ),
                    )
                }

            draft.editing != null ->
                emitError(
                    errorLabel = "Annotation update failed",
                    errorEvent = AttachmentAnnotationUiEvent::ShowError,
                ) { repository.update(draft.editing, draft.note) }

            else ->
                emitError(
                    errorLabel = "Annotation create failed",
                    errorEvent = AttachmentAnnotationUiEvent::ShowError,
                ) {
                    repository.create(
                        range = TextRange(
                            attachmentId = attachmentId,
                            start = start,
                            end = end,
                            quote = draft.quote,
                        ),
                        note = draft.note,
                    )
                }
        }

        // The write has been handed off; the repository will emit the row through
        // `watchForAttachment` and the sheet re-renders. Closing the form on a *failed*
        // write would throw away what the user typed, so this only closes when the draft
        // parsed and the range fitted.
        if (start != null && end != null && end <= content.documentText.length) {
            editContent { it.copy(draft = null) }
        }
    }

    private suspend fun delete(id: AttachmentAnnotationId) {
        emitError("Annotation delete failed", AttachmentAnnotationUiEvent::ShowError) {
            repository.delete(id)
        }
    }

    private fun resolve(annotations: List<AttachmentAnnotation>, text: String): List<ResolvedAnnotation> =
        annotations.map { ResolvedAnnotation(it, it.anchorIn(text)) }

    private fun editContent(
        transform: (AttachmentAnnotationUiState.Content) -> AttachmentAnnotationUiState.Content,
    ) {
        updateState { current ->
            if (current is AttachmentAnnotationUiState.Content) transform(current) else current
        }
    }

    private fun editDraft(transform: (AnnotationDraft) -> AnnotationDraft) {
        editContent { content -> content.copy(draft = content.draft?.let(transform)) }
    }
}
