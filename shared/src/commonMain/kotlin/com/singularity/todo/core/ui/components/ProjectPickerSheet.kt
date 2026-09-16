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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    onProjectSelected: (Project?) -> Unit,
    onDismiss: () -> Unit,
    vm: ProjectPickerViewModel = koinViewModel(),
) {
    ProjectPickerSheetContent(
        vm = vm,
        onProjectSelected = onProjectSelected,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPickerSheetContent(
    vm: ProjectPickerViewModel,
    onProjectSelected: (Project?) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val projects by vm.projects.collectAsStateWithLifecycle()
    val draftName by vm.draftName.collectAsStateWithLifecycle()
    val isCreating by vm.isCreating.collectAsStateWithLifecycle()

    TaskEditorSheetHost(
        title = "Select Project",
        onClose = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            // No project (Inbox)
            item {
                TextButton(
                    onClick = {
                        onProjectSelected(null);
                        onDismiss()
                    },
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
                            value = draftName,
                            onValueChange = vm::setDraftName,
                            placeholder = { Text("New project name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (draftName.isNotBlank()) {
                                        vm.confirmCreate()
                                        focusManager.clearFocus()
                                    }
                                },
                            ),
                        )
                        TextButton(
                            onClick = {
                                if (draftName.isNotBlank()) {
                                    vm.confirmCreate()
                                    focusManager.clearFocus()
                                }
                            },
                        ) {
                            Text("Create")
                        }
                        TextButton(onClick = { vm.setCreating(false) }) {
                            Text("Cancel")
                        }
                    }
                } else {
                    TextButton(
                        onClick = { vm.setCreating(true) },
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
                        .clickable {
                            onProjectSelected(project);
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Preview
@Composable
private fun ProjectPickerSheetLightPreview() = PreviewThemed(darkTheme = false) {
    val fakeProjectsRepo = FakeProjectsRepository()
    val fakeAuthRepo = FakeAuthRepository()
    val fakeCurrentUser = FakeProfileAwareCurrentUser(authRepository = fakeAuthRepo)
    // FakeProjectsRepository.create() is already a no-op success.
    val vm = ProjectPickerViewModel(
        projectRepo = fakeProjectsRepo,
        createProject = CreateProjectUseCase(fakeProjectsRepo, Clock),
        currentUser = fakeCurrentUser,
    )
    ProjectPickerSheetContent(
        vm = vm,
        onProjectSelected = { _ -> },
        onDismiss = {},
    )
}
