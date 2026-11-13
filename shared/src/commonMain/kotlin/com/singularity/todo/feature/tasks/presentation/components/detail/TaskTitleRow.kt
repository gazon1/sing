package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
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
) {
    Row(
        // Top, not centre: the title wraps to three lines now, and a checkbox floating
        // in the middle of a three-line title is not on the line the eye starts at.
        verticalAlignment = Alignment.Top,
    ) {
        if (onCheckToggle != null) {
            Celebration(
                triggerKey = if (isCompleted) taskId else "",
            ) {
                IconButton(onClick = onCheckToggle) {
                    val doneTint =
                        if (isCompleted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    val glyph =
                        if (isCompleted) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Outlined.CheckBoxOutlineBlank
                        }
                    Icon(
                        imageVector = glyph,
                        contentDescription =
                            if (isCompleted) {
                                stringResource(Res.string.task_completed)
                            } else {
                                stringResource(Res.string.mark_task_complete)
                            },
                        tint = doneTint,
                        modifier = Modifier.size(TaskSpacing.iconSizeLarge),
                    )
                }
            }
            Spacer(modifier = Modifier.width(TaskSpacing.md))
        }
        BasicTextField(
            state = titleState,
            textStyle =
                LocalTextStyle.current.copy(
                    // A completed task reads as done without looking at the checkbox:
                    // struck through and dimmed. Colour alone would not be a fact a
                    // screen reader, or an eye that cannot see the dimming, receives.
                    color =
                        if (isCompleted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // The hero wraps to three lines instead of hard-clipping at the container
            // edge, which is the finding this replaces (UXF-06). Three is the cap.
            //
            // shortcut: an editable field has no `TextOverflow` — past three lines the
            // field scrolls internally rather than drawing an ellipsis. The whole title
            // is still its value, so accessibility and a cursor move reach all of it;
            // when a deliberate ellipsis is required, the hero has to become a Text
            // with tap-to-edit rather than this field.
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 3),
            inputTransformation = NoNewlines,
            modifier =
                Modifier
                    .weight(1f)
                    .bringIntoViewOnFocus()
                    // One tag per node. A second `testTag` on the same chain is
                    // applied and then dropped by the semantics merge, so the flow
                    // that asks for it can never pass — the tag looks right in
                    // `TestTags.kt` and exists nowhere in the tree. The title's
                    // *value* needs no second name: the field publishes it as the
                    // node's text (measured on the device, 2026-10-28), so a flow
                    // asserts it with `text:`.
                    .testTag(TestTags.TASK_EDITOR_TITLE_INPUT)
                    // The visual cap is three lines, but the field's own value is the
                    // whole title — an editable node publishes all of it, so a screen
                    // reader reads the full title however it is drawn.
                    .semantics { contentDescription = "Task title" },
            decorator = { innerTextField ->
                if (titleState.text.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.task_title_placeholder),
                        color = placeholderTextColor(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                innerTextField()
            },
        )
    }
}

/**
 * The title is one logical line: a newline pasted into it would break the single-row
 * shape the checkbox aligns against, and the hero's three lines come from wrapping,
 * not from line breaks the user typed.
 */
private val NoNewlines =
    InputTransformation {
        val breaks = asCharSequence().mapIndexedNotNull { i, c -> i.takeIf { c == '\n' || c == '\r' } }
        // Back to front, so each replacement leaves the earlier offsets valid.
        breaks.asReversed().forEach { replace(it, it + 1, " ") }
    }

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
