package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.TopAppBar
import com.singularity.todo.core.ui.detail.SavedIndicator
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.ai_actions
import singularity_todo.shared.generated.resources.back
import singularity_todo.shared.generated.resources.menu

/**
 * Top bar for TaskDetail screen (View mode).
 *
 * @param onBackClick called when the back arrow is tapped
 * @param onMoreClick called when the menu button is tapped. Null means the button is hidden
 *        (e.g. in Create mode where there is no overflow menu).
 * @param onAiClick called when the AI button is tapped. Null means the button is hidden
 *        (AI features not available on this platform).
 * @param savedVisible whether the "Saved" indicator is shown. The task detail edits in
 *        place and stores the edit silently, so the indicator is the only thing that
 *        tells the user the change was kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionSignature")
@Composable
fun TaskDetailTopBar(
    onBackClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
    onAiClick: (() -> Unit)? = null,
    savedVisible: Boolean = false,
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            SavedIndicator(
                visible = savedVisible,
                modifier = Modifier.testTag(TestTags.TASK_DETAIL_SAVED_INDICATOR),
            )
            if (onAiClick != null) {
                IconButton(
                    onClick = onAiClick,
                    modifier = Modifier.testTag(TestTags.TASK_EDITOR_AI_BUTTON),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = stringResource(Res.string.ai_actions),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (onMoreClick != null) {
                IconButton(onClick = onMoreClick, modifier = Modifier.testTag(TestTags.TASK_EDITOR_MORE_MENU)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(Res.string.menu),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    )
}
