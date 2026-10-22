package com.singularity.todo.feature.proposals.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.proposals.presentation.components.ProposalCard
import org.koin.compose.viewmodel.koinViewModel

/**
 * The proposals waiting for a decision.
 *
 * ## Why the card is the shared one
 *
 * A proposal is a change to something, not a property of a task or of a note. The
 * card that draws it therefore lives with the proposals, and the task detail, the
 * note editor and this screen all draw it. Three renderings of one thing meant
 * three sets of buttons and three vocabularies for the same act; now there is one
 * card, and the person who meets a proposal learns one way of deciding.
 *
 * ## What the screen is called
 *
 * "Proposals", and not "Review". A review is a procedure — the four scenarios the
 * chat can run — and this screen does not run one: it lists what is waiting. The
 * name it had promised more than it gave, and a screen that promises more than it
 * gives is a screen the person learns to distrust.
 */
@Composable
fun ProposalsScreen(modifier: Modifier = Modifier) {
    val viewModel: ProposalsViewModel = koinViewModel()
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
            SelectableText(
                text = "Nothing to propose",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            SelectableText(
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
                onConfirm = { viewModel.onIntent(ProposalsIntent.Confirm(it)) },
                onReject = { itemId, reason -> viewModel.onIntent(ProposalsIntent.Reject(itemId, reason)) },
                onConfirmAll = { viewModel.onIntent(ProposalsIntent.ConfirmAll(proposal.id)) },
                onDismiss = { viewModel.onIntent(ProposalsIntent.Dismiss(proposal.id)) },
            )
        }
    }
}
