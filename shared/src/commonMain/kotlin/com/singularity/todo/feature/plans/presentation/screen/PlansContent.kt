package com.singularity.todo.feature.plans.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.plans.presentation.components.PlansSectionList
import com.singularity.todo.feature.plans.presentation.viewmodel.PlansIntent
import com.singularity.todo.feature.plans.presentation.viewmodel.PlansUiState
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * The body of the plan screen.
 *
 * Stateless — it takes a [PlansUiState] and reports what the user did as a
 * [PlansIntent], so the same composable serves the production screen, a
 * preview and a UI test.
 *
 * ## The granularity control
 *
 * It names the granularity in effect, because a control that only shows three
 * anonymous buttons leaves the reader to remember which one is chosen. Choosing
 * another one re-groups the list in place: the sections are a function of the
 * granularity, so a change of it is a change of the list, not a filter over it.
 *
 * @param state The current state of the screen.
 * @param onIntent Called with what the user did.
 * @param onTaskClick Called with the id of the task the user tapped.
 * @param onToggleCompleted Called when the user ticks a task off — or back on.
 * @param onSwipeAway Called when the user swipes a task away.
 * @param onAddInSection Called with the date a new task should be due.
 * @param snackbarHost Where a one-shot message is shown. Supplied by the
 *   screen, which owns the state of the message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansContent(
    state: PlansUiState,
    onIntent: (PlansIntent) -> Unit,
    onTaskClick: (TaskId) -> Unit,
    onToggleCompleted: (TaskId) -> Unit,
    onSwipeAway: (TaskId) -> Unit,
    onAddInSection: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.testTag(TestTags.PLANS_SCREEN),
        topBar = { TopAppBar(title = { Text("Plans") }) },
        snackbarHost = snackbarHost,
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (state) {
                is PlansUiState.Loading -> PlansLoading()

                is PlansUiState.Error -> PlansMessage(
                    text = state.message,
                    tag = TestTags.PLANS_ERROR,
                    modifier = Modifier.fillMaxSize(),
                )

                is PlansUiState.Loaded -> {
                    PlansGranularityControl(
                        granularity = state.granularity,
                        onGranularityChange = { onIntent(PlansIntent.SetGranularity(it)) },
                    )

                    if (state.isEmpty) {
                        PlansMessage(
                            text = "Nothing scheduled",
                            tag = TestTags.PLANS_EMPTY_STATE,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        PlansSectionList(
                            sections = state.sections,
                            onTaskClick = onTaskClick,
                            onToggleCompleted = onToggleCompleted,
                            onSwipeAway = onSwipeAway,
                            onAddInSection = onAddInSection,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The switch between the day, the week and the month.
 *
 * The name of the granularity is on the button that is chosen, so the control
 * states what is in effect rather than making the reader remember it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlansGranularityControl(
    granularity: PlansGranularity,
    onGranularityChange: (PlansGranularity) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        val options = listOf(
            PlansGranularity.Day to "Day",
            PlansGranularity.Week to "Week",
            PlansGranularity.Month to "Month",
        )
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = granularity == value,
                onClick = { onGranularityChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.testTag(
                    when (value) {
                        PlansGranularity.Day -> TestTags.PLANS_GRANULARITY_DAY
                        PlansGranularity.Week -> TestTags.PLANS_GRANULARITY_WEEK
                        PlansGranularity.Month -> TestTags.PLANS_GRANULARITY_MONTH
                    },
                ),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun PlansLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * A sentence where a list would be.
 *
 * An empty timeline is not an absence of content — it is the answer "there is
 * nothing scheduled", and the user has to be told it.
 */
@Composable
private fun PlansMessage(text: String, tag: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(PaddingValues(32.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(tag),
        )
    }
}
