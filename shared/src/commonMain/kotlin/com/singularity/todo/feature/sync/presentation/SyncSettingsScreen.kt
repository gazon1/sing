package com.singularity.todo.feature.sync.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.sync.ATTACHMENTS_SYNC_UNAVAILABLE_REASON
import com.singularity.todo.core.sync.ConnectionTestResult
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.core.sync.ATTACHMENTS_SYNC_TRANSPORT_AVAILABLE
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.SettingsActionRow
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.core.ui.components.SettingsValueRow
import com.singularity.todo.core.ui.preview.PreviewThemed
import org.koin.compose.viewmodel.koinViewModel

/** Interval choices offered for periodic sync, in minutes. */
private val INTERVAL_CHOICES = listOf(15, 30, 60, 180, 360)

/**
 * Sync settings.
 *
 * ## Why this screen exists
 *
 * It introduces no capability. [SyncViewModel] and [SyncButton] were both already
 * written and both were reachable from nowhere: the view model was bound in the Koin
 * graph and never injected, the button had no call site at all. A settings section the
 * user could never open is the defect this change set set out to remove, so this is the
 * missing end of an existing path.
 *
 * ## The attachment row
 *
 * The attachment sync switch renders **locked** while [ATTACHMENTS_SYNC_TRANSPORT_AVAILABLE]
 * is `false` and says why in its subtitle. Presenting it as a working toggle would be the
 * same defect one layer up: the preference is stored per account+profile and is read here,
 * but no device can act on it yet, so a switch that accepted a tap would be reporting a
 * sync that never happens.
 *
 * It is shown rather than hidden because a user who finds attachments missing from their
 * sync deserves to learn that the capability exists and is not ready, which is different
 * from learning it does not exist.
 */
@Composable
fun SyncSettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SyncViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    SyncSettingsContent(state = state, onIntent = viewModel::onIntent, modifier = modifier)
}

/**
 * Stateless body of [SyncSettingsScreen], so previews and UI tests can supply a [SyncState]
 * without standing up a Koin graph.
 */
@Composable
fun SyncSettingsContent(
    state: SyncState,
    onIntent: (SyncIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .testTag(TestTags.Settings.content(TestTags.Sync.TAB_SLUG))
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Status") {
            SettingsValueRow(
                title = "Sync now",
                value = state.status.label(),
                subtitle = state.errorMessage,
                onClick = { onIntent(SyncIntent.SyncNow) },
                testTag = TestTags.Sync.SYNC_NOW_ROW,
            )
        }

        SettingsSection(title = "Automatic Sync") {
            SettingsSwitchRow(
                title = "Sync automatically",
                subtitle = "Run a sync cycle on a timer",
                checked = state.autoSyncEnabled,
                onCheckedChange = { onIntent(SyncIntent.SetAutoSync(it)) },
                testTag = TestTags.Sync.AUTO_SYNC_SWITCH,
            )
            SettingsValueRow(
                title = "Interval",
                value = intervalLabel(state.intervalMinutes),
                subtitle = if (state.autoSyncEnabled) {
                    "Tap to cycle through ${INTERVAL_CHOICES.joinToString(", ")} minutes"
                } else {
                    "Turn on automatic sync to apply this"
                },
                onClick = { onIntent(SyncIntent.SetInterval(nextInterval(state.intervalMinutes))) },
                testTag = TestTags.Sync.INTERVAL_ROW,
            )
        }

        SettingsSection(title = "Attachments") {
            SettingsSwitchRow(
                title = "Sync attachments",
                subtitle = if (ATTACHMENTS_SYNC_TRANSPORT_AVAILABLE) {
                    "Upload file contents along with the tasks that reference them"
                } else {
                    ATTACHMENTS_SYNC_UNAVAILABLE_REASON
                },
                checked = state.attachmentsSyncEnabled,
                // Null, not a handler that calls into a stage that does not exist.
                // `SettingsSwitchRow` treats that as inert, so the row cannot be
                // tapped and its switch cannot look live. Stage 2 replaces this
                // argument with `SyncIntent.SetAttachmentsSync`.
                onCheckedChange = null,
                enabled = ATTACHMENTS_SYNC_TRANSPORT_AVAILABLE,
                testTag = TestTags.Sync.ATTACHMENTS_SWITCH,
            )
        }

        SettingsSection(title = "Diagnostics") {
            SettingsActionRow(
                title = "Test connection",
                subtitle = connectionSubtitle(state),
                onClick = { onIntent(SyncIntent.TestConnection) },
                testTag = TestTags.Sync.TEST_CONNECTION_ROW,
            )
        }
    }
}

/**
 * The next interval offered when the row is tapped.
 *
 * Cycles rather than opening a picker: the list is short, fixed, and a dialog for five
 * choices costs a screen and a focus trap to save two taps. An unknown current value
 * (a row written by a newer client, say) starts at the first choice instead of
 * wrapping to the end.
 */
private fun nextInterval(current: Int): Int {
    val index = INTERVAL_CHOICES.indexOf(current)
    return INTERVAL_CHOICES[(if (index < 0) 0 else index + 1) % INTERVAL_CHOICES.size]
}

private fun intervalLabel(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h"

private fun connectionSubtitle(state: SyncState): String? = when (val result = state.connectionTestResult) {
    null -> if (state.isTestingConnection) "Checking…" else null
    is ConnectionTestResult.Success -> "Connection OK"
    is ConnectionTestResult.Failure -> result.error.message
}

private fun SyncEngineStatus.label(): String = when (this) {
    is SyncEngineStatus.Idle -> "Idle"
    is SyncEngineStatus.Pushing, is SyncEngineStatus.Pulling -> "Syncing…"
    is SyncEngineStatus.NoConnection -> "Offline"
    is SyncEngineStatus.Failure -> "Error"
}

@Preview
@Composable
private fun SyncSettingsIdlePreview() = PreviewThemed {
    SyncSettingsContent(state = SyncState(), onIntent = {})
}

@Preview
@Composable
private fun SyncSettingsOfflinePreview() = PreviewThemed(darkTheme = true) {
    SyncSettingsContent(
        state = SyncState(
            status = SyncEngineStatus.NoConnection,
            errorMessage = "Server unreachable",
            autoSyncEnabled = true,
            intervalMinutes = 60,
        ),
        onIntent = {},
    )
}
