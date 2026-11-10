package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.platform.systemToday
import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.TextMatchRange
import com.singularity.todo.core.search.inColumn
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.AiActionButton
import com.singularity.todo.core.ui.components.DeleteActionButton
import com.singularity.todo.core.ui.isAndroidPlatform
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.text.highlightedText
import com.singularity.todo.feature.tasks.domain.logic.computeTaskMarkings
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.list.TaskMarkingsRow
import com.singularity.todo.preview.PreviewSamples
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.LocalDate

/**
 * Card representation of a single task. Stateless — every piece of behavior is
 * supplied via [onClick] (whole-row tap) and [actions] (per-button callbacks).
 *
 * Supports slot customization via [body] and [trailing] parameters following
 * Material 3 naming convention (`body` for main content, `trailing` for actions).
 *
 * ## Two presentations
 *
 * On a pointer layout ([touchLayout] `false`, the default on Desktop) the wired
 * [actions] are drawn as trailing icon buttons: a 32 dp icon is a small target,
 * which a mouse handles and a finger does not. On a touch layout they are not
 * drawn at all — a trailing button row costs width on a narrow screen and
 * competes with the row's own tap target. The same actions open from a long
 * press instead, and are also announced as accessibility custom actions so a
 * screen-reader user does not need the long press.
 *
 * Card-level AI is not among the touch rows: the callback currently duplicates
 * navigation (see [com.singularity.todo.feature.tasks.presentation.components.TaskCardActions]).
 *
 * @param body    Main content slot — defaults to [DefaultTaskCardBody].
 * @param trailing Actions slot — defaults to [DefaultTaskCardTrailing] on a pointer layout and to nothing on a touch layout.
 * @param touchLayout Which presentation the card is drawn in. Android is a
 *   touch layout, Desktop is not.
 * @param matchRanges Where the search matched this task, if it was found by
 *   one. The card draws the range that falls in the title; a match in the
 *   description is not drawn in the title, because the title does not carry it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskCard(
    task: Task,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    actions: TaskCardActions = TaskCardActions(),
    touchLayout: Boolean = isAndroidPlatform,
    modifier: Modifier = Modifier,
    matchRanges: ImmutableList<TextMatchRange> = persistentListOf(),
    today: LocalDate = systemToday(),
    blockedTaskIds: ImmutableSet<TaskId> = persistentSetOf(),
    isBlocked: Boolean = false,
    markings: ImmutableSet<TaskMarking>? = null,
    body: @Composable RowScope.() -> Unit = {
        DefaultTaskCardBody(task, actions, matchRanges, today, blockedTaskIds, isBlocked, markings)
    },
    trailing: @Composable RowScope.() -> Unit = {
        if (!touchLayout) DefaultTaskCardTrailing(task, actions)
    },
) {
    var actionSheetOpen by remember { mutableStateOf(false) }
    // shortcut: a hardware keyboard reaches these only through the accessibility
    // action menu, upgrade if a keyboard user needs the rows without a long press.
    val touchActions = remember(task.isPinned, actions, touchLayout) { touchActionRows(task, actions, touchLayout) }
    val hasTouchActions = touchActions.isNotEmpty()

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.taskItem(task.title))
                    .semantics { customActions = touchActions }
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick =
                            onLongClick ?: if (hasTouchActions) {
                                { actionSheetOpen = true }
                            } else {
                                null
                            },
                    ),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (task.isPinned) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                body()
                Spacer(modifier = Modifier.padding(start = 4.dp))
                trailing()
            }
        }

        if (hasTouchActions) {
            DropdownMenu(
                expanded = actionSheetOpen,
                onDismissRequest = { actionSheetOpen = false },
                modifier = Modifier.mapTestTagsAsResourceIds(),
            ) {
                actions.onPin?.let { onPin ->
                    DropdownMenuItem(
                        text = { Text(if (task.isPinned) "Unpin" else "Pin") },
                        onClick = {
                            actionSheetOpen = false
                            onPin()
                        },
                        modifier = Modifier.testTag(TestTags.TASK_CARD_ACTION_PIN),
                    )
                }
                actions.onDelete?.let { onDelete ->
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            actionSheetOpen = false
                            onDelete()
                        },
                        modifier = Modifier.testTag(TestTags.TASK_CARD_ACTION_DELETE),
                    )
                }
            }
        }
    }
}

/**
 * The actions a touch row offers, as accessibility custom actions — empty on a
 * pointer layout, where the trailing buttons are the way in.
 */
private fun touchActionRows(
    task: Task,
    actions: TaskCardActions,
    touchLayout: Boolean,
): List<CustomAccessibilityAction> {
    if (!touchLayout) return emptyList()
    return buildList {
        actions.onPin?.let { onPin ->
            add(
                CustomAccessibilityAction(label = if (task.isPinned) "Unpin" else "Pin") {
                    onPin()
                    true
                },
            )
        }
        actions.onDelete?.let { onDelete ->
            add(
                CustomAccessibilityAction(label = "Delete") {
                    onDelete()
                    true
                },
            )
        }
    }
}

/**
 * Default body content for [TaskCard] — toggle, title text, and meta chips.
 * Marked as [Composable][Composable] with [RowScope] receiver so callers
 * can use [Modifier.weight] inside the slot.
 */
@Composable
internal fun RowScope.DefaultTaskCardBody(
    task: Task,
    actions: TaskCardActions,
    matchRanges: ImmutableList<TextMatchRange> = persistentListOf(),
    today: LocalDate = systemToday(),
    blockedTaskIds: ImmutableSet<TaskId> = persistentSetOf(),
    isBlocked: Boolean = false,
    markings: ImmutableSet<TaskMarking>? = null,
) {
    val computedMarkings: ImmutableSet<TaskMarking> =
        markings
            ?: computeTaskMarkings(task, today, isBlocked || task.id in blockedTaskIds)
    actions.onToggle?.let { onToggle ->
        ToggleButton(isCompleted = task.isCompleted, onClick = onToggle, taskTitle = task.title)
    }
    TaskText(task = task, modifier = Modifier.weight(1f), matchRanges = matchRanges)
    if (task.parentTaskId != null) {
        SubtaskChip()
    }
    PriorityChip(priority = task.priority)
    TaskMarkingsRow(markings = computedMarkings)
}

/**
 * Default trailing actions for [TaskCard] — pin, AI, delete buttons.
 * Each button is conditionally rendered only when the corresponding callback is non-null.
 */
@Composable
internal fun DefaultTaskCardTrailing(
    task: Task,
    actions: TaskCardActions,
) {
    actions.onPin?.let {
        PinButton(
            isPinned = task.isPinned,
            onClick = it,
            modifier = Modifier.testTag(TestTags.TASK_CARD_PIN_BUTTON),
        )
    }
    actions.onAiClick?.let { AiActionButton(onClick = it) }
    actions.onDelete?.let {
        DeleteActionButton(
            onClick = it,
            modifier = Modifier.testTag(TestTags.TASK_CARD_DELETE_BUTTON),
        )
    }
}

@Composable
private fun ToggleButton(
    isCompleted: Boolean,
    onClick: () -> Unit,
    taskTitle: String = "",
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.testTag(TestTags.taskCheckbox(taskTitle)),
    ) {
        Icon(
            imageVector = if (isCompleted) Icons.Filled.Check else Icons.Filled.RadioButtonUnchecked,
            contentDescription = "Toggle complete",
            tint =
                if (isCompleted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun TaskText(
    task: Task,
    modifier: Modifier = Modifier,
    matchRanges: ImmutableList<TextMatchRange> = persistentListOf(),
) {
    Column(modifier = modifier) {
        Text(
            text = highlightedText(task.title, matchRanges.inColumn(SearchColumn.TITLE)),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        task.dueDate?.let { date ->
            Text(
                text = date.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PinButton(
    isPinned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.PushPin,
            contentDescription = if (isPinned) "Unpin" else "Pin",
            tint =
                if (isPinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/**
 * Marker for tasks that are sub-tasks (have a parentTaskId).
 *
 * A plain label, not an [AssistChip]: a chip is a control with a ripple and a pressed
 * state, and this is a static badge. The previous `AssistChip(onClick = {})` was
 * tappable and inert — tapping the badge did nothing at all.
 */
@Composable
private fun SubtaskChip(modifier: Modifier = Modifier) {
    Text(
        text = "Sub-task",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskCardLightPreview() =
    PreviewThemed(darkTheme = false) {
        TaskCard(
            task = PreviewSamples.task(),
            onClick = {},
            onLongClick = {},
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskCardCompletedDarkPreview() =
    PreviewThemed(darkTheme = true) {
        TaskCard(
            task = PreviewSamples.task(completed = true),
            onClick = {},
            onLongClick = {},
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskCardPinnedPurpleDarkPreview() =
    PreviewThemed(
        darkTheme = true,
        accent = com.singularity.todo.core.ui.theme.SingularityAccents.Purple,
    ) {
        TaskCard(
            task = PreviewSamples.task(pinned = true, priority = TaskPriority.High),
            onClick = {},
            onLongClick = {},
        )
    }
