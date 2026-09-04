package com.singularity.todo.feature.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.DeleteActionButton
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(onNavigateToCreateTag: () -> Unit) {
    val viewModel: TagsViewModel = koinInject()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tags") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateTag) {
                Icon(Icons.Filled.Create, contentDescription = "Add Tag")
            }
        },
    ) { padding ->
        TagsContent(
            state = state,
            modifier = Modifier.padding(padding),
            onDelete = viewModel::delete,
        )
    }
}

@Composable
private fun TagsContent(
    state: TagsUiState,
    modifier: Modifier = Modifier,
    onDelete: (TagId) -> Unit,
) {
    when (state) {
        is TagsUiState.Loading -> LoadingIndicator(modifier = modifier)
        is TagsUiState.Empty -> EmptyState(title = "No tags yet", modifier = modifier)
        is TagsUiState.Error -> EmptyState(title = "Error: ${state.message}", modifier = modifier)
        is TagsUiState.Content -> TagList(
            tags = state.tags,
            modifier = modifier,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun TagList(tags: List<Tag>, modifier: Modifier = Modifier, onDelete: (TagId) -> Unit) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tags, key = { it.id.value }) { tag ->
            TagCard(tag = tag, onDelete = { onDelete(tag.id) })
        }
    }
}

@Composable
fun TagCard(tag: Tag, onDelete: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(tag.color)),
            )
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            DeleteActionButton(onClick = onDelete)
        }
    }
}
