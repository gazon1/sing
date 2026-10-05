package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem

/**
 * Pending AI proposals for a task, as a stack of cards.
 *
 * Moved out of `TaskDetailViewScreen` when that screen was deleted. It was a
 * private composable there, which is how it came to be missing from the desktop
 * task detail: the screen it belonged to was reachable from the Android graph
 * only, so the section rendered on one platform and not the other while both
 * platforms showed the same route and the same ViewModel. Nothing about the
 * proposal feature is platform-specific, so the section now lives next to its
 * siblings in `components/detail/` and both graphs get it.
 */
@Composable
fun TaskDetailProposalSection(
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

@Composable
private fun ProposalCard(
    proposal: AiProposal,
    onConfirm: (ProposalItemId) -> Unit,
    onReject: (ProposalItemId, String?) -> Unit,
    onConfirmAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var rejectingItemId by mutableStateOf<ProposalItemId?>(null)
    var rejectionText by mutableStateOf("")

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
                    rejectionText = if (rejectingItemId == item.id) rejectionText else "",
                    onConfirm = { onConfirm(item.id) },
                    onStartReject = { rejectingItemId = item.id },
                    onSendReject = {
                        onReject(item.id, rejectionText.takeIf { it.isNotBlank() })
                        rejectingItemId = null
                        rejectionText = ""
                    },
                    onCancelReject = {
                        rejectingItemId = null
                        rejectionText = ""
                    },
                    onRejectionTextChange = { rejectionText = it },
                )
            }
        }
    }
}

@Composable
private fun ProposalItemRow(
    item: ProposalItem,
    isRejecting: Boolean,
    rejectionText: String,
    onConfirm: () -> Unit,
    onStartReject: () -> Unit,
    onSendReject: () -> Unit,
    onCancelReject: () -> Unit,
    onRejectionTextChange: (String) -> Unit,
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
            Text(
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
                value = rejectionText,
                onValueChange = onRejectionTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Reason (optional)") },
                singleLine = true,
            )
        }
    }
}
