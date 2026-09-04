package com.singularity.todo.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

@Composable
fun ProjectPickerSheet(
    onProjectSelected: (Project?) -> Unit,
    onDismiss: () -> Unit,
) {
    val projectsRepo: ProjectsRepository = koinInject()
    val settingsRepo: SettingsRepository = koinInject()

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }

    LaunchedEffect(Unit) {
        val userId = settingsRepo.userId.first()
        projects = projectsRepo.watchProjects(userId).first()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Project") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    TextButton(
                        onClick = { onProjectSelected(null); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("No Project (Inbox)")
                    }
                }
                items(projects, key = { it.id.value }) { project ->
                    Text(
                        text = project.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProjectSelected(project); onDismiss() }
                            .padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
