package com.singularity.todo.feature.search

import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
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
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel = koinViewModel()) {
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
                TaskCard(task = task, onClick = {}, actions = TaskCardActions.Empty)
            }
        }
        if (results.notes.isNotEmpty()) {
            item { SectionHeader("Notes") }
            items(results.notes.take(5)) { note ->
                SimpleResultCard(title = note.title.ifBlank { "Untitled" }, onClick = {})
            }
        }
        if (results.projects.isNotEmpty()) {
            item { SectionHeader("Projects") }
            items(results.projects.take(5)) { project ->
                SimpleResultCard(title = project.name, onClick = {})
            }
        }
        if (results.tags.isNotEmpty()) {
            item { SectionHeader("Tags") }
            items(results.tags.take(5)) { tag ->
                SimpleResultCard(title = tag.name, onClick = {})
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

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SearchResultsListWithResultsPreview() = PreviewThemed(darkTheme = false) {
    SearchResultsList(
        results = SearchResults(
            tasks = listOf(
                PreviewSamples.task("t1", "Buy groceries", TaskPriority.High),
                PreviewSamples.task("t2", "Read book"),
            ),
            notes = listOf(
                PreviewSamples.note("n1", "Meeting notes"),
            ),
            projects = listOf(
                PreviewSamples.project("p1", "Work"),
            ),
            tags = listOf(
                PreviewSamples.tag("tg1", "urgent", 0xFFF44336.toInt()),
            ),
        ),
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SearchResultsListEmptyDarkPreview() = PreviewThemed(darkTheme = true) {
    SearchResultsList(
        results = SearchResults(emptyList(), emptyList(), emptyList(), emptyList()),
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SimpleResultCardPreview() = PreviewThemed(darkTheme = false) {
    SimpleResultCard(title = "Sample result item", onClick = {})
}
