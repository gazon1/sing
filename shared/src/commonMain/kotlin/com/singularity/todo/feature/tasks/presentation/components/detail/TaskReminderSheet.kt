package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.reminders.ReminderOffsetState
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.core.ui.components.sheet.SheetActions
import com.singularity.todo.core.ui.components.sheet.SheetScaffold
import com.singularity.todo.core.ui.components.sheet.rememberAppSheetState
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.action_cancel
import singularity_todo.shared.generated.resources.action_clear
import singularity_todo.shared.generated.resources.action_save
import singularity_todo.shared.generated.resources.notification_permission_action
import singularity_todo.shared.generated.resources.notification_settings_action
import singularity_todo.shared.generated.resources.notifications_disabled
import singularity_todo.shared.generated.resources.reminder_label
import singularity_todo.shared.generated.resources.reminder_needs_due_date
import singularity_todo.shared.generated.resources.reminder_unknown_offset

/**
 * The reminder editor of the task detail.
 *
 * ## Why the reminder has a sheet of its own
 *
 * A reminder is not a date. It is an offset *before* the due moment, it is
 * written by another slot with its own capability gate, and it needs the
 * notification permission — three things the date sheet has no room for and no
 * business knowing about. It is a fact of its own, and it gets a surface of its
 * own.
 *
 * ## What it commits, and when
 *
 * Nothing is written until «Сохранить». «Отмена» and a swipe-down write
 * nothing at all. The offset the sheet opens on is the task's current offset,
 * so the sheet shows what is already set rather than an empty choice.
 *
 * «Очистить» marks the removal and «Сохранить» applies it, so a cleared
 * reminder can still be called back: the same rule the date sheet follows.
 *
 * ## The two states the sheet has to say out loud
 *
 * *A reminder without a due date cannot be created.* The offset counts back
 * from the due moment, and a task without a date has no such moment — the
 * reminder would fire at once. So the sheet says that the date comes first, and
 * the slot refuses the write.
 *
 * *A reminder with an offset this build does not know is still a reminder.* It
 * was written by another build or another client. The sheet says that the
 * reminder exists, offers to remove it, and never rewrites it into the nearest
 * offset it happens to know.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TaskReminderSheet(
    currentState: ReminderOffsetState,
    hasDueDate: Boolean,
    notificationsEnabled: Boolean,
    onSelect: (ReminderOffset) -> Unit,
    onClear: () -> Unit,
    onRequestPermission: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    // The sheet opens expanded: a half-expanded sheet hides its bottom, and
    // the bottom of this sheet is where the actions are.
    val sheetState = rememberAppSheetState(initialValue = SheetValue.Expanded)

    // The offset the task carries, when this build knows it. An unknown offset
    // has none: the sheet cannot preselect a choice it does not have, and it
    // must not preselect the nearest one — that would rewrite the reminder the
    // moment the sheet is opened.
    val currentOffset = (currentState as? ReminderOffsetState.Known)?.offset

    BottomSheetHost(onDismiss = onDismiss, sheetState = sheetState) {
        // The pending offset. The sheet opens on the task's current offset, so
        // the choice is *shown* rather than empty — and «Отмена» leaves it there.
        var selected by remember { mutableStateOf(currentOffset) }
        // The pending removal. «Очистить» does not delete the reminder: it marks
        // the removal and only «Сохранить» applies it. A sheet that deletes the
        // moment it is cleared cannot be cancelled, and every other sheet in the
        // app applies its change on the confirm button.
        var removalPending by remember { mutableStateOf(false) }
        SheetScaffold(
            modifier = Modifier.testTag(TestTags.TASK_REMINDER_SHEET),
            actions =
                SheetActions(
                    onClear =
                        if (currentState != ReminderOffsetState.None) {
                            {
                                removalPending = true
                                selected = null
                            }
                        } else {
                            null
                        },
                    onCancel = onDismiss,
                    onConfirm = {
                        when {
                            // The removal the user asked for. It wins over the
                            // offset: a cleared reminder has no offset.
                            removalPending -> onClear()

                            // Nothing to write when the choice was left as it was:
                            // the offset the task already has is already there.
                            // An unknown offset is left alone as well: it is not
                            // an offset the sheet can write, and it must not
                            // become the nearest one it knows.
                            selected != currentOffset -> selected?.let { onSelect(it) }
                        }
                        onDismiss()
                    },
                    confirmLabel = stringResource(Res.string.action_save),
                    cancelLabel = stringResource(Res.string.action_cancel),
                    clearLabel = stringResource(Res.string.action_clear),
                    testTagConfirm = TestTags.TASK_REMINDER_CONFIRM,
                    testTagCancel = TestTags.TASK_REMINDER_CANCEL,
                    testTagClear = TestTags.TASK_REMINDER_CLEAR,
                ),
        ) {
            Text(
                text = stringResource(Res.string.reminder_label),
                style = MaterialTheme.typography.titleMedium,
            )

            // The permission notice.
            //
            // A refusal with no way out is a dead end, so the notice says what
            // is wrong *and* offers the two ways out: ask the system again, or
            // open the app's notification settings. Which of them is offered
            // depends on the platform — the desktop app has neither.
            if (!notificationsEnabled) {
                ReminderNotice(
                    onRequestPermission = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                )
            }

            // The offset counts back from the due moment, and a task without a
            // date has no such moment. The sheet says so before the user picks
            // an offset, because the slot refuses the write.
            if (!hasDueDate) {
                Text(
                    text = stringResource(Res.string.reminder_needs_due_date),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .padding(vertical = 8.dp)
                            .testTag(TestTags.TASK_REMINDER_NEEDS_DUE_DATE),
                )
            }

            // A reminder this build does not know the offset of. It exists, and
            // the sheet says so — a reminder shown as no reminder is a fact the
            // database holds and the screen does not.
            if (currentState is ReminderOffsetState.Unknown) {
                Text(
                    text = stringResource(Res.string.reminder_unknown_offset),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .padding(vertical = 8.dp)
                            .testTag(TestTags.TASK_REMINDER_UNKNOWN_OFFSET),
                )
            }

            // The offsets.
            //
            // `AT_DUE` is not among them: it is the "no reminder" selection in
            // the domain, and the sheet offers the removal as its own action.
            REMINDER_OFFSETS.forEach { offset ->
                ReminderOptionRow(
                    offset = offset,
                    selected = offset == selected,
                    // Picking an offset drops a pending removal: the user who
                    // clears and then chooses a time wants that time, not the
                    // removal.
                    onSelect = {
                        selected = offset
                        removalPending = false
                    },
                )
            }
        }
    }
}

/**
 * The notice that says the notifications are off, and the ways out.
 *
 * The ways out are optional because the desktop has neither: the desktop never
 * refuses a notification permission, so the notice is not shown there at all.
 */
@Composable
private fun ReminderNotice(
    onRequestPermission: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(Res.string.notifications_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .testTag(TestTags.TASK_REMINDER_NOTICE),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onRequestPermission != null) {
                TextButton(
                    onClick = onRequestPermission,
                    modifier =
                        Modifier
                            .testTag(TestTags.TASK_REMINDER_PERMISSION_ACTION)
                            .mapTestTagsAsResourceIds(),
                ) { Text(stringResource(Res.string.notification_permission_action)) }
            }
            if (onOpenSettings != null) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier =
                        Modifier
                            .testTag(TestTags.TASK_REMINDER_SETTINGS_ACTION)
                            .mapTestTagsAsResourceIds(),
                ) { Text(stringResource(Res.string.notification_settings_action)) }
            }
        }
    }
}

/**
 * One offset, as a row that is the radio button.
 *
 * The row *is* the radio: it carries the label, the tag and the action, and it
 * publishes which option is in effect — a radio button inside it would be a
 * second focusable with no name of its own.
 */
@Composable
private fun ReminderOptionRow(
    offset: ReminderOffset,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(reminderOptionTag(offset))
                .mapTestTagsAsResourceIds()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = reminderOffsetLabel(offset),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = selected,
            // The row above is the button: it carries the label, the tag and the
            // action. A radio button *inside* it would be a second focusable
            // with no name of its own.
            onClick = null,
        )
    }
}

/**
 * The offsets the sheet offers.
 *
 * The order is the one a person reads them in: the soonest first.
 */
private val REMINDER_OFFSETS =
    listOf(
        ReminderOffset.FIFTEEN_MIN,
        ReminderOffset.ONE_HOUR,
        ReminderOffset.ONE_DAY,
    )

/**
 * The test tag of an offset row.
 *
 * The tags are static constants rather than a tag built from the offset's
 * name: a tag that a test can only spell by repeating the domain's spelling
 * is a tag the selector gate cannot check.
 */
private fun reminderOptionTag(offset: ReminderOffset): String =
    when (offset) {
        ReminderOffset.FIFTEEN_MIN -> TestTags.TASK_REMINDER_OPTION_15_MIN
        ReminderOffset.ONE_HOUR -> TestTags.TASK_REMINDER_OPTION_1_HOUR
        ReminderOffset.ONE_DAY -> TestTags.TASK_REMINDER_OPTION_1_DAY
        ReminderOffset.AT_DUE -> error("AT_DUE is not an option of the reminder sheet")
    }
