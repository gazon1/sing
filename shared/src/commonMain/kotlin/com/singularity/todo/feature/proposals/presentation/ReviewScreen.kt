package com.singularity.todo.feature.proposals.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import org.koin.compose.viewmodel.koinViewModel

/**
 * The proposals waiting for a decision.
 *
 * Each change the assistant proposed is a line with two buttons: apply it, or turn it
 * down. Nothing is applied until the user says so.
 */
@Composable
fun ReviewScreen(modifier: Modifier = Modifier) {
    val viewModel: ReviewViewModel = koinViewModel()
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()

    if (state.isLoading) {
        LoadingIndicator(modifier = modifier)
        return
    }

    if (state.proposals.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Nothing to review",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Changes the assistant proposes show up here, waiting for you.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Waiting for you",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(state.proposals, key = { it.id }) { proposal ->
            ProposalCard(
                proposal = proposal,
                onConfirm = { viewModel.onIntent(ReviewIntent.Confirm(it)) },
                onReject = { viewModel.onIntent(ReviewIntent.Reject(it)) },
            )
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: ReviewProposal,
    onConfirm: (ProposalItemId) -> Unit,
    onReject: (ProposalItemId) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = targetLabel(proposal),
                style = MaterialTheme.typography.labelLarge,
            )
            proposal.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onConfirm(ProposalItemId.fromString(item.id)) }) {
                        Text("Apply")
                    }
                    OutlinedButton(onClick = { onReject(ProposalItemId.fromString(item.id)) }) {
                        Text("No")
                    }
                }
            }
        }
    }
}

/** What the change is about, in the app's words. */
private fun targetLabel(proposal: ReviewProposal): String = when (proposal.targetKind) {
    AiProposal.TARGET_KIND_TASK -> "Task"
    AiProposal.TARGET_KIND_NOTE -> "Note"
    AiProposal.TARGET_KIND_PROJECT -> "Project"
    AiProposal.TARGET_KIND_TAG -> "Tag"
    else -> "Change"
}
