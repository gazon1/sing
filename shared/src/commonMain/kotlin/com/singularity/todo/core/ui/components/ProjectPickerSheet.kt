package com.singularity.todo.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.tasks.components.TaskEditorSheetHost
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    onProjectSelected: (Project?) -> Unit,
    onDismiss: () -> Unit,
) {
    val projectsRepo: ProjectsRepository = koinInject()
    val settingsRepo: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var isCreating by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        val userId = settingsRepo.userId.first()
        projects = projectsRepo.watchProjects(userId).first()
    }

    TaskEditorSheetHost(
        title = "Select Project",
        onClose = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            // No project (Inbox)
            item {
                TextButton(
                    onClick = { onProjectSelected(null); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "No Project (Inbox)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Inline create row
            item {
                if (isCreating) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newProjectName,
                            onValueChange = { newProjectName = it },
                            placeholder = { Text("New project name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (newProjectName.isNotBlank()) {
                                        scope.launch {
                                            val userId = settingsRepo.userId.first()
                                            val newProject = Project(
                                                id = ProjectId.generate(),
                                                name = newProjectName.trim(),
                                                color = 0xFF4CAF50.toInt(),
                                                createdAt = Clock.now(),
                                                updatedAt = Clock.now(),
                                                userId = userId,
                                            )
                                            projectsRepo.create(newProject)
                                            newProjectName = ""
                                            isCreating = false
                                            projects = projectsRepo.watchProjects(userId).first()
                                        }
                                    }
                                    focusManager.clearFocus()
                                },
                            ),
                        )
                        TextButton(
                            onClick = {
                                if (newProjectName.isNotBlank()) {
                                    scope.launch {
                                        val userId = settingsRepo.userId.first()
                                        val newProject = Project(
                                            id = ProjectId.generate(),
                                            name = newProjectName.trim(),
                                            color = 0xFF4CAF50.toInt(),
                                            createdAt = Clock.now(),
                                            updatedAt = Clock.now(),
                                            userId = userId,
                                        )
                                        projectsRepo.create(newProject)
                                        newProjectName = ""
                                        isCreating = false
                                        projects = projectsRepo.watchProjects(userId).first()
                                    }
                                }
                                focusManager.clearFocus()
                            },
                        ) {
                            Text("Create")
                        }
                    }
                } else {
                    TextButton(
                        onClick = { isCreating = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            "Create new project",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            // Project list
            items(projects, key = { it.id.value }) { project ->
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onProjectSelected(project); onDismiss() }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}
