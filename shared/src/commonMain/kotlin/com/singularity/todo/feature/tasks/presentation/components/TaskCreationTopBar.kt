package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCreationTopBar(
    onBackClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = TaskColors.TextPrimary
                )
            }
        },
        actions = {
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Меню",
                    tint = TaskColors.TextPrimary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = TaskColors.Background)
    )
}
