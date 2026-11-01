package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.attachments.AttachmentTile
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.components.ChecklistItemRow
import com.singularity.todo.feature.checklist.components.ChecklistRowTags
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import kotlinx.collections.immutable.ImmutableList

// ─── Recurrence ────────────────────────────────────────────────────────────────

@Composable
fun TaskDetailRecurrenceSection(recurrence: RecurrenceSpec, modifier: Modifier = Modifier) {
    ExtraSectionCard(
        icon = { Icon(Icons.Filled.Repeat, contentDescription = null) },
        label = "Repeats",
        content = {
            Text(
                text = recurrence.toString(),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        modifier = modifier,
    )
}

// ─── Checklist ────────────────────────────────────────────────────────────────

@Suppress("FunctionSignature")
@Composable
fun TaskDetailChecklistSection(
    checklist: ImmutableList<ChecklistItem>,
    onToggle: (ChecklistItem) -> Unit,
    onDelete: (ChecklistItemId) -> Unit,
    onAddItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newTitleState = rememberTextFieldState()
    val completed = checklist.count { it.isCompleted }

    // The section always renders, empty list or not. It is the only place the
    // checklist can be edited from the task screen, so a section that hid itself
    // when the list was empty left the user with no way to add the first item —
    // and no way to say "this task has no checklist" either.
    ExtraSectionCard(
        modifier = modifier.testTag(TestTags.TASK_CHECKLIST_SECTION),
        icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
        label = "Checklist ($completed/${checklist.size})",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The add row — always present, so the first item can be added
                // without leaving the screen.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        state = newTitleState,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TestTags.CHECKLIST_INLINE_ADD_INPUT),
                        placeholder = { Text("Add item...", style = MaterialTheme.typography.bodySmall) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        onKeyboardAction = {
                            val trimmed = newTitleState.text.toString().trim()
                            if (trimmed.isNotEmpty()) {
                                onAddItem(trimmed)
                                newTitleState.clearText()
                            }
                        },
                    )
                    IconButton(
                        onClick = {
                            val trimmed = newTitleState.text.toString().trim()
                            if (trimmed.isNotEmpty()) {
                                onAddItem(trimmed)
                                newTitleState.clearText()
                            }
                        },
                        modifier = Modifier
                            .touchTarget()
                            .size(40.dp)
                            .testTag(TestTags.CHECKLIST_INLINE_ADD_BUTTON),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add item",
                        )
                    }
                }
                // No cap: a task with fourteen checklist items shows fourteen of
                // them. The list used to show ten and say "+4 more", which is a
                // truncation the user cannot act on from the screen they are on.
                checklist.forEach { item ->
                    ChecklistItemRow(
                        text = item.title,
                        checked = item.isCompleted,
                        onToggle = { onToggle(item) },
                        onDelete = { onDelete(item.id) },
                        tags = ChecklistRowTags.forChecklistItem(id = item.id.value, title = item.title),
                    )
                }
            }
        },
    )
}

// ─── Attachments ─────────────────────────────────────────────────────────────

/**
 * The task's attachments, listed in full.
 *
 * This used to `take(5)` and say nothing: a task with seven attachments showed five,
 * with no "+2 more", no scroll and no way to reach the rest. Silently dropping two
 * rows is a different failure from showing a truncated list, so the cap is gone.
 *
 * The rows are [AttachmentTile] rather than bare `Text`. The tile was implemented,
 * previewed and called by nothing outside its own file — it renders the thumbnail,
 * the file size and a type-correct icon, none of which the text row did.
 *
 * @param onOpenAttachments opens the attachments sheet — the one place that can add a
 *   file or a URL. `null` hides the row, because a row that opens nothing is worse
 *   than no row at all.
 * @param onOpen navigates to the viewer for one attachment. `null` renders rows that
 *   cannot be tapped, so no row advertises an interaction it does not have.
 */
@Composable
fun TaskDetailAttachmentsSection(
    attachments: ImmutableList<Attachment>,
    onOpenAttachments: (() -> Unit)? = null,
    onDelete: ((AttachmentId) -> Unit)? = null,
    onOpen: ((AttachmentId) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty() && onOpenAttachments == null) return
    ExtraSectionCard(
        icon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
        label = "Attachments (${attachments.size})",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                attachments.forEach { att ->
                    AttachmentTile(
                        attachment = att,
                        onDelete = { onDelete?.invoke(att.id) },
                        // A URL attachment has no file behind it, so it gets no open
                        // affordance rather than a row that goes nowhere.
                        onOpen = onOpen?.takeIf { !att.isUrl }?.let { open -> { open(att.id) } },
                    )
                }
                if (onOpenAttachments != null) {
                    Text(
                        text = "+ Add file",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .touchTarget()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable(onClick = onOpenAttachments)
                            .testTag(TestTags.ATTACHMENTS_ADD_FILE_ROW),
                    )
                }
            }
        },
        modifier = modifier,
    )
}
