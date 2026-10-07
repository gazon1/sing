package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * First-run suggestion banner shown on newly created tasks that have no content yet.
 * Displays up to three action chips: Write note, Add checklist, Ask AI.
 *
 * Every handler is nullable and a chip with no handler is not rendered.
 *
 * `TaskDetailContent` passed `onWriteNote = { /* scroll to body */ }` and
 * `onAddChecklist = { /* expand checklist */ }` — two visible chips on a card titled
 * "Quick actions" that did nothing when pressed. The comments described work nobody
 * had done. A chip that cannot act is not a suggestion, it is a broken promise, so the
 * banner now shows only the actions this screen can actually perform.
 *
 * Wiring the other two needs scroll state the detail screen does not keep; it is
 * tracked in the deferred backlog rather than shipped as a button that lies.
 */
@Composable
fun FirstRunSection(
    onWriteNote: (() -> Unit)? = null,
    onAddChecklist: (() -> Unit)? = null,
    onAskAi: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Quick actions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onWriteNote?.let { action ->
                    FirstRunChip(
                        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        label = "Write note",
                        onClick = action,
                        modifier = Modifier.weight(1f),
                    )
                }
                onAddChecklist?.let { action ->
                    FirstRunChip(
                        icon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                        label = "Add checklist",
                        onClick = action,
                        modifier = Modifier.weight(1f),
                    )
                }
                onAskAi?.let { action ->
                    FirstRunChip(
                        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                        label = "Ask AI",
                        onClick = action,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FirstRunChip(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            icon()
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
