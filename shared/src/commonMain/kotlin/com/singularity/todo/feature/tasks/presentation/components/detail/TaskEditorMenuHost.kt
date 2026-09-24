package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Hosts the overflow [DropdownMenu] for [TaskEditorContent].
 * Rendered alongside the body; visibility controlled by [showMenu].
 */
@Composable
fun TaskEditorMenuHost(
    menuItems: List<TaskEditorMenuItem>,
    showMenu: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (menuItems.isNotEmpty()) {
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = onDismiss,
            modifier = modifier,
        ) {
            menuItems.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        item.onClick()
                        onDismiss()
                    },
                )
            }
        }
    }
}
