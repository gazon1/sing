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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ButtonSpinner
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Bottom bar with primary save action.
 * Anchored at bottom for thumb accessibility.
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
                    .testTag(TestTags.TASK_EDITOR_SAVE),
        ) {
            if (isLoading) {
                // `isLoading` is the caller's `isSaving`: TaskCreateScreen passes
                // `isLoading = state.isSaving` here, so this branch is what a save
                // in flight looks like on screen. The parameter was declared and
                // ignored before — the caller handed over the flag and the button
                // said "Сохранить" for the whole save, which reads as "nothing
                // happened" on a save that is merely slow. TASK_EDITOR_SAVING is
                // therefore in the tree exactly while a save is running, which is
                // what a test can wait on.
                //
                // The label gives way to the spinner: a button that says
                // "Сохранить" while it is busy says the save has not started.
                // The caller keeps `enabled` false while saving (`isSaveEnabled`
                // is `validationError == null && !isSaving`), so the spinner
                // cannot be tapped into a second save.
                ButtonSpinner(
                    modifier = Modifier.testTag(TestTags.TASK_EDITOR_SAVING),
                )
            } else {
                Text(
                    text = "Сохранить",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
