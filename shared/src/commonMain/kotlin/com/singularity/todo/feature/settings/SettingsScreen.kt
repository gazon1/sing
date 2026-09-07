package com.singularity.todo.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.backup.BackupScreen
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.settings.screens.AccountSettingsScreen
import com.singularity.todo.feature.settings.screens.AiProviderSettingsScreen
import com.singularity.todo.feature.settings.screens.FilesSettingsScreen
import com.singularity.todo.feature.settings.screens.InterfaceSettingsScreen
import com.singularity.todo.feature.settings.screens.NotificationSettingsScreen
import com.singularity.todo.feature.settings.screens.WorkScheduleSettingsScreen
import org.koin.compose.viewmodel.koinViewModel

private enum class SettingsTab(val label: String) {
    Interface("Interface"),
    Notifications("Notifications"),
    AIProvider("AI Provider"),
    WorkSchedule("Work Schedule"),
    Files("Files"),
    Backup("Backup"),
    Account("Account"),
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(SettingsTab.Interface) }

    when (val state = uiState) {
        is SettingsUiState.Loading -> LoadingIndicator(modifier = modifier)
        is SettingsUiState.Error -> EmptyState(title = "Error", subtitle = state.cause.toString(), modifier = modifier)
        is SettingsUiState.Content -> SettingsContent(
            state = state,
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            viewModel = viewModel,
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState.Content,
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        // Navigation rail on wide screens (disabled until WindowSizeClass is wired)
        SettingsNavRail(selectedTab, Modifier.fillMaxHeight(), onSelectTab)
        VerticalDivider()

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                SettingsTab.Interface -> InterfaceSettingsScreen(
                    state = state,
                    onIntent = viewModel::processIntent,
                )
                SettingsTab.Notifications -> NotificationSettingsScreen(
                    state = state,
                    onIntent = viewModel::processIntent,
                )
                SettingsTab.AIProvider -> AiProviderSettingsScreen(
                    state = state,
                    onIntent = viewModel::processIntent,
                )
                SettingsTab.WorkSchedule -> WorkScheduleSettingsScreen(
                    state = state,
                    onIntent = viewModel::processIntent,
                )
                SettingsTab.Files -> FilesSettingsScreen()
                SettingsTab.Backup -> BackupScreenWrapper(onBack = { onSelectTab(SettingsTab.Interface) })
                SettingsTab.Account -> AccountSettingsScreen(state = state)
            }
        }
    }
}

@Composable
private fun BackupScreenWrapper(onBack: () -> Unit) {
    val backupVm: BackupViewModel = koinViewModel()
    val backupState by backupVm.state.collectAsState()
    BackupScreen(
        state = backupState,
        events = backupVm.events,
        onBack = onBack,
        onCreateBackup = backupVm::createBackup,
        onRestore = backupVm::restore,
        onDelete = backupVm::delete,
        onPush = backupVm::push,
    )
}

@Composable
private fun SettingsNavRail(
    selectedTab: SettingsTab,
    modifier: Modifier = Modifier,
    onSelect: (SettingsTab) -> Unit,
) {
    Column(modifier = modifier.width(80.dp).padding(vertical = 8.dp)) {
        SettingsTab.entries.forEach { tab ->
            val icon = when (tab) {
                SettingsTab.Interface -> Icons.Filled.Palette
                SettingsTab.Notifications -> Icons.Filled.Notifications
                SettingsTab.AIProvider -> Icons.Filled.SmartToy
                SettingsTab.WorkSchedule -> Icons.Filled.Schedule
                SettingsTab.Files -> Icons.Filled.Folder
                SettingsTab.Backup -> Icons.Filled.CloudUpload
                SettingsTab.Account -> Icons.Filled.AccountCircle
            }
            Column(
                modifier = Modifier
                    .clickable { onSelect(tab) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = tab.label)
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

// ===== Preview =====

@Composable
private fun SettingsContentPreview(
    state: SettingsUiState.Content,
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxHeight()) {
        SettingsNavRail(selectedTab, Modifier.fillMaxHeight(), onSelectTab)
        VerticalDivider()

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                SettingsTab.Interface -> InterfaceSettingsScreen(
                    state = state,
                    onIntent = {},
                )
                SettingsTab.Notifications -> NotificationSettingsScreen(
                    state = state,
                    onIntent = {},
                )
                SettingsTab.AIProvider -> AiProviderSettingsScreen(
                    state = state,
                    onIntent = {},
                )
                SettingsTab.WorkSchedule -> WorkScheduleSettingsScreen(
                    state = state,
                    onIntent = {},
                )
                SettingsTab.Files -> FilesSettingsScreen()
                SettingsTab.Backup -> {
                    // BackupScreen requires BackupViewModel - show placeholder in preview
                    Text("Backup", modifier = Modifier.padding(16.dp))
                }
                SettingsTab.Account -> AccountSettingsScreen(state = state)
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    SettingsContentPreview(
        state = SettingsUiState.Content(),
        selectedTab = SettingsTab.Interface,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    SettingsContentPreview(
        state = SettingsUiState.Content(),
        selectedTab = SettingsTab.Account,
    )
}
