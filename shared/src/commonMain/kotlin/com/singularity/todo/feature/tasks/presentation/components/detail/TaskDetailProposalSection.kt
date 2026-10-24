package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.runtime.Composable
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalTargetKind
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.proposals.presentation.components.ProposalCardsSection

/**
 * Pending AI proposals for a task, as a stack of cards.
 *
 * Extracted from a task-detail screen that was itself removed on 2026-10-06
 * (633 unreachable lines, cleared by `find-unwired-surfaces.py`). It had been a
 * private composable in that screen, which is how it came to be missing from the
 * desktop task detail: the screen it belonged to was reachable from the Android graph
 * only, so the section rendered on one platform and not the other while both
 * platforms showed the same route and the same ViewModel. Nothing about the
 * proposal feature is platform-specific, so the section now lives next to its
 * siblings in `components/detail/` and both graphs get it.
 *
 * The card itself is the proposals' own: a proposal is a change to something, and
 * the note editor shows the same card for the same reason.
 */
@Composable
fun TaskDetailProposalSection(
    proposals: List<AiProposal>,
    onConfirm: (ProposalItemId) -> Unit,
    onReject: (ProposalItemId, String?) -> Unit,
    onConfirmAll: (ProposalId) -> Unit,
    onDismiss: (ProposalId) -> Unit,
    onEdit: ((ProposalItemId, String) -> Unit)? = null,
    failures: Map<ProposalItemId, String> = emptyMap(),
) {
    ProposalCardsSection(
        proposals = proposals,
        onConfirm = onConfirm,
        onReject = onReject,
        onConfirmAll = onConfirmAll,
        onDismiss = onDismiss,
        onEdit = onEdit,
        failures = failures,
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskDetailProposalSectionPreview() = PreviewThemed(darkTheme = false) {
    val proposalId = ProposalId.fromString("prop_1")
    TaskDetailProposalSection(
        proposals = listOf(
            AiProposal(
                id = proposalId,
                targetKind = ProposalTargetKind.Task,
                targetId = "t1",
                userId = UserId.anonymous,
                source = ProposalSource.Detail,
                createdAt = PreviewSamples.now,
                updatedAt = PreviewSamples.now,
                items = listOf(
                    ProposalItem.pending(
                        id = ProposalItemId.fromString("pi_1"),
                        proposalId = proposalId,
                        kind = ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
                        targetId = "t1",
                        humanSummary = "Set the priority to High",
                        fingerprint = "set-priority-high",
                    ),
                    ProposalItem.pending(
                        id = ProposalItemId.fromString("pi_2"),
                        proposalId = proposalId,
                        kind = ProposalItemKind.AddSubtasks(listOf("Draft the outline")),
                        targetId = "t1",
                        humanSummary = "Add the subtask “Draft the outline”",
                        fingerprint = "add-subtask-outline",
                    ),
                ),
            ),
        ),
        onConfirm = { },
        onReject = { _, _ -> },
        onConfirmAll = { },
        onDismiss = { },
    )
}
