package com.singularity.todo.feature.agenda.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView

/**
 * A card representing a single saved agenda view.
 * Stateless — all interactions are forwarded through callbacks.
 *
 * @param view The saved agenda view to display.
 * @param onClick Called when the user taps the card body.
 * @param onDelete Called when the user taps the delete button in the overflow menu.
 * @param onEdit Called when the user taps "Edit" in the overflow menu.
 * @param onCopyToProfile Called when the user taps "Copy to profile" in the overflow menu.
 * @param modifier Compose modifier.
 */
@Composable
fun SavedAgendaCard(
    view: SavedAgendaView,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onCopyToProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.savedAgendaCard(view.name))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = view.name.ifBlank { "(Unnamed)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Updated ${view.updatedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = { menuExpanded = true }) {
                Text(
                    text = "⋮",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.SAVED_AGENDA_OVERFLOW_BUTTON),
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        modifier = Modifier.testTag(TestTags.SAVED_AGENDA_MENU_EDIT),
                    )
                    DropdownMenuItem(
                        text = { Text("Copy to profile") },
                        onClick = {
                            menuExpanded = false
                            onCopyToProfile()
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        modifier = Modifier.testTag(TestTags.SAVED_AGENDA_MENU_COPY_TO_PROFILE),
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        modifier = Modifier.testTag(TestTags.SAVED_AGENDA_MENU_DELETE),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun SavedAgendaCardPreview() = PreviewThemed(darkTheme = false) {
    SavedAgendaCard(
        view = PreviewSamples.savedAgendaView(name = "Weekly Review"),
        onClick = {},
        onDelete = {},
        onEdit = {},
        onCopyToProfile = {},
    )
}

@Preview
@Composable
private fun SavedAgendaCardDarkPreview() = PreviewThemed(darkTheme = true) {
    SavedAgendaCard(
        view = PreviewSamples.savedAgendaView(name = "Weekly Review"),
        onClick = {},
        onDelete = {},
        onEdit = {},
        onCopyToProfile = {},
    )
}

@Preview
@Composable
private fun SavedAgendaCardUnnamedPreview() = PreviewThemed(darkTheme = false) {
    SavedAgendaCard(
        view = PreviewSamples.savedAgendaView(name = ""),
        onClick = {},
        onDelete = {},
        onEdit = {},
        onCopyToProfile = {},
    )
}
