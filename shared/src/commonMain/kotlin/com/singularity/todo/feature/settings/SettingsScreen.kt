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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.backup.BackupScreen
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncSettingsScreen
import com.singularity.todo.feature.profile.presentation.AccountSettingsScreen
import com.singularity.todo.feature.profile.presentation.AccountSettingsViewModel
import com.singularity.todo.feature.settings.screens.AgendaSettingsScreen
import com.singularity.todo.feature.settings.screens.AiProviderSettingsScreen
import com.singularity.todo.feature.settings.screens.FilesSettingsScreen
import com.singularity.todo.feature.settings.screens.InterfaceSettingsScreen
import com.singularity.todo.feature.settings.screens.NotificationSettingsScreen
import com.singularity.todo.feature.settings.screens.WorkScheduleSettingsScreen
import com.singularity.todo.feature.tags.TagsScreen
import com.singularity.todo.feature.tags.TagsUiState
import com.singularity.todo.feature.tags.TagsViewModel
import com.singularity.todo.feature.tags.presentation.screen.TagGroupsScreen
import com.singularity.todo.feature.tags.presentation.viewmodel.TagGroupsIntent
import com.singularity.todo.feature.tags.presentation.viewmodel.TagGroupsUiState
import com.singularity.todo.feature.tags.presentation.viewmodel.TagGroupsViewModel
import com.singularity.todo.test.fakes.FakeProfileRepository
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private enum class SettingsTab(val label: String) {
    Interface("Interface"),
    Agenda("Agenda"),
    Notifications("Notifications"),
    AIProvider("AI Provider"),
    WorkSchedule("Work Schedule"),
    Calendar("Calendar"),
    Tags("Tags"),
    TagGroups("Tag Groups"),
    Files("Files"),
    Backup("Backup"),
    Account("Account"),
}

private val SettingsTab.icon
    get() = when (this) {
        SettingsTab.Interface -> Icons.Filled.Palette
        SettingsTab.Agenda -> Icons.Filled.Schedule
        SettingsTab.Notifications -> Icons.Filled.Notifications
        SettingsTab.AIProvider -> Icons.Filled.SmartToy
        SettingsTab.WorkSchedule -> Icons.Filled.Schedule
        SettingsTab.Calendar -> Icons.Filled.CalendarMonth
        SettingsTab.Tags -> Icons.AutoMirrored.Filled.Label
        SettingsTab.TagGroups -> Icons.AutoMirrored.Filled.Label
        SettingsTab.Files -> Icons.Filled.Folder
        SettingsTab.Backup -> Icons.Filled.CloudUpload
        SettingsTab.Account -> Icons.Filled.AccountCircle
    }

/** AI connection-status badge colors, named instead of inline hex + `// comment`. */
private object AiStatusColors {
    val Ok = Color(0xFF4CAF50)
    val Error = Color(0xFFF44336)
    val Unknown = Color(0xFF9E9E9E)
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = koinViewModel()
    val uiState by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf(SettingsTab.Interface) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar on error, then dismiss it
    LaunchedEffect((uiState as? SettingsUiState.Content)?.errorMessage) {
        val msg = (uiState as? SettingsUiState.Content)?.errorMessage
            ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.processIntent(SettingsIntent.DismissError)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        when (val state = uiState) {
            is SettingsUiState.Loading -> LoadingIndicator(modifier = Modifier.padding(paddingValues))

            is SettingsUiState.Error -> EmptyState(
                title = "Error",
                subtitle = state.cause.toString(),
                modifier = Modifier.padding(paddingValues),
            )

            is SettingsUiState.Content -> SettingsContent(
                state = state,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onIntent = viewModel::processIntent,
                onOpenAttachmentsFolder = { viewModel.processIntent(SettingsIntent.OpenAttachmentsFolder) },
                attachmentsPath = koinInject<FileRevealer>().attachmentsBasePath(),
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

/**
 * Nav rail + selected tab's body. This is the single source of truth for what
 * each [SettingsTab] renders — both the real screen and its `@Preview`s call
 * this same composable, so the two can no longer drift apart the way the old
 * `SettingsContent`/`SettingsContentPreview` pair did (they duplicated the
 * entire eleven-branch `when` with only the callbacks swapped for `{}`).
 *
 * Tabs whose body needs its own ViewModel (Tags, TagGroups, Backup) resolve it
 * internally via [koinViewModel] when [previewOverrides] doesn't supply one;
 * previews pass fixed content for those tabs through [previewOverrides]
 * instead of trying to fake a ViewModel.
 */
@Composable
private fun SettingsContent(
    state: SettingsUiState.Content,
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    onIntent: (SettingsIntent) -> Unit,
    onOpenAttachmentsFolder: () -> Unit,
    attachmentsPath: String,
    modifier: Modifier = Modifier,
    previewOverrides: Map<SettingsTab, @Composable () -> Unit> = emptyMap(),
) {
    Row(modifier = modifier.fillMaxSize()) {
        SettingsNavRail(
            selectedTab = selectedTab,
            aiTestResult = state.aiEphemeral.testResult,
            modifier = Modifier.fillMaxHeight(),
            onSelect = onSelectTab,
        )
        VerticalDivider()

        Box(
            modifier = Modifier.weight(1f)
                .fillMaxHeight(),
        ) {
            previewOverrides[selectedTab]?.invoke()
                ?: when (selectedTab) {
                    SettingsTab.Interface -> InterfaceSettingsScreen(state = state, onIntent = onIntent)

                    SettingsTab.Agenda -> AgendaSettingsScreen(state = state, onIntent = onIntent)

                    SettingsTab.Notifications -> NotificationSettingsScreen(state = state, onIntent = onIntent)

                    SettingsTab.AIProvider -> AiProviderSettingsScreen(state = state, onIntent = onIntent)

                    SettingsTab.WorkSchedule -> WorkScheduleSettingsScreen(state = state, onIntent = onIntent)

                    SettingsTab.Calendar -> CalendarSyncSettingsScreen()

                    SettingsTab.Tags -> {
                        val tagsVm: TagsViewModel = koinViewModel()
                        val tagsState by tagsVm.state.collectAsState()
                        TagsScreen(state = tagsState, onDelete = tagsVm::delete)
                    }

                    SettingsTab.TagGroups -> {
                        val tagGroupsVm: TagGroupsViewModel = koinViewModel()
                        val tagGroupsState by tagGroupsVm.state.collectAsState()
                        TagGroupsScreen(
                            state = tagGroupsState,
                            onDelete = { id -> tagGroupsVm.onIntent(TagGroupsIntent.Delete(id)) },
                        )
                    }

                    SettingsTab.Files -> FilesSettingsScreen(
                        attachmentsPath = attachmentsPath,
                        onOpenAttachmentsFolder = onOpenAttachmentsFolder,
                    )

                    SettingsTab.Backup -> BackupScreenWrapper(onBack = { onSelectTab(SettingsTab.Interface) })

                    SettingsTab.Account -> AccountSettingsScreen()
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
        snackbar = backupVm.snackbar,
        onBack = onBack,
        onCreateBackup = backupVm::createBackup,
        onSelectRestoreFile = { /* Platform shell provides file picker on Android */ },
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
    Column(
        modifier = modifier.width(80.dp)
            .padding(vertical = 8.dp),
    ) {
        SettingsTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Column(
                modifier = Modifier.clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics { selected = isSelected }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(48.dp)
                        .background(
                            shape = CircleShape,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(tab.icon, contentDescription = tab.label)
                    if (tab == SettingsTab.AIProvider) {
                        AiStatusBadge(
                            aiTestResult = aiTestResult,
                            modifier = Modifier.align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp),
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

@Composable
private fun AiStatusBadge(aiTestResult: AiTestResult, modifier: Modifier = Modifier) {
    val badgeColor = when (aiTestResult) {
        is AiTestResult.Ok -> AiStatusColors.Ok
        is AiTestResult.Error -> AiStatusColors.Error
        else -> AiStatusColors.Unknown
    }
    Box(
        modifier = modifier.size(8.dp)
            .background(badgeColor, CircleShape),
    )
}

// Reuses the real SettingsContent (see SettingsScreen.kt) instead of keeping a
// second, hand-copied `when` over SettingsTab — the two previously drifted
// apart because every real-screen change had to be hand-mirrored here.
//
// Tabs whose body normally resolves its own ViewModel via koinViewModel()
// (Tags, TagGroups, Backup, Account) aren't wired to Koin in a @Preview, so
// those four are substituted through `previewOverrides` with fixed/fake state.
// Every other tab (Interface, Agenda, Notifications, AIProvider,
// WorkSchedule, Calendar, Files) renders through the exact same branch the
// real screen uses.

private val previewOverrides: Map<SettingsTab, @Composable () -> Unit> = mapOf(
    SettingsTab.Tags to {
        TagsScreen(state = TagsUiState.Empty, onDelete = {})
    },
    SettingsTab.TagGroups to {
        TagGroupsScreen(state = TagGroupsUiState.Empty, onDelete = {})
    },
    SettingsTab.Backup to {
        // BackupScreen requires BackupViewModel (Koin) - show placeholder in preview
        Text("Backup", modifier = Modifier.padding(16.dp))
    },
    SettingsTab.Account to {
        AccountSettingsScreen(vm = AccountSettingsViewModel(FakeProfileRepository()))
    },
)

@Composable
private fun SettingsScreenPreview(selectedTab: SettingsTab) {
    SettingsContent(
        state = SettingsUiState.Content(),
        selectedTab = selectedTab,
        onSelectTab = {},
        onIntent = {},
        onOpenAttachmentsFolder = {},
        attachmentsPath = "/data/user/0/com.singularity.todo/files/attachments",
        previewOverrides = previewOverrides,
    )
}

@Preview
@Composable
private fun SettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    SettingsScreenPreview(selectedTab = SettingsTab.Interface)
}

@Preview
@Composable
private fun SettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    SettingsScreenPreview(selectedTab = SettingsTab.Account)
}
