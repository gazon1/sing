package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.BottomSheetHost
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.nav.LocalProjectsNavigator
import com.singularity.todo.feature.projects.presentation.nav.ProjectsPreviewWrapper
import com.singularity.todo.feature.projects.presentation.state.ProjectEditorIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectEditorUiEvent
import com.singularity.todo.feature.projects.presentation.theme.ProjectColorPalette
import com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectEditorViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * Shell that creates [ProjectEditorViewModel] via Koin and delegates to
 * [ProjectEditorContent]. This is the navigation-entry composable.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectEditorScreen(projectId: ProjectId?, modifier: Modifier = Modifier) {
    val nav = LocalProjectsNavigator.current
    val viewModel: ProjectEditorViewModel = koinViewModel { parametersOf(projectId) }
    ProjectEditorContent(
        viewModel = viewModel,
        modifier = modifier,
        onBack = { nav.back() },
    )
}

// ─── Content — accepts VM as parameter (usable without Koin) ──────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectEditorContent(viewModel: ProjectEditorViewModel, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showIconPicker by remember { mutableStateOf(false) }
    var showParentPicker by remember { mutableStateOf(false) }

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        onNavigateBack = onBack,
        modifier = Modifier.testTag("project_editor_notification_host"),
    )

    if (state.loading) {
        LoadingIndicator()
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditMode) "Edit Project" else "New Project") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.processIntent(ProjectEditorIntent.Save) },
                        enabled = state.name.isNotBlank() && !state.saving,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ── Identity: name + description ──────────────────────────────
            OutlinedTextField(
                value = state.name,
                onValueChange = { viewModel.processIntent(ProjectEditorIntent.NameChanged(it)) },
                label = { Text("Project name") },
                isError = state.errorMessage != null,
                supportingText = state.errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = { viewModel.processIntent(ProjectEditorIntent.DescriptionChanged(it)) },
                label = { Text("Description (optional)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Appearance: icon + color (consolidated) ──────────────────
            Text("Appearance", style = MaterialTheme.typography.titleSmall)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Color selector
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProjectColorPalette.all.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .then(
                                    if (state.color == color) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { viewModel.processIntent(ProjectEditorIntent.ColorChanged(color)) },
                        ) {
                            if (state.color == color) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .padding(6.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Icon selector
                FilterChip(
                    selected = state.icon != null,
                    onClick = { showIconPicker = true },
                    label = {
                        Text(state.icon?.let { ProjectIconRegistry.iconByKey(it)?.name } ?: "Icon")
                    },
                    leadingIcon = {
                        state.icon?.let { key ->
                            val icon = ProjectIconRegistry.iconByKey(key)
                            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } ?: Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
            }

            // ── Organization: parent project ─────────────────────────────
            Text("Organization", style = MaterialTheme.typography.titleSmall)

            FilterChip(
                selected = state.parentId != null,
                onClick = { showParentPicker = true },
                label = { Text("Parent project") },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }

    if (state.errorMessage != null && !state.saving) {
        ResultDialog(
            title = "Error",
            text = state.errorMessage ?: "",
            onDismiss = { viewModel.processIntent(ProjectEditorIntent.ErrorShown) },
        )
    }

    // ── Icon Picker Sheet ─────────────────────────────────────────────────
    if (showIconPicker) {
        BottomSheetHost(onDismiss = { showIconPicker = false }) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Choose icon", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProjectIconRegistry.all.forEach { (key, icon) ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable {
                                    viewModel.processIntent(ProjectEditorIntent.IconChanged(key))
                                    showIconPicker = false
                                }
                                .background(
                                    if (key == state.icon) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = key, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── Parent Picker Sheet ───────────────────────────────────────────────
    if (showParentPicker) {
        BottomSheetHost(onDismiss = { showParentPicker = false }) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Parent project", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                // "None" option
                FilterChip(
                    selected = state.parentId == null,
                    onClick = {
                        viewModel.processIntent(ProjectEditorIntent.ParentChanged(null))
                        showParentPicker = false
                    },
                    label = { Text("None (root project)") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ─── Notification mapper ────────────────────────────────────────────────────────

private fun ProjectEditorUiEvent.toNotification(): Notification = when (this) {
    ProjectEditorUiEvent.NavigateBack -> Notification.NavigateBack
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Suppress("ViewModelConstructorInComposable")
@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun ProjectEditorCreatePreview() = ProjectsPreviewWrapper {
    // Build fake dependencies manually — no Koin needed in previews.
    val fakeProjectsRepo = com.singularity.todo.test.fakes.FakeProjectsRepository()
    val fakeAuthRepo = com.singularity.todo.test.fakes.FakeAuthRepository()
    val fakeProfileRepo = com.singularity.todo.test.fakes.FakeProfileRepository()
    val fakeCurrentUser = com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser(fakeAuthRepo, fakeProfileRepo)

    val vm = ProjectEditorViewModel(
        projectId = null,
        createProject = CreateProjectUseCase(fakeProjectsRepo, com.singularity.todo.core.platform.Clock, fakeCurrentUser),
        updateProject = UpdateProjectUseCase(fakeProjectsRepo, com.singularity.todo.core.platform.Clock),
        projectsRepo = fakeProjectsRepo,
    )

    PreviewThemed {
        ProjectEditorContent(viewModel = vm, onBack = {})
    }
}

@Suppress("ViewModelConstructorInComposable")
@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun ProjectEditorEditPreview() = ProjectsPreviewWrapper {
    val sample = com.singularity.todo.core.ui.preview.PreviewSamples.project("p1", "Work")
    val fakeProjectsRepo = com.singularity.todo.test.fakes.FakeProjectsRepository().apply { seed(sample) }
    val fakeAuthRepo = com.singularity.todo.test.fakes.FakeAuthRepository()
    val fakeProfileRepo = com.singularity.todo.test.fakes.FakeProfileRepository()
    val fakeCurrentUser = com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser(fakeAuthRepo, fakeProfileRepo)

    val vm = ProjectEditorViewModel(
        projectId = ProjectId.fromString("p1"),
        createProject = CreateProjectUseCase(fakeProjectsRepo, com.singularity.todo.core.platform.Clock, fakeCurrentUser),
        updateProject = UpdateProjectUseCase(fakeProjectsRepo, com.singularity.todo.core.platform.Clock),
        projectsRepo = fakeProjectsRepo,
    )

    PreviewThemed {
        ProjectEditorContent(viewModel = vm, onBack = {})
    }
}
