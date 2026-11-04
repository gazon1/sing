package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ButtonSpinner
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * The save action's name, in one place: the visible label while idle, the
 * accessible name always.
 */
private const val SAVE_LABEL = "Сохранить"

/**
 * Bottom bar with primary save action.
 * Anchored at bottom for thumb accessibility.
 *
 * [isLoading] swaps the label for a spinner rather than sitting beside it: the
 * button says what it is doing, and only one of the two is ever on screen
 * (#2170). The name does not go with it — the Button carries a
 * contentDescription, so a screen reader still hears "Сохранить" while the
 * label is replaced.
 */
@Composable
fun TaskSaveBar(
    isEnabled: Boolean,
    isLoading: Boolean = false,
    onSaveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Button(
            onClick = onSaveClick,
            enabled = isEnabled,
            shape = RoundedCornerShape(TaskSpacing.cardCornerRadius),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TaskSpacing.screenPadding, vertical = TaskSpacing.md)
                    .height(52.dp)
                    .testTag(TestTags.TASK_EDITOR_SAVE)
                    // The accessible name, held separately from the visible label:
                    // while a save runs the label is gone and the spinner is the only
                    // child, so without this the button would announce nothing at all.
                    .semantics { contentDescription = SAVE_LABEL },
        ) {
            // A save in flight replaces the label with the spinner: the button says what
            // it is doing rather than what tapping it would do, and the two are never on
            // screen at once (#2170). `enabled` is false while saving, so the spinner
            // cannot be tapped into a second save. TASK_EDITOR_SAVING is in the tree
            // exactly while a save runs, which is what a test can wait on.
            //
            // The *name* does not leave with the label: the Button carries a
            // contentDescription, because a control whose only child is an indeterminate
            // progress indicator has nothing for a screen reader to announce. The visible
            // text is gone, the accessible name is not.
            if (isLoading) {
                ButtonSpinner(modifier = Modifier.testTag(TestTags.TASK_EDITOR_SAVING))
            } else {
            Text(
                text = SAVE_LABEL,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The save action's name, in one place: the visible label while idle, the accessible name always. */
private const val SAVE_LABEL = "Сохранить"
