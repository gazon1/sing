package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet

/**
 * Unified task editor Composable for both Create and View modes.
 *
 * Reduces the 20+ parameter signature to two focused data classes:
 * - [TaskEditorModel]: all editable attribute values
 * - [TaskEditorCallbacks]: all callbacks, grouped by attribute (null = row hidden)
 *
 * @param model Current values for all editable attributes.
 * @param callbacks All callbacks grouped by attribute. Null bundle = that row is hidden.
 * @param modifier Passed through to the root Scaffold.
 */
@Composable
fun TaskEditorContent(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<TaskEditorSheet?>(null) }

    Scaffold(
        topBar = {
            TaskDetailTopBar(
                onBackClick = callbacks.onBack,
                onMoreClick = if (callbacks.menuItems.isNotEmpty()) {
                    { showMenu = true }
                } else null,
            )
        },
        bottomBar = callbacks.bottomBar ?: {},
        modifier = modifier,
    ) { paddingValues ->
        TaskEditorBody(
            model = model,
            callbacks = callbacks,
            onShowSheet = { sheet -> activeSheet = sheet },
            modifier = Modifier.padding(paddingValues),
        )
    }

    TaskEditorMenuHost(
        menuItems = callbacks.menuItems,
        showMenu = showMenu,
        onDismiss = { showMenu = false },
    )

    TaskEditorSheetsHost(
        model = model,
        callbacks = callbacks,
        activeSheet = activeSheet,
        onSheetDismiss = { activeSheet = null },
    )
}
