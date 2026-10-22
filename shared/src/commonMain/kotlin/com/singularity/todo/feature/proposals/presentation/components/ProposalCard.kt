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
 */
@Composable
fun ProposalCardsSection(
    proposals: List<AiProposal>,
    onConfirm: (ProposalItemId) -> Unit,
    onReject: (ProposalItemId, String?) -> Unit,
    onConfirmAll: (ProposalId) -> Unit,
    onDismiss: (ProposalId) -> Unit,
) {
    proposals.forEach { proposal ->
        ProposalCard(
            proposal = proposal,
            onConfirm = onConfirm,
            onReject = onReject,
            onConfirmAll = { onConfirmAll(proposal.id) },
            onDismiss = { onDismiss(proposal.id) },
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
) {
    var rejectingItemId by mutableStateOf<ProposalItemId?>(null)
    // The state lives here, not in the row: the row is only composed while the user is
    // rejecting, so a state created there would be thrown away on the first keystroke.
    val rejectionState = rememberTextFieldState()

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
                )
            }
        }
    }
}

@Composable
private fun ProposalItemRow(
    item: ProposalItem,
    isRejecting: Boolean,
    rejectionState: TextFieldState,
    onConfirm: () -> Unit,
    onStartReject: () -> Unit,
    onSendReject: () -> Unit,
    onCancelReject: () -> Unit,
) {
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
            } else {
                TextButton(onClick = onConfirm) { Text("✓") }
                TextButton(onClick = onStartReject) { Text("✗") }
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
    }
}
