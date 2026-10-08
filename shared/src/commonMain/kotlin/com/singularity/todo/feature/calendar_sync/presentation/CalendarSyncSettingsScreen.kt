package com.singularity.todo.feature.calendar_sync.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary
import com.singularity.todo.feature.calendar_sync.permission.rememberCalendarPermissionRequester
import com.singularity.todo.feature.calendar_sync.presentation.CalendarProvider.Google
import com.singularity.todo.feature.calendar_sync.presentation.CalendarProvider.SystemCalendar
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.LoadCalendars
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SelectAppPackage
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SelectCalendar
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SelectGoogleCalendar
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SelectProvider
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SetEnabled
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SetGoogleConnected
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SetImportFromGoogle
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SyncNow
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SyncGoogleNow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration

/**
 * Calendar sync settings screen.
 *
 * Allows the user to:
 * 1. Choose which calendar the feature talks to — the device's system calendar or Google
 * 2. Grant READ/WRITE_CALENDAR permissions (system calendar only, on first visit)
 * 3. Enable/disable sync, pick the target calendar, and trigger a manual sync
 *
 * Integrated into the Settings tab via [com.singularity.todo.feature.settings.SettingsScreen].
 */
@Composable
fun CalendarSyncSettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: CalendarSyncViewModel = koinViewModel()
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()

    val permissionRequester = rememberCalendarPermissionRequester()

    LaunchedEffect(Unit) {
        viewModel.onIntent(LoadCalendars)
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ─── Provider selector ───────────────────────────────────────────
        // Always rendered, above both halves. It is the only part of this screen a desktop
        // user needs: Google works there and the system calendar does not, so a gate that
        // ran before the selector would hide the one working option behind an
        // "Android only" message.
        ProviderSelector(
            selected = state.provider,
            onSelect = { viewModel.onIntent(SelectProvider(it)) },
        )

        if (state.provider == Google) {
            GoogleCalendarPanel(state = state, onIntent = viewModel::onIntent)
        } else {
            SystemCalendarPanel(
                state = state,
                isSupported = permissionRequester.isSupported,
                hasPermissions = permissionRequester.hasPermissions,
                onRequestPermission = { permissionRequester.requestPermissions() },
                onIntent = viewModel::onIntent,
            )
        }
    }
}

/**
 * The two-way choice between the device calendar and Google, as a segmented row.
 *
 * Segmented rather than a switch: the two are alternatives, not a strength of one, and a
 * switch would need a legend to say which way is which.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSelector(selected: CalendarProvider, onSelect: (CalendarProvider) -> Unit) {
    SettingsSection(title = "Calendar Sync") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CalendarProvider.entries.forEachIndexed { index, provider ->
                SegmentedButton(
                    selected = selected == provider,
                    onClick = { onSelect(provider) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = CalendarProvider.entries.size),
                    label = { Text(providerLabel(provider)) },
                    // SegmentedButton exposes no testTag parameter, so the tag goes on
                    // the label the user reads. Keyed by that label rather than by the
                    // enum so a selector addresses what is on screen; a test asserting
                    // "Google Calendar is offered" then also fails if the label is lost.
                    modifier = Modifier.testTag(TestTags.CalendarSync.providerSegment(providerLabel(provider))),
                )
            }
        }
    }
}

/** Segmented-button labels. Short, because the row splits the width in two. */
private fun providerLabel(provider: CalendarProvider): String = when (provider) {
    SystemCalendar -> "System calendar"
    Google -> "Google Calendar"
}

/**
 * "Last synced 06 Oct 2026 02:14", in the device's own zone.
 *
 * Extracted rather than inlined so the Google half formats its own timestamp exactly the way
 * the system half does. Two spellings of "when did this last run" on one screen would drift,
 * and a reader would be left comparing two formats to work out whether they meant different
 * things.
 */
private fun formatGoogleSyncTime(at: kotlinx.datetime.Instant): String {
    val local = at.toLocalDateTime(TimeZone.currentSystemDefault())
    val month = local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$month ${local.dayOfMonth}, ${local.year} $hour:$minute"
}

/**
 * The system-calendar half, including both gates that used to guard the whole screen.
 *
 * The gates belong here rather than at the top of the screen: they are questions about the
 * device calendar, and Google neither asks for nor needs them. Run before the provider
 * choice, a desktop user would have been shown a permission prompt for a feature that
 * cannot work on their platform, and the working alternative was never reachable.
 *
 * @param isSupported The platform gate: whether the permission requester can reach the
 *   system calendar at all. False on desktop.
 * @param onRequestPermission Launches the system dialog.
 * @param onIntent Dispatches into the ViewModel.
 */
@Composable
private fun SystemCalendarPanel(
    state: CalendarSyncUiState,
    isSupported: Boolean,
    hasPermissions: Boolean,
    onRequestPermission: () -> Unit,
    onIntent: (CalendarSyncIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ─── Platform support gate ───────────────────────────────────────
        // Checked before the permission gate on purpose. On desktop the permission
        // requester now reports "unsupported" rather than a fabricated `true`, and the
        // controls below it would render over no-op repositories — an empty calendar
        // list and an enable switch that only appeared to work. Saying so plainly is
        // better than a panel that lies.
        if (!isSupported) {
            UnavailableOnThisPlatform()
            return@Column
        }

        // ─── Permission gate ─────────────────────────────────────────────
        if (!hasPermissions) {
            PermissionGate(onRequestPermission = onRequestPermission)
            return@Column
        }

        SystemCalendarControls(state = state, onIntent = onIntent)
    }
}

/**
 * The system-calendar controls, unchanged in behaviour from before the provider selector
 * existed — including the enable gate on everything below it, so a disabled sync does not
 * present choices that nothing will act on.
 */
@Composable
private fun SystemCalendarControls(state: CalendarSyncUiState, onIntent: (CalendarSyncIntent) -> Unit) {
    // ─── Enable toggle ──────────────────────────────────────────────────
    SettingsSection(title = "System Calendar Sync") {
        SettingsSwitchRow(
            title = "Enable Sync",
            subtitle = "One-way: tasks sync to your system calendar",
            testTag = TestTags.CalendarSync.SYSTEM_ENABLE_SWITCH,
            checked = state.isEnabled,
            onCheckedChange = { onIntent(SetEnabled(it)) },
        )
    }

    // ─── Calendar app picker ─────────────────────────────────────────────
    if (state.isEnabled) {
        CalendarAppPicker(
            selectedAppPackage = state.selectedAppPackage,
            availableApps = state.availableApps,
            onSelectApp = { onIntent(SelectAppPackage(it)) },
        )
    }

    // ─── Calendar selection ──────────────────────────────────────────────
    if (state.isEnabled) {
        SystemTargetCalendarSection(state = state, onIntent = onIntent)
    }

    // ─── Status ────────────────────────────────────────────────────────
    if (state.isEnabled) {
        SystemStatusSections(state = state, onIntent = onIntent)
    }
}

/** The device calendar to write into, chosen from the calendars the platform reports. */
@Composable
private fun SystemTargetCalendarSection(state: CalendarSyncUiState, onIntent: (CalendarSyncIntent) -> Unit) {
    SettingsSection(title = "Target Calendar") {
        if (state.availableCalendars.isEmpty() && state.isLoading) {
            Text(
                text = "Loading calendars...",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else if (state.availableCalendars.isEmpty()) {
            Text(
                text = "No calendars available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            state.availableCalendars.forEach { (id, name) ->
                RadioRow(
                    label = name,
                    selected = state.selectedCalendarId == id,
                    onClick = { onIntent(SelectCalendar(id)) },
                )
            }
        }
    }
}

/** Status and explanatory copy, kept together because both describe the device calendar. */
@Composable
private fun SystemStatusSections(state: CalendarSyncUiState, onIntent: (CalendarSyncIntent) -> Unit) {
    SettingsSection(title = "Status") {
        Text(
            text = systemStatusText(state.status),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Button(
            onClick = { onIntent(SyncNow) },
            enabled = state.status !is CalendarSyncStatus.Syncing,
            modifier = Modifier
                .testTag(TestTags.CalendarSync.SYSTEM_SYNC_NOW_BUTTON)
                .padding(top = 8.dp),
        ) {
            Text("Sync Now")
        }
    }

    // ─── Info ─────────────────────────────────────────────────────────
    SettingsSection(title = "About") {
        Text(
            text = "Syncs task title, due date, due time, and recurrence to your system calendar " +
                "as all-day or timed events. Deep-links back to this app are embedded in the event description.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The device calendar's own status line.
 *
 * Extracted so the shared "Sync Now" wording has exactly one rendering per provider: this
 * text reports what the *system* half last did, and presenting it as Google's status would
 * be a claim about a different calendar.
 */
private fun systemStatusText(status: CalendarSyncStatus): String = when (status) {
    is CalendarSyncStatus.Disabled -> "Disabled"

    is CalendarSyncStatus.Idle -> {
        val date = status.lastSyncedAt?.let { ts ->
            val instant = Instant.fromEpochMilliseconds(ts)
            val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
            val month = local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
            val hour = local.hour.toString().padStart(2, '0')
            val minute = local.minute.toString().padStart(2, '0')
            "$month ${local.dayOfMonth}, ${local.year} $hour:$minute"
        } ?: "Never"
        "Last synced: $date"
    }

    is CalendarSyncStatus.Syncing -> "Syncing..."

    is CalendarSyncStatus.Failed -> "Failed: ${status.reason}"
}

/**
 * The Google half, which needs neither the device calendar nor its permission.
 *
 * Every branch here is a state the user can actually be in, and each one says what is
 * missing rather than rendering a control that does nothing: not connected, connected but
 * unable to renew, connected with no calendar chosen, connected and waiting on the list.
 */
@Composable
private fun GoogleCalendarPanel(state: CalendarSyncUiState, onIntent: (CalendarSyncIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GoogleAccountSection(state = state, onIntent = onIntent)

        if (state.googleConnected) {
            GoogleCalendarPickerSection(state = state, onIntent = onIntent)
            GoogleImportSection(state = state, onIntent = onIntent)
        }

        GoogleSyncSection(state = state, onIntent = onIntent)
    }
}

/**
 * Connect or disconnect, plus the one warning the connection state can carry.
 *
 * The two states are one section because they are one account: a user looking for how to
 * reconnect should not have to first work out that the screen considers them connected.
 */
@Composable
private fun GoogleAccountSection(state: CalendarSyncUiState, onIntent: (CalendarSyncIntent) -> Unit) {
    SettingsSection(title = "Google Account") {
        if (!state.googleConnected) {
            Text(
                text = "Connecting asks Google for permission to read and edit your calendar. " +
                    "The app uses it only to sync your tasks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(
                onClick = { onIntent(SetGoogleConnected(true)) },
                modifier = Modifier.testTag(TestTags.CalendarSync.GOOGLE_CONNECT_BUTTON),
            ) {
                Text("Connect Google account")
            }
            return@SettingsSection
        }

        // A grant with no refresh token is connected in every sense the settings screen
        // can see and still unable to make a call once the access token expires. Left
        // unsaid, sync would appear to work and then stop for no stated reason, which
        // reads as the app losing the connection rather than the grant never being able
        // to replace it.
        if (!state.googleCanRenew) {
            Text(
                text = "This connection cannot be renewed in the background, so sync will stop " +
                    "when the current permission expires. Reconnect then to keep syncing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .testTag(TestTags.CalendarSync.GOOGLE_RENEW_WARNING)
                    .padding(bottom = 8.dp),
            )
        }

        Button(
            onClick = { onIntent(SetGoogleConnected(false)) },
            modifier = Modifier.testTag(TestTags.CalendarSync.GOOGLE_DISCONNECT_BUTTON),
        ) {
            Text("Disconnect")
        }
    }
}

/** The Google calendar to sync into, or the reason there is nothing to choose from. */
@Composable
private fun GoogleCalendarPickerSection(state: CalendarSyncUiState, onIntent: (CalendarSyncIntent) -> Unit) {
    SettingsSection(title = "Google Calendar") {
        when {
            state.isLoading -> Text(
                text = "Loading calendars...",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            // Error first, and never merged into "no calendars": an empty list
            // after a failed read is the reason the field exists at all, and a
            // reauth or network problem has nothing to do with the account having
            // no calendars.
            state.googleError != null -> Text(
                text = "Could not read your Google calendars: ${state.googleError}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .testTag(TestTags.CalendarSync.GOOGLE_LIST_ERROR)
                    .padding(vertical = 8.dp),
            )

            state.googleCalendars.isEmpty() -> Text(
                text = "No calendars were found on this account",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            else -> state.googleCalendars.forEach { calendar ->
                RadioRow(
                    label = googleCalendarLabel(calendar),
                    selected = state.selectedGoogleCalendarId == calendar.id,
                    enabled = calendar.canWrite,
                    onClick = { onIntent(SelectGoogleCalendar(calendar.id)) },
                    // Keyed by the Google id rather than the row order: the listing is
                    // sorted by Google, so "the first row" is not a stable selector and
                    // would silently retarget whenever the account's order changed.
                    testTag = TestTags.CalendarSync.googleCalendarRow(calendar.id),
                )
            }
        }
    }
}

/** Whether foreign Google events come in, and how far the listing reaches. */
@Composable
private fun GoogleImportSection(state: CalendarSyncUiState, onIntent: (CalendarSyncIntent) -> Unit) {
    SettingsSection(title = "Import From Google") {
        SettingsSwitchRow(
            title = "Import events from Google",
            subtitle = "Events this app did not create are added as tasks you can edit, and " +
                "edits flow back. Turning this off leaves your own tasks still syncing to Google.",
            testTag = TestTags.CalendarSync.GOOGLE_IMPORT_SWITCH,
            checked = state.importFromGoogle,
            onCheckedChange = { onIntent(SetImportFromGoogle(it)) },
        )
        // Read from the state, which carries the window the engine is configured with.
        // It used to read `ImportWindow.DEFAULT` directly, alongside two default
        // arguments that named the same constant — three sites, and a fourth copy here
        // was free to drift without anything noticing. The screen would then describe a
        // window the pass does not use.
        Text(
            text = "Imports events from the past ${formatWindowBound(state.importWindow.past)} " +
                "to ${formatWindowBound(state.importWindow.future)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .testTag(TestTags.CalendarSync.GOOGLE_IMPORT_WINDOW)
                .padding(top = 4.dp),
        )
    }
}

/**
 * The manual sync control, or the reason there isn't one.
 *
 * Deliberately has no status line: the one above belongs to the device calendar, and
 * reporting it here would describe a calendar this panel is not talking to.
 */
@Composable
private fun GoogleSyncSection(state: CalendarSyncUiState, onIntent: (CalendarSyncIntent) -> Unit) {
    SettingsSection(title = "Google Sync") {
        // No Sync button until a calendar is chosen. A button that is present and
        // does nothing is the failure this screen was restructured to avoid.
        if (state.googleReady) {
            Button(
                onClick = { onIntent(SyncGoogleNow) },
                // Disabled while a pass runs, because the coordinator is re-entrant but a
                // user pressing twice means two passes raced for the same cursor.
                enabled = !state.googleSyncing,
                modifier = Modifier.testTag(TestTags.CalendarSync.GOOGLE_SYNC_NOW_BUTTON),
            ) {
                Text(if (state.googleSyncing) "Syncing..." else "Sync Now")
            }

            // The outcome of the last pass. A Google sync that stops working has to say
            // so: before this, a failed pass left the button exactly as it was, and the
            // only evidence was a calendar that had quietly stopped updating.
            // One tag on whichever line is showing, rather than two: the pair is one
            // piece of information ("what did the last pass do") in two mutually
            // exclusive renderings. A test asserting the tag exists is asserting that
            // the pass reported *something*, and reads the text to learn which.
            val error = state.googleSyncError
            if (error != null) {
                Text(
                    text = "Sync failed: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .testTag(TestTags.CalendarSync.GOOGLE_SYNC_OUTCOME)
                        .padding(top = 4.dp),
                )
            } else {
                state.googleLastSyncedAt?.let { at ->
                    Text(
                        text = "Last synced ${formatGoogleSyncTime(at)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .testTag(TestTags.CalendarSync.GOOGLE_SYNC_OUTCOME)
                            .padding(top = 4.dp),
                    )
                }
            }
        } else {
            Text(
                text = if (state.googleConnected) {
                    "Choose a calendar above to start syncing."
                } else {
                    "Connect a Google account and choose a calendar to start syncing."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(TestTags.CalendarSync.GOOGLE_SYNC_NEEDS_CALENDAR)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * Label for a Google calendar row.
 *
 * The primary marker is spelled out rather than implied by row order: the list is sorted
 * by Google, and "the first one" is not a reason to believe a row is the account's own.
 * A read-only calendar is marked inline because the row is visibly unselectable and an
 * unexplained disabled control invites the user to hunt for the missing permission.
 */
private fun googleCalendarLabel(calendar: GoogleCalendarSummary): String = buildString {
    append(calendar.summary)
    if (calendar.isPrimary) append(" (primary)")
    if (!calendar.canWrite) append(" — read-only, this account cannot create events there")
}

/**
 * Renders one end of the import window.
 *
 * Not the shared `formatDuration`, which takes milliseconds for elapsed *durations* and
 * would render 30 days as "720h". A calendar window is a distance in days, and "30 days" is
 * what a user can check their own calendar against.
 */
private fun formatWindowBound(duration: Duration): String {
    val days = duration.inWholeDays
    return if (days == 1L) "1 day" else "$days days"
}

@Composable
private fun PermissionGate(onRequestPermission: () -> Unit) {
    SettingsSection(title = "Permissions Required") {
        Text(
            text = "Calendar sync requires READ_CALENDAR and WRITE_CALENDAR permissions.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Grant Permission")
        }
    }
}

/**
 * Shown where the system calendar cannot be reached at all (desktop today).
 *
 * The alternative — hiding the tab — would leave a settings entry that appears and does
 * nothing, which is the same defect in a different place.
 */
@Composable
private fun UnavailableOnThisPlatform() {
    SettingsSection(title = "System Calendar Sync") {
        Text(
            text = "System calendar sync needs Android. " +
                "Your tasks are unaffected — they stay in the app.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(TestTags.CalendarSync.SYSTEM_UNAVAILABLE),
        )
    }
}

/**
 * A radio row that can refuse to be chosen.
 *
 * [enabled] exists for Google calendars the account may read but not write. The listing
 * port already promises writable calendars only, so this is defensive — but offering a
 * choice that fails at the first write is worse than saying no here, where it costs one
 * line of UI to prevent.
 */
@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            // Applied before `clickable` so the tag lands on the node that owns the
            // click, matching how SettingsRow tags itself: one semantic node for the
            // whole row rather than a tagged wrapper around an untagged clickable.
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = if (enabled) onClick else null, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
