package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun TaskChecklistCard(
    itemCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TaskCounterCard(
        icon = Icons.AutoMirrored.Filled.List,
        label = "Чек-лист",
        count = itemCount,
        onClick = onClick,
        modifier = modifier
    )
}
