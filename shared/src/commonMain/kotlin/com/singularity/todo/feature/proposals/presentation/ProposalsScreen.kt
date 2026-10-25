package com.singularity.todo.feature.proposals.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.refresh.RefreshableContent
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.core.ui.components.TaggedSnackbarHost
import com.singularity.todo.feature.proposals.presentation.components.ProposalCardsSection
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * The proposals waiting for a decision.
 *
 * ## Why the screen reads the errors
 *
 * The ViewModel has always said *why* a change could not be applied — the message was
 * there, and it was emitted. Nobody collected it: the screen had no snackbar and no
 * event handler, so every failure was silent. The user tapped "apply", nothing
 * happened, and the proposal stayed on the screen as if it were still fine.
 *
 * So the screen reads the events now, and the card also keeps the reason next to the
 * change it belongs to — the message is transient, the reason on the card is not.
 *
 * ## Why the screen does not apply anything itself
 *
 * The screen draws proposals and reports what the user decided. Applying a change is
 * the use case's job, and it applies exactly what the user confirmed.
 */
@Composable
fun ProposalsScreen(modifier: Modifier = Modifier) {
    val viewModel: ProposalsViewModel = koinViewModel()
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    CollectEvents(viewModel.events) { event ->
        when (event) {
            is ProposalsUiEvent.ShowError -> {
                scope.launch {
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Short)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { TaggedSnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        RefreshableContent {
            when {
                state.isLoading -> LoadingIndicator(modifier = Modifier.fillMaxSize().padding(paddingValues))

                state.proposals.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
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

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SelectableText(
                            text = "Waiting for you",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                    items(state.proposals, key = { it.id.value }) { proposal ->
                        ProposalCardsSection(
                            proposals = persistentListOf(proposal),
                            onConfirm = { viewModel.onIntent(ProposalsIntent.Confirm(it)) },
                            onReject = { itemId, reason ->
                                viewModel.onIntent(ProposalsIntent.Reject(itemId, reason))
                            },
                            onConfirmAll = { viewModel.onIntent(ProposalsIntent.ConfirmAll(it)) },
                            onDismiss = { viewModel.onIntent(ProposalsIntent.Dismiss(it)) },
                            onEdit = { itemId, text -> viewModel.onIntent(ProposalsIntent.Edit(itemId, text)) },
                            failures = state.failures,
                        )
                    }
                }
            }
        }
    }
}
