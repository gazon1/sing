package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.sheet.rememberAppSheetState
import com.singularity.todo.feature.projects.presentation.state.ProjectBriefUiState

/**
 * The sheet that shows the project brief.
 *
 * The brief is generated on demand, so the sheet has three faces: the wait, the
 * review, and the reason there is no review. Nothing is written while it is open —
 * the brief is a reading, not an edit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectBriefSheet(
    state: ProjectBriefUiState,
    onDismiss: () -> Unit,
) {
    if (state is ProjectBriefUiState.Hidden) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberAppSheetState(initialValue = SheetValue.Hidden),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text("Project brief", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            when (state) {
                ProjectBriefUiState.Hidden -> {
                    Unit
                }

                ProjectBriefUiState.Loading -> {
                    CircularProgressIndicator()
                }

                is ProjectBriefUiState.Ready -> {
                    Text(
                        state.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }

                is ProjectBriefUiState.Failed -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Close") }
        }
    }
}
