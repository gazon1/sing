package com.singularity.todo.feature.agenda.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.DragHandleIcon
import com.singularity.todo.core.ui.components.DragHandleRow
import com.singularity.todo.core.ui.components.HandleSide
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.selector.typeDescription

/**
 * A section editor card shown in the "edit agenda" screen.
 *
 * Displays the section name and selector description, with a drag handle
 * for reordering and a delete button.
 *
 * @param section The section to display.
 * @param index The section's position in the list.
 * @param onDelete Called when the user taps the delete button.
 * @param modifier Compose modifier.
 */
@Composable
fun SectionEditorCard(section: Section, index: Int, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        DragHandleRow(
            text = section.name,
            subtitle = section.selector.typeDescription,
            handleSide = HandleSide.Leading,
            padding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp,
                end = 4.dp,
                top = 4.dp,
                bottom = 4.dp,
            ),
            handle = {
                Box(modifier = Modifier.padding(8.dp)) {
                    DragHandleIcon()
                }
            },
            trailing = {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Delete section",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}
