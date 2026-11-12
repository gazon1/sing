package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.localizedDueDateWithTime
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.add_deadline
import singularity_todo.shared.generated.resources.notifications_disabled
import singularity_todo.shared.generated.resources.overdue_label
import singularity_todo.shared.generated.resources.reminder_label
import singularity_todo.shared.generated.resources.reminder_offset_15_min
import singularity_todo.shared.generated.resources.reminder_offset_1_day
import singularity_todo.shared.generated.resources.reminder_offset_1_hour
import singularity_todo.shared.generated.resources.reminder_offset_at_due
import singularity_todo.shared.generated.resources.reset_deadline
import singularity_todo.shared.generated.resources.reset_reminder

/**
 * The localized name of a reminder offset.
 *
 * The offset enum carries an English label, and an English label in a Russian
 * app is the defect this row's sibling already had once (#1700). The wire name
 * stays in the enum — the tag of an option row is built from it, because a tag
 * built from a translatable string breaks on every device set to another
 * language.
 */
@Composable
fun reminderOffsetLabel(offset: ReminderOffset): String =
    when (offset) {
        ReminderOffset.AT_DUE -> stringResource(Res.string.reminder_offset_at_due)
        ReminderOffset.FIFTEEN_MIN -> stringResource(Res.string.reminder_offset_15_min)
        ReminderOffset.ONE_HOUR -> stringResource(Res.string.reminder_offset_1_hour)
        ReminderOffset.ONE_DAY -> stringResource(Res.string.reminder_offset_1_day)
    }

/**
 * The reminder row of the task detail.
 *
 * It shows three different things, and the difference matters:
 *
 * * the offset, when the task has a reminder;
 * * «Напоминание», when it has none — the row is still there, because a row
 *   that appears only once something is set is a row nobody finds;
 * * «Уведомления выключены», when the app may not post notifications. The
 *   reminder is *not* absent then — it is there and it cannot fire, and saying
 *   so is the difference between a missing feature and a broken one.
 *
 * The offset is shown even when the notifications are off: a reminder that is
 * set and cannot fire is still a reminder, and a row that swapped its value
 * for a warning would hide the very thing the user came to check.
 */
@Composable
fun TaskEditorReminderRow(
    reminderOffset: ReminderOffset?,
    notificationsEnabled: Boolean,
    onReminderClick: (() -> Unit)?,
    onReminderClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val label =
        when {
            reminderOffset != null -> {
                reminderOffsetLabel(reminderOffset) +
                    if (notificationsEnabled) {
                        ""
                    } else {
                        " · " + stringResource(Res.string.notifications_disabled)
                    }
            }

            !notificationsEnabled -> {
                stringResource(Res.string.notifications_disabled)
            }

            else -> {
                stringResource(Res.string.reminder_label)
            }
        }
    PlanningRow(
        icon = Icons.Outlined.Notifications,
        label = label,
        isSet = reminderOffset != null,
        rowTestTag = TestTags.TASK_EDITOR_REMINDER_ROW,
        labelTestTag =
            if (reminderOffset != null) {
                TestTags.TASK_EDITOR_REMINDER_ROW_LABEL
            } else {
                TestTags.TASK_EDITOR_REMINDER_ROW_PLACEHOLDER
            },
        onClick = onReminderClick,
        onClear = onReminderClear,
        clearContentDescription = stringResource(Res.string.reset_reminder),
        modifier = modifier,
    )
}

/**
 * The deadline row of the task detail.
 *
 * An overdue deadline says so **in words**. Colour alone is not a fact a
 * screen reader reads out and not a fact a person with a colour-vision
 * deficiency can see, so the word «Просрочен» is part of the label.
 */
@Composable
fun TaskEditorDeadlineRow(
    deadline: LocalDate?,
    isOverdue: Boolean,
    onDeadlineClick: (() -> Unit)?,
    onDeadlineClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val label =
        when {
            deadline == null -> {
                stringResource(Res.string.add_deadline)
            }

            isOverdue -> {
                localizedDueDateWithTime(deadline, null) +
                    " · " + stringResource(Res.string.overdue_label)
            }

            else -> {
                localizedDueDateWithTime(deadline, null)
            }
        }
    PlanningRow(
        icon = Icons.Outlined.Flag,
        label = label,
        isSet = deadline != null,
        rowTestTag = TestTags.TASK_EDITOR_DEADLINE_ROW,
        labelTestTag =
            if (deadline != null) {
                TestTags.TASK_EDITOR_DEADLINE_ROW_LABEL
            } else {
                TestTags.TASK_EDITOR_DEADLINE_ROW_PLACEHOLDER
            },
        onClick = onDeadlineClick,
        onClear = onDeadlineClear,
        clearContentDescription = stringResource(Res.string.reset_deadline),
        modifier = modifier,
    )
}

/**
 * The shape every planning row of the task detail has: an icon, a label and
 * an optional ×.
 *
 * One shape for the three rows, so the reminder and the deadline sit in the
 * list the way the due date does instead of each inventing its own.
 */
@Composable
private fun PlanningRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSet: Boolean,
    rowTestTag: String,
    labelTestTag: String,
    onClick: (() -> Unit)?,
    onClear: (() -> Unit)?,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(rowTestTag)
                .mapTestTagsAsResourceIds()
                // No clickable() at all when the callback is null — see TaskEditorPriorityRow.
                .then(
                    if (onClick != null) {
                        Modifier.clickable { onClick() }
                    } else {
                        Modifier
                    },
                ).padding(
                    horizontal = TaskSpacing.cardPaddingHorizontal,
                    vertical = TaskSpacing.cardPaddingVertical,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint =
                if (isSet) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        Text(
            text = label,
            color =
                if (isSet) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            fontSize = 16.sp,
            fontWeight = if (isSet) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f).testTag(labelTestTag),
        )
        if (isSet && onClear != null) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = clearContentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}
