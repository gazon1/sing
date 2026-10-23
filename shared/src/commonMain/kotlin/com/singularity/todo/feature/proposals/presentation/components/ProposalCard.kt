package com.singularity.todo.feature.proposals.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ui.text.LinkifiedText
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem

/**
 * The changes waiting for a decision, as a stack of cards.
 *
 * ## Why the card lives here and not in a feature
 *
 * A proposal is not a task's property and not a note's property — it is a change to
 * something, and the same card shows it for either. So the card lives with the
 * proposals, and the task detail and the note editor both show it.
 *
 * ## Why the card asks before it applies
 *
 * The whole point of a proposal is that the change has not happened yet. So the card
 * offers "apply" and "no", and it never applies anything on its own.
 *
 * ## Why the card can change the value
 *
 * A proposal the user cannot change is a proposal the user can only accept or
 * refuse. The model named a value; the model may have named it wrong, and the user
 * is the one who knows what they meant. So a change whose value a person would type
 * carries a field, and what the user types is read by the same reader that read the
 * model's value — and refused with the same reason when it does not read.
 *
 * ## Why the decided changes are still on the card
 *
 * A refusal the user cannot see is a refusal they cannot learn from. The card keeps
 * the changes that were already decided — muted, with the reason — so the user reads
 * what happened to the change they just turned down, and so a proposal that is half
 * decided still tells the whole story.
 */
@Composable
fun ProposalCardsSection(
    proposals: List<AiProposal>,
    onConfirm: (ProposalItemId) -> Unit,
    onReject: (ProposalItemId, String?) -> Unit,
    onConfirmAll: (ProposalId) -> Unit,
    onDismiss: (ProposalId) -> Unit,
    onEdit: ((ProposalItemId, String) -> Unit)? = null,
    failures: Map<ProposalItemId, String> = emptyMap(),
) {
    proposals.forEach { proposal ->
        ProposalCard(
            proposal = proposal,
            onConfirm = onConfirm,
            onReject = onReject,
            onConfirmAll = { onConfirmAll(proposal.id) },
            onDismiss = { onDismiss(proposal.id) },
            onEdit = onEdit,
            failures = failures,
        )
    }
}

/** One proposal, with the changes it holds. */
@Composable
fun ProposalCard(
    proposal: AiProposal,
    onConfirm: (ProposalItemId) -> Unit,
    onReject: (ProposalItemId, String?) -> Unit,
    onConfirmAll: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: ((ProposalItemId, String) -> Unit)? = null,
    failures: Map<ProposalItemId, String> = emptyMap(),
) {
    var rejectingItemId by mutableStateOf<ProposalItemId?>(null)
    var editingItemId by mutableStateOf<ProposalItemId?>(null)
    // The state lives here, not in the row: the row is only composed while the user is
    // rejecting, so a state created there would be thrown away on the first keystroke.
    val rejectionState = rememberTextFieldState()
    // The same, for the value the user is changing. The key is the item being edited,
    // so editing another item starts from that item's value rather than the last one.
    val editState: TextFieldState = key(editingItemId) {
        rememberTextFieldState(initialText = editingItemId?.let { id -> proposal.editableTextOf(id) }.orEmpty())
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AI Suggestions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Row {
                    TextButton(onClick = onConfirmAll) {
                        Text("Accept all")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss")
                    }
                }
            }

            proposal.pendingItems.forEach { item ->
                ProposalItemRow(
                    item = item,
                    isRejecting = rejectingItemId == item.id,
                    rejectionState = rejectionState,
                    isEditing = editingItemId == item.id,
                    editState = editState,
                    canEdit = onEdit != null,
                    failure = failures[item.id],
                    onConfirm = { onConfirm(item.id) },
                    onStartReject = { rejectingItemId = item.id },
                    onSendReject = {
                        onReject(item.id, rejectionState.text.toString().takeIf { it.isNotBlank() })
                        rejectingItemId = null
                        rejectionState.clearText()
                    },
                    onCancelReject = {
                        rejectingItemId = null
                        rejectionState.clearText()
                    },
                    onStartEdit = { editingItemId = item.id },
                    onSendEdit = {
                        onEdit?.invoke(item.id, editState.text.toString())
                        editingItemId = null
                    },
                    onCancelEdit = { editingItemId = null },
                )
            }

            proposal.decidedItems.forEach { item ->
                DecidedProposalItemRow(item = item)
            }
        }
    }
}

/**
 * The value of the item being edited — the text the field is filled with.
 *
 * The card asks the kind, not the item: the kind knows whether it carries a value a
 * person would type, and the item only knows which kind it is.
 */
private fun AiProposal.editableTextOf(itemId: ProposalItemId): String =
    items.firstOrNull { it.id == itemId }?.kind?.editableProposal()?.text.orEmpty()

@Composable
private fun ProposalItemRow(
    item: ProposalItem,
    isRejecting: Boolean,
    rejectionState: TextFieldState,
    isEditing: Boolean,
    editState: TextFieldState,
    canEdit: Boolean,
    failure: String?,
    onConfirm: () -> Unit,
    onStartReject: () -> Unit,
    onSendReject: () -> Unit,
    onCancelReject: () -> Unit,
    onStartEdit: () -> Unit,
    onSendEdit: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    val editable = item.kind.editableProposal()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The summary is prose the model wrote, and prose is where links turn up.
            // The row also holds buttons, so the text is linkified rather than wrapped in
            // a selection group of its own.
            LinkifiedText(
                text = item.humanSummary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (isRejecting) {
                TextButton(onClick = onSendReject) { Text("Send") }
                TextButton(onClick = onCancelReject) { Text("Cancel") }
            } else if (isEditing) {
                TextButton(onClick = onSendEdit) { Text("Save") }
                TextButton(onClick = onCancelEdit) { Text("Cancel") }
            } else {
                TextButton(onClick = onConfirm) { Text("✓") }
                TextButton(onClick = onStartReject) { Text("✗") }
                if (editable != null && canEdit) {
                    TextButton(onClick = onStartEdit) { Text("✎") }
                }
            }
        }
        if (isRejecting) {
            OutlinedTextField(
                state = rejectionState,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Reason (optional)") },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        }
        if (isEditing) {
            OutlinedTextField(
                state = editState,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("The value you want") },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        }
        if (failure != null) {
            LinkifiedText(
                text = failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // The reason the stored value does not read. It is not a failed attempt:
        // the change itself cannot be applied, and the user has to know that
        // before they press "apply" — and be able to turn it down.
        val readFailure = item.readFailure
        if (readFailure != null) {
            LinkifiedText(
                text = "This change cannot be applied: $readFailure",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * A change that has already been decided.
 *
 * Muted, and with the reason: the reason is what the user wrote, or what the app
 * could not do, and either way it is the answer to "what happened to this?".
 */
@Composable
private fun DecidedProposalItemRow(item: ProposalItem) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LinkifiedText(
            text = item.humanSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val reason = item.rejectionReason?.takeIf { it.isNotBlank() }
        val outcome = item.outcomeLabel
        LinkifiedText(
            text = if (reason == null) outcome else "$outcome — $reason",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
