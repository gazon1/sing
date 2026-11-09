package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.TopAppBar
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.ai_actions
import singularity_todo.shared.generated.resources.back
import singularity_todo.shared.generated.resources.menu

/**
 * Top bar for TaskDetail screen (View mode).
 *
 * Owns the overflow menu itself so that, on Android, the whole bar — back, AI,
 * more and the archive/delete items — is published to the shell as one actions
 * block. Keeping the menu out here would leave it anchored to a composable the
 * shell never renders, and the menu would never open.
 *
 * @param onBackClick called when the back arrow is tapped
 * @param onAiClick called when the AI button is tapped. Null means the button is hidden
 *        (AI features not available on this platform).
 * @param menuItems Overflow menu items. Empty means the more button is hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionSignature")
@Composable
fun TaskDetailTopBar(
    onBackClick: () -> Unit,
    onAiClick: (() -> Unit)? = null,
    menuItems: ImmutableList<TaskEditorMenuItem> = persistentListOf(),
) {
    var showMenu by remember { mutableStateOf(false) }

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
            if (menuItems.isNotEmpty()) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.testTag(TestTags.TASK_EDITOR_MORE_MENU),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(Res.string.menu),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.mapTestTagsAsResourceIds(),
                    ) {
                        menuItems.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label) },
                                onClick = {
                                    showMenu = false
                                    item.onClick()
                                },
                                modifier =
                                    if (item.testTag != null) {
                                        Modifier.testTag(item.testTag)
                                    } else {
                                        Modifier
                                    },
                            )
                        }
                    }
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    )
}
