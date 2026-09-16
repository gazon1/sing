package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet that lists every AI action available for [task].
 *
 * Stateless: tapping an entry fires [onAction] with the appropriate enum
 * value. The parent is responsible for invoking the ViewModel and dismissing
 * the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskAiBottomSheet(
    task: Task,
    onAction: (TaskAiAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    LaunchedEffect(Unit) { sheetState.show() }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "AI Actions for: ${task.title}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.padding(8.dp))
            TaskAiAction.entries.forEach { action ->
                TextButton(onClick = {
                    scope.launch { sheetState.hide() }
                    onAction(action)
                }) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Text(" ${action.label()}")
                }
            }
            Spacer(modifier = Modifier.padding(16.dp))
        }
    }
}

private fun TaskAiAction.label(): String = when (this) {
    TaskAiAction.RefineTitle -> "Refine title"
    TaskAiAction.GenerateDescription -> "Generate description"
    TaskAiAction.GenerateChecklist -> "Generate checklist"
    TaskAiAction.Decompose -> "Decompose into sub-tasks"
    TaskAiAction.SuggestTime -> "Suggest time"
}
