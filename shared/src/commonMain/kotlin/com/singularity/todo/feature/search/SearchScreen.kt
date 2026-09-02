package com.singularity.todo.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.TaskCard
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    searchUseCase: SearchUseCase = koinInject()
) {
    val settingsRepo: com.singularity.todo.core.settings.SettingsRepository = koinInject()
    var query by remember { mutableStateOf("") }
    val results by searchUseCase(query, settingsRepo.userIdBlocking())
        .collectAsState(initial = SearchResults(emptyList(), emptyList(), emptyList(), emptyList()))

    Scaffold(
        topBar = { TopAppBar(title = { Text("Search") }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search tasks, notes, projects...") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true
            )

            if (query.isBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Enter a search query")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (results.tasks.isNotEmpty()) {
                        item { Text("Tasks", style = androidx.compose.material3.MaterialTheme.typography.titleSmall) }
                        items(results.tasks.take(5)) { task ->
                            TaskCard(
                                task = task,
                                onClick = { /* TODO */ },
                                onToggle = { /* TODO */ },
                                onDelete = { /* TODO */ }
                            )
                        }
                    }
                    if (results.notes.isNotEmpty()) {
                        item { Text("Notes", style = androidx.compose.material3.MaterialTheme.typography.titleSmall) }
                        items(results.notes.take(5)) { note ->
                            Card(modifier = Modifier.fillMaxWidth().clickable { /* TODO */ }) {
                                Text(note.title.ifBlank { "Untitled" }, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                    if (results.projects.isNotEmpty()) {
                        item { Text("Projects", style = androidx.compose.material3.MaterialTheme.typography.titleSmall) }
                        items(results.projects.take(5)) { project ->
                            Card(modifier = Modifier.fillMaxWidth().clickable { /* TODO */ }) {
                                Text(project.name, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                    if (results.tags.isNotEmpty()) {
                        item { Text("Tags", style = androidx.compose.material3.MaterialTheme.typography.titleSmall) }
                        items(results.tags.take(5)) { tag ->
                            Card(modifier = Modifier.fillMaxWidth().clickable { /* TODO */ }) {
                                Text(tag.name, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                    if (results.tasks.isEmpty() && results.notes.isEmpty() && results.projects.isEmpty() && results.tags.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No results found")
                            }
                        }
                    }
                }
            }
        }
    }
}
