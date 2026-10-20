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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.components.StaticTextSource
import com.singularity.todo.core.ui.components.rememberBridgedTextFieldState
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.components.EditorParentPickerSheet
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.feature.projects.presentation.nav.LocalProjectsNavigator
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
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    var showIconPicker by remember { mutableStateOf(false) }
    var showParentPicker by remember { mutableStateOf(false) }

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        onNavigateBack = onBack,
        modifier = Modifier.testTag(TestTags.PROJECT_EDITOR_NOTIFICATION_HOST),
    )

    if (state.loading) {
        LoadingIndicator()
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            EditorTopBar(
                isEditMode = state.isEditMode,
                canSave = state.name.isNotBlank() && !state.saving,
                onBack = onBack,
                onSave = { viewModel.onIntent(ProjectEditorIntent.Save) },
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
            IdentitySection(
                nameSource = state.nameSource,
                descriptionSource = state.descriptionSource,
                errorMessage = state.errorMessage,
                onNameChanged = { viewModel.onIntent(ProjectEditorIntent.NameChanged(it)) },
                onDescriptionChanged = { viewModel.onIntent(ProjectEditorIntent.DescriptionChanged(it)) },
            )

            // ── Appearance: icon + color ─────────────────────────────────
            AppearanceSection(
                selectedColor = state.color,
                selectedIcon = state.icon,
                onColorSelected = { viewModel.onIntent(ProjectEditorIntent.ColorChanged(it)) },
                onIconPickerRequested = { showIconPicker = true },
            )

            // ── Organization: parent project ────────────────────────────
            OrganizationSection(
                hasParent = state.parentId != null,
                onParentPickerRequested = { showParentPicker = true },
            )
        }
    }

    if (state.errorMessage != null && !state.saving) {
        EditorErrorDialog(
            message = state.errorMessage ?: "",
            onDismiss = { viewModel.onIntent(ProjectEditorIntent.ErrorShown) },
        )
    }

    IconPickerSheetHost(
        visible = showIconPicker,
        currentIcon = state.icon,
        onPick = { icon ->
            icon?.let {
                viewModel.onIntent(
                    ProjectEditorIntent.IconChanged(it),
                )
            }
            showIconPicker = false
        },
        onDismiss = { showIconPicker = false },
    )

    ParentPickerSheetHost(
        visible = showParentPicker,
        onPick = {
            viewModel.onIntent(ProjectEditorIntent.ParentChanged(it))
            showParentPicker = false
        },
        onDismiss = { showParentPicker = false },
    )
}

// ─── Sheet hosts ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconPickerSheetHost(
    visible: Boolean,
    currentIcon: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        BottomSheetHost(onDismiss = onDismiss) {
            IconPickerSheet(
                currentIcon = currentIcon,
                onPick = onPick,
                onDismiss = onDismiss,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentPickerSheetHost(
    visible: Boolean,
    onPick: (ProjectId?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        BottomSheetHost(onDismiss = onDismiss) {
            EditorParentPickerSheet(
                onPick = onPick,
                onDismiss = onDismiss,
            )
        }
    }
}

// ─── Content sections ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    isEditMode: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    TopAppBar(
        title = { Text(if (isEditMode) "Edit Project" else "New Project") },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag(TestTags.PROJECT_EDITOR_BACK),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.testTag(TestTags.PROJECT_EDITOR_SAVE),
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Save")
            }
        },
    )
}

@Composable
private fun EditorErrorDialog(message: String, onDismiss: () -> Unit) {
    ResultDialog(
        title = "Error",
        text = message,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentitySection(
    nameSource: StaticTextSource,
    descriptionSource: StaticTextSource,
    errorMessage: String?,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
) {
    val nameState = rememberBridgedTextFieldState(
        source = nameSource,
        onTextChanged = onNameChanged,
    )
    val descriptionState = rememberBridgedTextFieldState(
        source = descriptionSource,
        onTextChanged = onDescriptionChanged,
    )

    OutlinedTextField(
        state = nameState,
        label = { Text("Project name") },
        isError = errorMessage != null,
        supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        lineLimits = TextFieldLineLimits.SingleLine,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.PROJECT_EDITOR_NAME_INPUT),
    )

    OutlinedTextField(
        state = descriptionState,
        label = { Text("Description (optional)") },
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.PROJECT_EDITOR_DESCRIPTION_INPUT),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(
    selectedColor: Int,
    selectedIcon: String?,
    onColorSelected: (Int) -> Unit,
    onIconPickerRequested: () -> Unit,
) {
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
                        // The swatch is a button that a screen reader has to be able to
                        // name: it carries no text, and its only visual is a circle of
                        // colour. "Blue" is the name of the thing, and "selected" says
                        // which one is in use.
                        .semantics {
                            contentDescription = "Colour ${ProjectColorPalette.nameOf(color)}" +
                                if (selectedColor == color) ", selected" else ""
                        }
                        .then(
                            if (selectedColor == color) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onColorSelected(color) },
                ) {
                    if (selectedColor == color) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp).padding(6.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        FilterChip(
            selected = selectedIcon != null,
            onClick = onIconPickerRequested,
            label = {
                Text(selectedIcon?.let { ProjectIconRegistry.iconByKey(it)?.name } ?: "Icon")
            },
            leadingIcon = {
                selectedIcon?.let { key ->
                    val icon = ProjectIconRegistry.iconByKey(key)
                    icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } ?: Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganizationSection(
    hasParent: Boolean,
    onParentPickerRequested: () -> Unit,
) {
    Text("Organization", style = MaterialTheme.typography.titleSmall)

    FilterChip(
        selected = hasParent,
        onClick = onParentPickerRequested,
        label = { Text("Parent project") },
        leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
    )
}

// ─── Notification mapper ────────────────────────────────────────────────────────

private fun ProjectEditorUiEvent.toNotification(): Notification = when (this) {
    ProjectEditorUiEvent.NavigateBack -> Notification.NavigateBack
}
