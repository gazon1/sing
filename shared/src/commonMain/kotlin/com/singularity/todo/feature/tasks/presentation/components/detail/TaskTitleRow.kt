package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.celebration.Celebration
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.theme.placeholderTextColor
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.mark_task_complete
import singularity_todo.shared.generated.resources.task_completed
import singularity_todo.shared.generated.resources.task_title_placeholder

/**
 * Заголовок задачи: чекбокс завершения + инлайн-редактируемое поле названия.
 * Клик по чекбоксу — отдельный target от поля ввода (не пересекаются).
 *
 * [onCheckToggle] is nullable and `null` hides the checkbox entirely. Create mode
 * passed `{}`, which rendered a completion checkbox — the screen's most prominent
 * control — that did nothing when pressed. There is no task to complete before it
 * exists, so the honest rendering is no checkbox, not a dead one.
 *
 * @param titleState the title's field state. The owner built the source and bridged it,
 *   and the kind of that source — static or owned — is decided by the caller, at compile
 *   time. This component shows the field and does not decide who may rewrite it.
 */
@Composable
fun TaskTitleRow(
    taskId: String,
    titleState: TextFieldState,
    isCompleted: Boolean,
    onCheckToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleError: String? = null,
    completedLabel: String? = null,
    /**
     * Whether the title is always a field.
     *
     * Create is: an empty draft's whole job is to take a title, and a title you have
     * to tap before typing is a tap between the user and the thing they came to do.
     * The detail is not: it shows a task, and the field appears when the title is
     * tapped — the document-style rule this screen follows.
     */
    alwaysEditable: Boolean = false,
) {
    // The caller's modifier is the whole block's: the message is part of the title,
    // so the caller's padding or width has to cover the row and the line under it.
    // No call site passes a modifier today (TaskEditorContent, and the previews below).
    Column(modifier = modifier) {
        TitleFieldRow(
            taskId = taskId,
            titleState = titleState,
            isCompleted = isCompleted,
            onCheckToggle = onCheckToggle,
            alwaysEditable = alwaysEditable,
        )

        // The completion date: a finished task says when it finished, in words.
        // Drawn under the title rather than in place of a row, because the rows
        // are what the task *is* and the date is what happened to it.
        if (completedLabel != null) {
            Text(
                text = completedLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .padding(top = TaskSpacing.sm)
                        .testTag(TestTags.TASK_DETAIL_COMPLETED_LABEL),
            )
        }

        // The message sits under the field, in the same place whatever the
        // screen: the create editor and the detail editor hand the row the same
        // string, so "the title is missing" reads the same in both. It is a
        // separate node rather than a decoration of the field, because a second
        // `testTag` on the field's modifier chain is dropped by the semantics
        // merge — the tag would exist in `TestTags` and nowhere in the tree.
        if (titleError != null) {
            Text(
                text = titleError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .padding(top = TaskSpacing.md)
                        .testTag(TestTags.TASK_EDITOR_TITLE_ERROR),
            )
        }
    }
}

@Composable
private fun TitleFieldRow(
    taskId: String,
    titleState: TextFieldState,
    isCompleted: Boolean,
    onCheckToggle: (() -> Unit)?,
    alwaysEditable: Boolean,
) {
    // Whether the title is a field right now. Create starts in it; the detail starts
    // showing the title as text and enters the field when the title is tapped. One
    // local flag, not a state holder: the value still lives in the caller's field
    // state, and this decides only how it is drawn.
    var editing by remember(alwaysEditable, taskId) { mutableStateOf(alwaysEditable) }
    val focusRequester = remember { FocusRequester() }
    // Focus follows a *tap*, not the field's existence. Create composes the field
    // immediately and must not grab the keyboard on its own: the flows that fill the
    // create editor scroll it into view first, and an IME opening under them changes
    // what that scroll means.
    var focusRequested by remember { mutableStateOf(false) }
    // A field reports "not focused" the first time it composes, before any focus has
    // arrived. Treating that as a blur would close the field in the same frame the tap
    // opened it, so the exit waits for focus to have been held and then lost.
    var hasHeldFocus by remember { mutableStateOf(false) }

    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            focusRequester.requestFocus()
            focusRequested = false
        }
    }

    Row(
        // Top, not centre: the title wraps to three lines now, and a checkbox floating
        // in the middle of a three-line title is not on the line the eye starts at.
        verticalAlignment = Alignment.Top,
    ) {
        if (onCheckToggle != null) {
            CompletionCheckbox(
                taskId = taskId,
                isCompleted = isCompleted,
                onCheckToggle = onCheckToggle,
            )
            Spacer(modifier = Modifier.width(TaskSpacing.md))
        }
        val titleStyle =
            MaterialTheme.typography.titleLarge.copy(
                // A completed task reads as done without looking at the checkbox:
                // struck through and dimmed. Colour alone would not be a fact a
                // screen reader, or an eye that cannot see the dimming, receives.
                color =
                    if (isCompleted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
            )

        if (editing) {
            TitleEditor(
                titleState = titleState,
                titleStyle = titleStyle,
                focusRequester = focusRequester,
                onFocusLost = {
                    if (hasHeldFocus) {
                        hasHeldFocus = false
                        editing = alwaysEditable
                    }
                },
                onFocusGained = { hasHeldFocus = true },
            )
        } else {
            TitleText(
                titleState = titleState,
                titleStyle = titleStyle,
                onClick = {
                    editing = true
                    focusRequested = true
                },
            )
        }
    }
}

/**
 * The completion toggle, with the celebration that fires when the task crosses into
 * done. Its own composable so the title row reads as one decision — how the title is
 * drawn — rather than as a row, a checkbox and a style.
 */
@Composable
private fun CompletionCheckbox(
    taskId: String,
    isCompleted: Boolean,
    onCheckToggle: () -> Unit,
) {
    Celebration(triggerKey = if (isCompleted) taskId else "") {
        IconButton(onClick = onCheckToggle) {
            Icon(
                imageVector =
                    if (isCompleted) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Outlined.CheckBoxOutlineBlank
                    },
                contentDescription =
                    if (isCompleted) {
                        stringResource(Res.string.task_completed)
                    } else {
                        stringResource(Res.string.mark_task_complete)
                    },
                tint =
                    if (isCompleted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(TaskSpacing.iconSizeLarge),
            )
        }
    }
}

/**
 * The title as a field, for editing and for Create.
 *
 * No line cap here: a person fixing a long title has to see the whole title they are
 * fixing. The three-line cap is a display rule for [TitleText], not a limit on the text.
 */
@Composable
private fun RowScope.TitleEditor(
    titleState: TextFieldState,
    titleStyle: TextStyle,
    focusRequester: FocusRequester,
    onFocusGained: () -> Unit,
    onFocusLost: () -> Unit,
) {
    BasicTextField(
        state = titleState,
        textStyle = titleStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1),
        inputTransformation = NoNewlines,
        modifier =
            Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .bringIntoViewOnFocus()
                // A field reports "not focused" the first time it composes, before any
                // focus has arrived. Treating that as a blur would close the field in
                // the same frame the tap opened it, so the exit waits for focus to have
                // been held and then lost.
                .onFocusChanged { state ->
                    if (state.isFocused) onFocusGained() else onFocusLost()
                }
                // One tag per node. A second `testTag` on the same chain is applied and
                // then dropped by the semantics merge, so the flow that asks for it can
                // never pass — the tag looks right in `TestTags.kt` and exists nowhere
                // in the tree. The title's *value* needs no second name: the field
                // publishes it as the node's text (measured on the device, 2026-10-28),
                // so a flow asserts it with `text:`.
                .testTag(TestTags.TASK_EDITOR_TITLE_INPUT)
                .semantics { contentDescription = "Task title" },
        decorator = { innerTextField ->
            if (titleState.text.isEmpty()) {
                Text(
                    text = stringResource(Res.string.task_title_placeholder),
                    color = placeholderTextColor(),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            innerTextField()
        },
    )
}

/**
 * The title as text, capped at three lines and ellipsised past them (UXF-06).
 *
 * A `Text` draws an ellipsis; an editable field has no such thing and would only
 * scroll, which is not the same reading. The ellipsis is painted, not stored: the
 * node's semantics still carry the whole title, so a screen reader reads all of it
 * however the hero draws it.
 */
@Composable
private fun RowScope.TitleText(
    titleState: TextFieldState,
    titleStyle: TextStyle,
    onClick: () -> Unit,
) {
    Text(
        text =
            titleState.text.toString().ifEmpty {
                stringResource(Res.string.task_title_placeholder)
            },
        style = titleStyle,
        // The placeholder is a hint, not the value: dimming it says so, and a real
        // title never draws in that colour.
        color =
            if (titleState.text.isEmpty()) {
                placeholderTextColor()
            } else {
                titleStyle.color
            },
        maxLines = TITLE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .testTag(TestTags.TASK_EDITOR_TITLE_INPUT)
                .semantics { contentDescription = "Task title" },
    )
}

/**
 * The title is one logical line: a newline pasted into it would break the single-row
 * shape the checkbox aligns against, and the hero's three lines come from wrapping,
 * not from line breaks the user typed.
 *
 * A break is one space, whatever the platform wrote it as: a Windows paste arrives as
 * CRLF, and replacing each of its two characters would leave two spaces where the line
 * break was.
 */
private val NoNewlines =
    InputTransformation {
        val text = asCharSequence()
        // Back to front, so each replacement leaves the offsets before it valid.
        Regex("\r\n|\r|\n").findAll(text).toList().asReversed().forEach {
            replace(it.range.first, it.range.last + 1, " ")
        }
    }

/** The number of lines the hero shows before it ellipsises. */
private const val TITLE_MAX_LINES = 3

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskTitleRowPreview() =
    PreviewThemed(darkTheme = false) {
        TaskTitleRow(
            taskId = "t1",
            titleState = TextFieldState("Buy groceries"),
            isCompleted = false,
            onCheckToggle = noopClick,
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskTitleRowCompletedPreview() =
    PreviewThemed(darkTheme = false) {
        TaskTitleRow(
            taskId = "t1",
            titleState = TextFieldState("Buy groceries"),
            isCompleted = true,
            onCheckToggle = noopClick,
        )
    }
