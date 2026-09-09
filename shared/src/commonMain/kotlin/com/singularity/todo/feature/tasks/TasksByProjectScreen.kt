package com.singularity.todo.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.projects.ProjectIconRegistry
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.components.TaskCard
import com.singularity.todo.feature.tasks.components.TaskCardActions
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProfileRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksByProjectScreen(
    projectId: ProjectId,
    onBack: () -> Unit,
    onNavigateToTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TasksByProjectViewModel = koinViewModel { parametersOf(projectId) }
    TasksByProjectContent(viewModel = viewModel, projectId = projectId, onBack = onBack, onNavigateToTask = onNavigateToTask, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksByProjectContent(
    viewModel: TasksByProjectViewModel,
    projectId: ProjectId,
    onBack: () -> Unit,
    onNavigateToTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hideCompleted by viewModel.hideCompleted.collectAsStateWithLifecycle()

    when (val s = state) {
        TasksByProjectUiState.Loading -> {
            Scaffold(modifier = modifier, topBar = {
                TopAppBar(
                    title = { Text("Project") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                )
            }) { pad -> LoadingIndicator(Modifier.padding(pad)) }
        }
        TasksByProjectUiState.ProjectNotFound -> {
            Scaffold(modifier = modifier, topBar = {
                TopAppBar(
                    title = { Text("Project not found") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                )
            }) { padding ->
                EmptyState(title = "Project not found", modifier = Modifier.padding(padding))
            }
        }
        is TasksByProjectUiState.Content -> {
            Scaffold(
                modifier = modifier,
                topBar = {
                    TopAppBar(
                        title = { Text(s.project.name) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding(),
                ) {
                    // Project header with color/icon and progress
                    ProjectHeader(
                        project = s.project,
                        totalCount = s.totalCount,
                        completedCount = s.completedCount,
                    )

                    // Filter row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = hideCompleted,
                            onClick = { viewModel.toggleHideCompleted() },
                            label = { Text(if (hideCompleted) "Show completed" else "Hide completed") },
                            leadingIcon = if (hideCompleted) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                        )
                    }

                    // Task list
                    if (s.tasks.isEmpty()) {
                        EmptyState(
                            title = "No tasks",
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            items(s.tasks, key = { it.id.value }) { task ->
                                TaskCard(
                                    task = task,
                                    onClick = { onNavigateToTask(task.id) },
                                    actions = TaskCardActions.Empty,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    // Quick-add input
                    QuickAddRow(
                        onAdd = { viewModel.addTask(it) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectHeader(
    project: com.singularity.todo.feature.projects.Project,
    totalCount: Int,
    completedCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Color circle with icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(project.color)),
            contentAlignment = Alignment.Center,
        ) {
            val icon = ProjectIconRegistry.iconByKey(project.icon) ?: Icons.Filled.Folder
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            if (totalCount > 0) {
                LinearProgressIndicator(
                    progress = { if (totalCount > 0) completedCount.toFloat() / totalCount else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = Color(project.color),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$completedCount / $totalCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = project.description ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuickAddRow(
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add a task...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (text.isNotBlank()) {
            IconButton(onClick = {
                onAdd(text)
                text = ""
            }) {
                Icon(Icons.Filled.Check, "Add task", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TasksByProjectContentPreview() {
    val fakeTaskRepo = FakeTaskRepository()
    val fakeProjectsRepo = FakeProjectsRepository()
    val fakeAuthRepo = FakeAuthRepository()
    val fakeProfileRepo = FakeProfileRepository()
    val fakeCurrentUser = FakeProfileAwareCurrentUser(fakeAuthRepo, fakeProfileRepo)

    val sample = PreviewSamples.project()
    fakeProjectsRepo.seed(sample)
    fakeTaskRepo.seed(
        PreviewSamples.task(id = "t1", title = "Fix the bug", projectId = sample.id),
        PreviewSamples.task(id = "t2", title = "Write tests", projectId = sample.id),
    )

    val vm = TasksByProjectViewModel(
        projectId = sample.id,
        taskRepo = fakeTaskRepo,
        projectRepo = fakeProjectsRepo,
        createTask = CreateTaskUseCase(fakeTaskRepo, Clock),
        updateTask = UpdateTaskUseCase(fakeTaskRepo, Clock),
        currentUser = fakeCurrentUser,
    )

    PreviewThemed(darkTheme = false, useSurface = false) {
        TasksByProjectContent(
            viewModel = vm,
            projectId = sample.id,
            onBack = {},
            onNavigateToTask = {},
        )
    }
}
