package com.singularity.todo.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.singularity.todo.core.settings.SettingsIntent
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.backup.BackupScreen
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.profile.presentation.AccountSettingsScreen
import com.singularity.todo.feature.profile.presentation.AccountSettingsViewModel
import com.singularity.todo.feature.settings.screens.AgendaSettingsScreen
import com.singularity.todo.feature.settings.screens.AiProviderSettingsScreen
import com.singularity.todo.feature.settings.screens.FilesSettingsScreen
import com.singularity.todo.feature.settings.screens.InterfaceSettingsScreen
import com.singularity.todo.feature.settings.screens.NotificationSettingsScreen
import com.singularity.todo.feature.settings.screens.WorkScheduleSettingsScreen
import com.singularity.todo.test.fakes.FakeProfileRepository
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private enum class SettingsTab(val label: String) {
    Interface("Interface"),
    Agenda("Agenda"),
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
    val uiState by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf(SettingsTab.Interface) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar on error, then dismiss it
    LaunchedEffect((uiState as? SettingsUiState.Content)?.errorMessage) {
        val msg = (uiState as? SettingsUiState.Content)?.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.processIntent(SettingsIntent.DismissError)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        when (val state = uiState) {
            is SettingsUiState.Loading -> LoadingIndicator(modifier = Modifier.padding(paddingValues))

            is SettingsUiState.Error -> EmptyState(title = "Error", subtitle = state.cause.toString(), modifier = Modifier.padding(paddingValues))

            is SettingsUiState.Content -> SettingsContent(
                state = state,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                viewModel = viewModel,
                modifier = Modifier.padding(paddingValues),
            )
        }
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
    Row(modifier = modifier.fillMaxSize()) {
        // Navigation rail on wide screens (disabled until WindowSizeClass is wired)
        SettingsNavRail(
            selectedTab = selectedTab,
            aiTestResult = state.aiEphemeral.testResult,
            modifier = Modifier.fillMaxHeight(),
            onSelect = onSelectTab,
        )
        VerticalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (selectedTab) {
                SettingsTab.Interface -> InterfaceSettingsScreen(
                    state = state,
                    onIntent = viewModel::processIntent,
                )

                SettingsTab.Agenda -> AgendaSettingsScreen(
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

                SettingsTab.Files -> FilesSettingsScreen(
                    attachmentsPath = koinInject<FileRevealer>().attachmentsBasePath(),
                    onOpenAttachmentsFolder = {
                        viewModel.processIntent(SettingsIntent.OpenAttachmentsFolder)
                    },
                )

                SettingsTab.Backup -> BackupScreenWrapper(
                    onBack = { onSelectTab(SettingsTab.Interface) },
                    onSelectRestoreFile = { /* Platform shell provides file picker on Android */ },
                )

                SettingsTab.Account -> AccountSettingsScreen()
            }
        }
    }
}

@Composable
private fun BackupScreenWrapper(onBack: () -> Unit, onSelectRestoreFile: () -> Unit) {
    val backupVm: BackupViewModel = koinViewModel()
    val backupState by backupVm.state.collectAsState()
    BackupScreen(
        state = backupState,
        events = backupVm.events,
        snackbar = backupVm.snackbar,
        onBack = onBack,
        onCreateBackup = backupVm::createBackup,
        onSelectRestoreFile = onSelectRestoreFile,
        onRestore = { path -> backupVm.import(path) },
        onDelete = backupVm::delete,
        onPush = backupVm::push,
        // Settings snapshot — platform shell handles file picking / sharing
        onExportSettings = backupVm::exportSettingsSnapshot,
        onSelectSettingsFile = { /* shell opens file picker → calls importSettingsSnapshot */ },
        onShareSettingsJson = { /* shell shows share sheet with JSON */ },
        onImportSettings = { json -> backupVm.importSettingsSnapshot(json) },
    )
}

@Composable
private fun SettingsNavRail(
    selectedTab: SettingsTab,
    aiTestResult: AiTestResult,
    modifier: Modifier = Modifier,
    onSelect: (SettingsTab) -> Unit,
) {
    Column(modifier = modifier.width(80.dp).padding(vertical = 8.dp)) {
        SettingsTab.entries.forEach { tab ->
            val icon = when (tab) {
                SettingsTab.Interface -> Icons.Filled.Palette
                SettingsTab.Agenda -> Icons.Filled.Schedule
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
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            shape = CircleShape,
                            color = if (tab == selectedTab) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = tab.label)
                    // AI connection status badge on AI Provider tab
                    if (tab == SettingsTab.AIProvider) {
                        val badgeColor = when (aiTestResult) {
                            is AiTestResult.Ok -> Color(0xFF4CAF50)

                            // green
                            is AiTestResult.Error -> Color(0xFFF44336)

                            // red
                            else -> Color(0xFF9E9E9E) // grey
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .background(badgeColor, CircleShape),
                        )
                    }
                }
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
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
    Row(modifier = Modifier.fillMaxSize()) {
        SettingsNavRail(
            selectedTab = selectedTab,
            aiTestResult = state.aiEphemeral.testResult,
            modifier = Modifier.fillMaxHeight(),
            onSelect = onSelectTab,
        )
        VerticalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (selectedTab) {
                SettingsTab.Interface -> InterfaceSettingsScreen(
                    state = state,
                    onIntent = {},
                )

                SettingsTab.Agenda -> AgendaSettingsScreen(
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

                SettingsTab.Files -> FilesSettingsScreen(
                    attachmentsPath = "/data/user/0/com.singularity.todo/files/attachments",
                    onOpenAttachmentsFolder = {},
                )

                SettingsTab.Backup -> {
                    // BackupScreen requires BackupViewModel - show placeholder in preview
                    Text("Backup", modifier = Modifier.padding(16.dp))
                }

                SettingsTab.Account -> AccountSettingsScreen(
                    vm = AccountSettingsViewModel(FakeProfileRepository()),
                )
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
