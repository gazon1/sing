package com.singularity.todo.feature.search.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.search.query.SearchMode

/**
 * The switch that states which question the search is asking.
 *
 * The word mode finds a word by its beginning — "arch" finds "architecture" —
 * and the contains mode finds the term wherever it occurs — "port" finds
 * "airport". The switch is here, next to the field the query is written in,
 * because the mode is part of the question: a query means the same thing
 * wherever it is written down, and the rule it is answered by is stated by the
 * surface that runs it.
 *
 * The contains mode says what it costs. The index stores words, not character
 * positions, so it cannot say whether a term occurs in the middle of one; the
 * mode reads the rows instead. A slower answer that is not announced is a
 * slower answer that is not believed.
 *
 * See `docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`.
 *
 * @param mode The mode the free text is matched in.
 * @param onModeChange Called with the mode the person chose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchModeSwitch(
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            SegmentedButton(
                selected = mode == SearchMode.Word,
                onClick = { onModeChange(SearchMode.Word) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.testTag(TestTags.SEARCH_MODE_WORD),
            ) {
                Text("Word")
            }
            SegmentedButton(
                selected = mode == SearchMode.Contains,
                onClick = { onModeChange(SearchMode.Contains) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.testTag(TestTags.SEARCH_MODE_CONTAINS),
            ) {
                Text("Contains")
            }
        }

        if (mode == SearchMode.Contains) {
            Text(
                text = "Contains mode reads every record — slower than the word search.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(TestTags.SEARCH_MODE_CONTAINS_NOTE),
            )
        }
    }
}
