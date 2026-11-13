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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.sectionTitle
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.attachments.AttachmentTile
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistMove
import com.singularity.todo.feature.checklist.components.ChecklistItemRow
import com.singularity.todo.feature.checklist.components.ChecklistRowReorder
import com.singularity.todo.feature.checklist.components.ChecklistRowTags
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.presentation.components.RecurrenceSentence
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.LocalDate

// ─── Recurrence ────────────────────────────────────────────────────────────────

/**
 * The rule the task repeats on, read as a sentence.
 *
 * The row used to print the rule itself — "Interval(base=FROM_DUE, amount=3,
 * unit=DAY, termination=null)" — which is the rule's own `toString()` and says
 * nothing a person can act on. It says what repeats now.
 *
 * @param dueDate The task's due date. The rule hangs on it when its base says
 *        so, and then the sentence says when the series starts; when the task
 *        has no due date, the sentence says nothing about a start. It has no
 *        default: a caller that forgets it would quietly get a rule without a
 *        start date, and "no due date" is a thing to say out loud.
 */
@Composable
fun TaskDetailRecurrenceSection(
    recurrence: RecurrenceSpec,
    dueDate: LocalDate?,
    modifier: Modifier = Modifier,
) {
    ExtraSectionCard(
        icon = { Icon(Icons.Filled.Repeat, contentDescription = null) },
        label = "Repeats",
        content = {
            Text(
                text = RecurrenceSentence(recurrence, dueDate),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(TestTags.TASK_RECURRENCE_TEXT),
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
    onMove: ((ChecklistItemId, ChecklistMove) -> Unit)? = null,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpanded: (() -> Unit)? = null,
) {
    val newTitleState = rememberTextFieldState()
    val completed = checklist.count { it.isCompleted }
    // Two different expansions, kept apart. The section's own open state belongs to
    // the screen, which remembers it; the long list's "show all" belongs to the
    // list, and the screen has no reason to remember it.
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visibleItems = if (!showAll && checklist.size > 5) checklist.take(5) else checklist

    // The section always renders, empty list or not: the header is the way to
    // write into it, so a section that hid itself when the list was empty would
    // take the way in with it. What it must not do is look like a leftover — an
    // empty list shows no count over nothing, and the header is one line until
    // the section is opened.
    ExtraSectionCard(
        modifier = modifier,
        headerTestTag = TestTags.TASK_CHECKLIST_SECTION,
        icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
        label = sectionTitle("Checklist", checklist.size, completed),
        expanded = expanded,
        onToggle = onToggleExpanded,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (checklist.isEmpty()) {
                    EmptySectionHint(
                        text = "No checklist items yet. Add the first one below.",
                        modifier = Modifier.testTag(TestTags.CHECKLIST_EMPTY_HINT),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { if (checklist.isEmpty()) 0f else completed.toFloat() / checklist.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                visibleItems.forEach { item ->
                    val index = checklist.indexOf(item)
                    ChecklistItemRow(
                        text = item.title,
                        checked = item.isCompleted,
                        onToggle = { onToggle(item) },
                        onDelete = { onDelete(item.id) },
                        // Reordering is a checklist-only affordance: a subtask's
                        // order has no persisted field to restore it from, so the
                        // subtasks section passes no onMove and shows no controls.
                        reorder =
                            onMove?.takeIf { checklist.size > 1 }?.let { move ->
                                ChecklistRowReorder(
                                    canMoveUp = index > 0,
                                    canMoveDown = index in 0 until checklist.lastIndex,
                                    moveUpTag = TestTags.checklistItemMoveUp(id = item.id.value, title = item.title),
                                    moveDownTag =
                                        TestTags.checklistItemMoveDown(
                                            id = item.id.value,
                                            title = item.title,
                                        ),
                                    onMove = { direction -> move(item.id, direction) },
                                )
                            },
                        tags = ChecklistRowTags.forChecklistItem(id = item.id.value, title = item.title),
                    )
                }

                if (!showAll && checklist.size > 5) {
                    TextButton(
                        onClick = { showAll = true },
                        modifier = Modifier.testTag(TestTags.CHECKLIST_SHOW_ALL),
                    ) {
                        Text("Показать все ${checklist.size}")
                    }
                }

                // The add row — after existing items so new content stays in reading order.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        state = newTitleState,
                        modifier =
                            Modifier
                                .weight(1f)
                                .bringIntoViewOnFocus(revision = checklist.size)
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
                        modifier =
                            Modifier
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
    expanded: Boolean = false,
    onToggleExpanded: (() -> Unit)? = null,
) {
    if (attachments.isEmpty() && onOpenAttachments == null) return
    ExtraSectionCard(
        modifier = modifier,
        headerTestTag = TestTags.ATTACHMENTS_SECTION,
        icon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
        // No count over nothing: an empty list says "Attachments", and the add-row
        // below is the way in.
        label = sectionTitle("Attachments", attachments.size),
        expanded = expanded,
        onToggle = onToggleExpanded,
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
                        modifier =
                            Modifier
                                .touchTarget()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable(onClick = onOpenAttachments)
                                .testTag(TestTags.ATTACHMENTS_ADD_FILE_ROW),
                    )
                }
            }
        },
    )
}
