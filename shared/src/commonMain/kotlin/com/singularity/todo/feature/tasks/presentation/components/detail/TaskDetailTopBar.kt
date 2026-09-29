package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags

/**
 * Top bar for TaskDetail screen (View mode).
 *
 * @param onBackClick called when the back arrow is tapped
 * @param onMoreClick called when the menu button is tapped. Null means the button is hidden
 *        (e.g. in Create mode where there is no overflow menu).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailTopBar(onBackClick: () -> Unit, onMoreClick: (() -> Unit)? = null) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            if (onMoreClick != null) {
                IconButton(onClick = onMoreClick, modifier = Modifier.testTag(TestTags.TASK_EDITOR_MORE_MENU)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Меню",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
