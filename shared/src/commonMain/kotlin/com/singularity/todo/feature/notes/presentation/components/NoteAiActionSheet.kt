package com.singularity.todo.feature.notes.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.notes.NoteAiAction

/**
 * Bottom sheet listing all available Note AI actions.
 * Shown when the user taps the AI button in the note editor toolbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteAiActionSheet(onSelect: (NoteAiAction) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "AI Actions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // Improve (most common — shown first)
            AiActionItem(
                icon = Icons.Filled.AutoAwesome,
                title = "Improve writing",
                subtitle = "Polish clarity, conciseness, and readability",
                onClick = { onSelect(NoteAiAction.Improve) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Content actions
            AiActionItem(
                icon = Icons.Filled.Summarize,
                title = "Summarize",
                subtitle = "One-sentence summary of the note",
                onClick = { onSelect(NoteAiAction.Summarize) },
            )

            AiActionItem(
                icon = Icons.Filled.Checklist,
                title = "Extract actions",
                subtitle = "Find actionable tasks in this note",
                onClick = { onSelect(NoteAiAction.ExtractActions) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Rewrite section
            Text(
                text = "Rewrite",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            AiActionItem(
                icon = Icons.Filled.Edit,
                title = "One-liner",
                subtitle = "Short, punchy paragraph",
                onClick = { onSelect(NoteAiAction.RewriteOneLiner) },
            )

            AiActionItem(
                icon = Icons.Filled.Description,
                title = "TL;DR",
                subtitle = "Concise summary with key takeaways",
                onClick = { onSelect(NoteAiAction.RewriteTldr) },
            )

            AiActionItem(
                icon = Icons.Filled.FormatListBulleted,
                title = "Structured",
                subtitle = "Headings, bullets, and sections",
                onClick = { onSelect(NoteAiAction.RewriteStructured) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Tags
            AiActionItem(
                icon = Icons.AutoMirrored.Filled.Label,
                title = "Suggest tags",
                subtitle = "AI-powered tag recommendations",
                onClick = { onSelect(NoteAiAction.SuggestTags) },
            )
        }
    }
}

@Composable
private fun AiActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        },
        modifier = modifier,
    )
}
