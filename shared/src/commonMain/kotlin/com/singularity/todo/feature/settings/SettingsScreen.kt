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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.singularity.todo.core.ui.components.TaggedSnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.theme.AiStatusColors
import com.singularity.todo.core.files.FilePickPurpose
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.files.rememberAppFilePicker
import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewProfileRepository
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.backup.BackupIntent
import com.singularity.todo.feature.backup.BackupScreen
import com.singularity.todo.feature.backup.BackupViewModel
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncSettingsScreen
import com.singularity.todo.core.ui.onboarding.OnboardingSettingsRepository
import com.singularity.todo.feature.sync.presentation.SyncSettingsScreen
import com.singularity.todo.feature.profile.presentation.AccountSettingsScreen
import com.singularity.todo.feature.profile.presentation.AccountSettingsViewModel
import com.singularity.todo.feature.settings.screens.AgendaSettingsScreen
import com.singularity.todo.feature.settings.screens.AiProviderSettingsScreen
import com.singularity.todo.feature.settings.screens.FilesSettingsScreen
import com.singularity.todo.feature.settings.screens.InterfaceSettingsScreen
import com.singularity.todo.feature.settings.screens.NotificationSettingsScreen
import com.singularity.todo.feature.settings.screens.WorkScheduleSettingsScreen
import com.singularity.todo.feature.tags.TagsScreen
import com.singularity.todo.feature.tags.TagsViewModel
import com.singularity.todo.feature.tags.presentation.screen.TagGroupsScreen
import com.singularity.todo.feature.tags.presentation.viewmodel.TagGroupsIntent
import com.singularity.todo.feature.tags.presentation.viewmodel.TagGroupsUiState
import com.singularity.todo.feature.tags.presentation.viewmodel.TagGroupsViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private enum class SettingsTab(val label: String) {
    Interface("Interface"),
    Agenda("Agenda"),
    Notifications("Notifications"),
    AIProvider("AI Provider"),
    WorkSchedule("Work Schedule"),
    Calendar("Calendar"),
    Sync("Sync"),
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
        SettingsTab.Sync -> Icons.Filled.CloudSync
        SettingsTab.Tags -> Icons.AutoMirrored.Filled.Label
        SettingsTab.TagGroups -> Icons.AutoMirrored.Filled.Label
        SettingsTab.Files -> Icons.Filled.Folder
        SettingsTab.Backup -> Icons.Filled.CloudUpload
        SettingsTab.Account -> Icons.Filled.AccountCircle
    }

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = koinViewModel()
    val uiState by viewModel.stateFlow.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(SettingsTab.Interface) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted state for the tags undo-snackbar countdown bar.
    var tagsCountdownProgress by remember { mutableStateOf<Float?>(null) }

    // Show snackbar on error, then dismiss it
    LaunchedEffect((uiState as? SettingsUiState.Content)?.errorMessage) {
        val msg = uiState.errorMessage
            ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.onIntent(SettingsIntent.DismissError)
    }

    Scaffold(
        snackbarHost = { TaggedSnackbarHost(snackbarHostState, countdownProgress = tagsCountdownProgress) },
        modifier = modifier,
    ) { paddingValues ->
        when (val state = uiState) {
            else -> SettingsContent(
                snackbarHostState = snackbarHostState,
                state = state,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onIntent = viewModel::onIntent,
                onOpenAttachmentsFolder = { viewModel.onIntent(SettingsIntent.OpenAttachmentsFolder) },
                attachmentsPath = koinInject<FileRevealer>().attachmentsBasePath(),
                onboarding = koinInject<OnboardingSettingsRepository>(),
                modifier = Modifier.padding(paddingValues),
                onTagsCountdownProgress = { tagsCountdownProgress = it },
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
    snackbarHostState: SnackbarHostState,
    state: SettingsUiState.Content,
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    onIntent: (SettingsIntent) -> Unit,
    onOpenAttachmentsFolder: () -> Unit,
    attachmentsPath: String,
    // Nullable so the previews, which have no Koin graph, render the same screen minus
    // the one row that needs the store rather than failing to resolve it.
    onboarding: OnboardingSettingsRepository? = null,
    modifier: Modifier = Modifier,
    previewOverrides: Map<SettingsTab, @Composable () -> Unit> = emptyMap(),
    onTagsCountdownProgress: (Float?) -> Unit = {},
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
                    SettingsTab.Interface -> InterfaceSettingsScreen(
                        state = state,
                        onIntent = onIntent,
                        onboarding = onboarding,
                    )

                    SettingsTab.Agenda -> AgendaSettingsScreen(state = state, onIntent = onIntent)

                    SettingsTab.Notifications -> NotificationSettingsScreen(state = state, onIntent = onIntent)

                    SettingsTab.AIProvider -> AiProviderSettingsScreen(state = state, onIntent = onIntent)

                    SettingsTab.WorkSchedule -> WorkScheduleSettingsScreen(state = state, onIntent = onIntent)

                    SettingsTab.Calendar -> CalendarSyncSettingsScreen()

                    SettingsTab.Sync -> SyncSettingsScreen()

                    SettingsTab.Tags -> {
                        val tagsVm: TagsViewModel = koinViewModel()
                        TagsScreen(
                            viewModel = tagsVm,
                        )
                    }

                    SettingsTab.TagGroups -> {
                        val tagGroupsVm: TagGroupsViewModel = koinViewModel()
                        val tagGroupsState by tagGroupsVm.stateFlow.collectAsStateWithLifecycle()
                        TagGroupsScreen(
                            state = tagGroupsState,
                            onDelete = { id -> tagGroupsVm.onIntent(TagGroupsIntent.Delete(id)) },
                        )
                    }

                    SettingsTab.Files -> FilesSettingsScreen(
                        attachmentsPath = attachmentsPath,
                        onOpenAttachmentsFolder = onOpenAttachmentsFolder,
                        logExportEphemeral = state.logExportEphemeral,
                        onExportLogs = { onIntent(SettingsIntent.ExportLogs) },
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
    val backupState by backupVm.stateFlow.collectAsStateWithLifecycle()

    // The restore / settings-import flows need the *path* the user picked, not just the
    // fact that they picked. `rememberAppFilePicker` hands the path straight to the
    // intent, so the launcher callback and the VM stay in step with no intermediate state.
    val pickBackup = rememberAppFilePicker(FilePickPurpose.Backup) { picked ->
        val path = picked?.path ?: return@rememberAppFilePicker
        backupVm.onIntent(BackupIntent.Restore(path))
    }
    val pickSettings = rememberAppFilePicker(FilePickPurpose.SettingsJson) { picked ->
        val source = picked?.path ?: return@rememberAppFilePicker
        backupVm.onIntent(BackupIntent.ImportSettingsFrom(source))
    }
    val sharePort: SharePort = koinInject()

    BackupScreen(
        state = backupState,
        events = backupVm.events,
        onBack = onBack,
        onCreateBackup = { backupVm.onIntent(BackupIntent.CreateBackup) },
        onSelectRestoreFile = pickBackup,
        onRestore = { path -> backupVm.onIntent(BackupIntent.Restore(path)) },
        onDelete = { id -> backupVm.onIntent(BackupIntent.Delete(id)) },
        onPush = { id -> backupVm.onIntent(BackupIntent.Push(id)) },
        onExportSettings = { backupVm.onIntent(BackupIntent.ExportSettingsSnapshot) },
        onSelectSettingsFile = pickSettings,
        onShareSettingsJson = { json -> sharePort.shareText("Singularity settings", json) },
        onImportSettings = { json -> backupVm.onIntent(BackupIntent.ImportSettingsSnapshot(json)) },
    )
}

@Composable
private fun SettingsNavRail(
    selectedTab: SettingsTab,
    aiTestResult: AiTestResult,
    modifier: Modifier = Modifier,
    onSelect: (SettingsTab) -> Unit,
) {
    // The rail is a fixed 80dp column holding twelve ~76dp rows. That is taller
    // than a phone viewport, so without a scroll the trailing tabs (Backup,
    // Account) are clipped and unreachable — the screen looked complete but two
    // settings were simply not tappable. Scrolls on every form factor; on a
    // tablet the content simply fits and the scroll never engages.
    Column(
        modifier = modifier.width(80.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SettingsTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Column(
                modifier = Modifier.clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics { selected = isSelected }
                    .padding(vertical = 12.dp, horizontal = 8.dp)
                    .testTag(TestTags.settingsTab(tab.name)),
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
        val fakeTagsRepo = com.singularity.todo.test.fakes.FakeTagsRepository()
        val fakeCreateTag = com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase(
            fakeTagsRepo,
            com.singularity.todo.test.fakes.FakeClock(),
        )
        val fakeUpdateTag = com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase(
            fakeTagsRepo,
            com.singularity.todo.test.fakes.FakeClock(),
        )
        val vm = TagsViewModel(
            tagRepo = fakeTagsRepo,
            createTag = fakeCreateTag,
            updateTag = fakeUpdateTag,
            currentUser = com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser(),
        )
        TagsScreen(viewModel = vm)
    },
    SettingsTab.TagGroups to {
        TagGroupsScreen(state = TagGroupsUiState.Empty, onDelete = {})
    },
    SettingsTab.Backup to {
        // BackupScreen requires BackupViewModel (Koin) - show placeholder in preview
        Text("Backup", modifier = Modifier.padding(16.dp))
    },
    SettingsTab.Account to {
        AccountSettingsScreen(vm = AccountSettingsViewModel(PreviewProfileRepository))
    },
)

@Composable
private fun SettingsScreenPreview(selectedTab: SettingsTab) {
    SettingsContent(
        snackbarHostState = SnackbarHostState(),
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
