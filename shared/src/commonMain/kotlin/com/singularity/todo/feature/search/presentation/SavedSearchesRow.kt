package com.singularity.todo.feature.search.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId

/**
 * Horizontal row of saved search chips shown above search results.
 *
 * - Active saved search (if any) shows a filled star.
 * - Other saved searches show outlined star.
 * - "+" chip opens the save dialog via [onSaveClick].
 * - Long-press any saved search chip opens a context menu with Rename / Delete.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SavedSearchesRow(
    savedSearches: List<SavedSearch>,
    activeSavedSearchId: SavedSearchId?,
    onLoadSearch: (SavedSearchId) -> Unit,
    onSaveClick: () -> Unit,
    onRename: (SavedSearchId, String) -> Unit,
    onDelete: (SavedSearchId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "+" chip to save current search
        item {
            FilterChip(
                selected = false,
                onClick = onSaveClick,
                label = { Text("Save") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }

        // Saved search chips
        items(
            items = savedSearches,
            key = { it.id.raw },
        ) { saved ->
            SavedSearchChipWithMenu(
                saved = saved,
                isActive = saved.id == activeSavedSearchId,
                onClick = { onLoadSearch(saved.id) },
                onRename = { newName -> onRename(saved.id, newName) },
                onDelete = { onDelete(saved.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SavedSearchChipWithMenu(
    saved: SavedSearch,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(saved.name) }

    Box {
        FilterChip(
            selected = isActive,
            onClick = onClick,
            label = { Text(saved.name, maxLines = 1) },
            leadingIcon = {
                Icon(
                    imageVector = if (isActive) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true },
            ),
        )

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            // Rename row with inline text field
            DropdownMenuItem(
                text = {
                    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                        Text("Rename", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            onClick = {
                                if (renameText.isNotBlank()) {
                                    onRename(renameText)
                                    renameText = saved.name
                                }
                                showMenu = false
                            },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
                        ) {
                            Text("Rename")
                        }
                    }
                },
                onClick = { /* handled inside column */ },
                leadingIcon = {},
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onDelete()
                    showMenu = false
                },
                leadingIcon = {},
            )
        }
    }
}
