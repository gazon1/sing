package com.singularity.todo.feature.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.preview.PreviewThemed
import org.koin.compose.viewmodel.koinViewModel

private val PRESET_COLORS = listOf(
    0xFF1976D2.toInt(), // blue
    0xFF388E3C.toInt(), // green
    0xFFF57C00.toInt(), // orange
    0xFFD32F2F.toInt(), // red
    0xFF7B1FA2.toInt(), // purple
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectEditorScreen(
    onBack: () -> Unit,
    viewModel: ProjectEditorViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        onNavigateBack = onBack,
        modifier = Modifier.testTag("project_editor_notification_host"),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Project") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = { viewModel.processIntent(ProjectEditorIntent.NameChanged(it)) },
                label = { Text("Project name") },
                isError = state.errorMessage != null,
                supportingText = state.errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Color", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PRESET_COLORS.forEach { color ->
                    ColorChip(
                        color = Color(color),
                        selected = state.color == color,
                        onClick = { viewModel.processIntent(ProjectEditorIntent.ColorChanged(color)) }
                    )
                }
            }

            OutlinedTextField(
                value = state.description,
                onValueChange = { viewModel.processIntent(ProjectEditorIntent.DescriptionChanged(it)) },
                label = { Text("Description (optional)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.errorMessage != null && !state.saving) {
        ResultDialog(
            title = "Error",
            text = state.errorMessage ?: "",
            onDismiss = { viewModel.processIntent(ProjectEditorIntent.ErrorShown) }
        )
    }
}

private fun ProjectEditorUiEvent.toNotification(): Notification = when (this) {
    ProjectEditorUiEvent.NavigateBack -> Notification.NavigateBack
}

@Composable
private fun ColorChip(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ===== Preview =====

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProjectEditorContentPreview(
    state: ProjectEditorUiState,
    onNameChange: (String) -> Unit = {},
    onColorChange: (Int) -> Unit = {},
    onDescriptionChange: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onErrorDismiss: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Project") },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSave,
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text("Project name") },
                isError = state.errorMessage != null,
                supportingText = state.errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Color", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PRESET_COLORS.forEach { color ->
                    ColorChip(
                        color = Color(color),
                        selected = state.color == color,
                        onClick = { onColorChange(color) }
                    )
                }
            }

            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = { Text("Description (optional)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.errorMessage != null && !state.saving) {
        ResultDialog(
            title = "Error",
            text = state.errorMessage ?: "",
            onDismiss = onErrorDismiss
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ProjectEditorScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    ProjectEditorContentPreview(
        state = ProjectEditorUiState(
            name = "My Project",
            color = 0xFF1976D2.toInt(),
            description = "A great project description",
        ),
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ProjectEditorScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    ProjectEditorContentPreview(
        state = ProjectEditorUiState(
            name = "My Project",
            color = 0xFF388E3C.toInt(),
            description = "A great project description",
        ),
    )
}
