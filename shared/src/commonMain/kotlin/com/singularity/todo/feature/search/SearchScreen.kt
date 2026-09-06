package com.singularity.todo.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.feature.tasks.components.TaskCard
import com.singularity.todo.feature.tasks.components.TaskCardActions
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel = koinInject()) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Search") }) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search tasks, notes, projects...") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            if (query.isBlank()) {
                EmptyState(title = "Enter a search query")
            } else {
                SearchResultsList(results = state.results)
            }
        }
    }
}

@Composable
private fun SearchResultsList(results: SearchResults) {
    val hasAny = results.tasks.isNotEmpty() || results.notes.isNotEmpty() ||
        results.projects.isNotEmpty() || results.tags.isNotEmpty()
    if (!hasAny) {
        EmptyState(title = "No results found")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (results.tasks.isNotEmpty()) {
            item { SectionHeader("Tasks") }
            items(results.tasks.take(5)) { task ->
                TaskCard(task = task, onClick = { /* TODO */ }, actions = TaskCardActions.Empty)
            }
        }
        if (results.notes.isNotEmpty()) {
            item { SectionHeader("Notes") }
            items(results.notes.take(5)) { note ->
                SimpleResultCard(title = note.title.ifBlank { "Untitled" }, onClick = { /* TODO */ })
            }
        }
        if (results.projects.isNotEmpty()) {
            item { SectionHeader("Projects") }
            items(results.projects.take(5)) { project ->
                SimpleResultCard(title = project.name, onClick = { /* TODO */ })
            }
        }
        if (results.tags.isNotEmpty()) {
            item { SectionHeader("Tags") }
            items(results.tags.take(5)) { tag ->
                SimpleResultCard(title = tag.name, onClick = { /* TODO */ })
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
}

@Composable
private fun SimpleResultCard(title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) { Text(text = title, modifier = Modifier.padding(12.dp)) }
}
