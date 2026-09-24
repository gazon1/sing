package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import com.singularity.todo.core.ui.components.ListPickerItem
import com.singularity.todo.core.ui.components.ListPickerSheet
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction

/**
 * Modal bottom sheet that lists every AI action available for [task].
 *
 * Stateless: tapping an entry fires [onAction] with the appropriate enum
 * value. The parent is responsible for invoking the ViewModel and dismissing
 * the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskAiBottomSheet(task: Task, onAction: (TaskAiAction) -> Unit, onDismiss: () -> Unit) {
    ListPickerSheet(
        title = "AI Actions for: ${task.title}",
        items = TaskAiAction.entries.map { action ->
            ListPickerItem(
                key = action,
                label = action.label(),
                leading = @Composable { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
            )
        },
        onItemSelected = onAction,
        onDismiss = onDismiss,
    )
}

private fun TaskAiAction.label(): String = when (this) {
    TaskAiAction.RefineTitle -> "Refine title"
    TaskAiAction.GenerateDescription -> "Generate description"
    TaskAiAction.GenerateChecklist -> "Generate checklist"
    TaskAiAction.Decompose -> "Decompose into sub-tasks"
    TaskAiAction.SuggestTime -> "Suggest time"
}
