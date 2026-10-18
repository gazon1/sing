package com.singularity.todo.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.auth.AuthFormFields
import com.singularity.todo.feature.auth.AuthIntent
import com.singularity.todo.feature.auth.AuthUiEvent
import com.singularity.todo.feature.auth.AuthUiState
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.auth.EraseCounts
import com.singularity.todo.feature.auth.LocalDataDisposition
import com.singularity.todo.feature.auth.LocalDataSummary
import com.singularity.todo.feature.auth.OwnerOperationCounts
import com.singularity.todo.feature.auth.ReassignCounts
import com.singularity.todo.feature.auth.rememberLoginFormState
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.settings.presentation.nav.LocalSettingsNavigator
import org.koin.compose.viewmodel.koinViewModel

/**
 * Screen for attaching an email/password account to the current local session.
 *
 * Only reachable when the current session is [com.singularity.todo.core.auth.Session.Anonymous].
 * Submitting the form sends [AuthIntent.AttachAccount] to [AuthViewModel], carrying the
 * disposition the user picked above the form.
 *
 * On success navigates to the agenda home; pressing back returns to the settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachAccountScreen(
    viewModel: AuthViewModel = koinViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val localData by viewModel.localData.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val settingsNavigator = LocalSettingsNavigator.current
    val form = rememberLoginFormState()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    CollectEvents(viewModel.events) { event ->
        when (event) {
            AuthUiEvent.NavigateToHome -> {
                // After attaching, go to agenda home.
                // The attach screen was entered via onExitGraph, so navigating
                // forward to AgendaGraph places it on top of the settings stack.
                // Pressing system Back from agenda returns to settings as expected.
                navigator.navigate(AppDestination.AgendaGraph(AgendaStartRoute.Today))
            }

            is AuthUiEvent.Error -> {
                errorMessage = event.message
                message = null
            }

            is AuthUiEvent.Message -> {
                message = event.message
                errorMessage = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Attach Account") },
                navigationIcon = {
                    IconButton(
                        onClick = { settingsNavigator.back() },
                        modifier = Modifier.testTag(TestTags.AUTH_ATTACH_BACK_BUTTON),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            AttachAccountForm(
                email = form.value.email,
                password = form.value.password,
                disposition = form.value.disposition,
                isLoading = state is AuthUiState.Loading,
                errorMessage = errorMessage,
                message = message,
                onEmailChange = { newEmail -> form.update { copy(email = newEmail) } },
                onPasswordChange = { newPassword -> form.update { copy(password = newPassword) } },
                onDispositionChange = { choice -> form.update { copy(disposition = choice) } },
                onSubmit = {
                    // Unreachable while the choice is null — the button is disabled —
                    // and the fallback is a refusal rather than a silent default, so a
                    // future caller that forgets the guard cannot dispose of anyone's data.
                    val choice = form.value.disposition
                    if (choice == null) {
                        errorMessage = "Choose what should happen to your offline data first."
                        return@AttachAccountForm
                    }
                    errorMessage = null
                    message = null
                    viewModel.onIntent(
                        AuthIntent.AttachAccount(
                            email = form.value.email.trim(),
                            password = form.value.password,
                            disposition = choice,
                        ),
                    )
                },
            )
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AttachAccountScreenPreview() = PreviewThemed(darkTheme = false) {
    AttachAccountScreenContent(
        email = "user@example.com",
        password = "password",
        disposition = LocalDataDisposition.Keep(ReassignCounts(tasks = 12, notes = 3), LocalDataSummary(12, 3)),
        isLoading = false,
        errorMessage = null,
        onEmailChange = {},
        onPasswordChange = {},
        onDispositionChange = {},
        onSubmit = {},
        onBack = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AttachAccountScreenUnansweredPreview() = PreviewThemed(darkTheme = false) {
    AttachAccountScreenContent(
        email = "user@example.com",
        password = "password",
        disposition = null,
        isLoading = false,
        errorMessage = null,
        onEmailChange = {},
        onPasswordChange = {},
        onDispositionChange = {},
        onSubmit = {},
        onBack = {},
    )
}

@Composable
internal fun AttachAccountScreenContent(
    email: String,
    password: String,
    disposition: LocalDataDisposition?,
    isLoading: Boolean,
    errorMessage: String?,
    message: String? = null,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDispositionChange: (LocalDataDisposition) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Attach Account") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(TestTags.AUTH_ATTACH_BACK_BUTTON),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            AttachAccountForm(
                email = email,
                password = password,
                disposition = disposition,
                isLoading = isLoading,
                errorMessage = errorMessage,
                message = message,
                onEmailChange = onEmailChange,
                onPasswordChange = onPasswordChange,
                onDispositionChange = onDispositionChange,
                onSubmit = onSubmit,
            )
        }
    }
}

/**
 * The attach-account body: the explanation, the choice about local data, then the form.
 *
 * Shared by [AttachAccountScreen] and [AttachAccountScreenContent]. Both rendered the
 * same copy of this block, so a wording change reached one screen until someone opened
 * the other.
 *
 * ## Why the choice comes before the fields
 *
 * So the decision is made while the user is still reading what the decision is about.
 * Below the password field it reads as a checkbox on a form rather than as the answer to
 * "what happens to the twelve tasks I made without an account", which is the only reason
 * anyone would make it carefully.
 */
@Composable
private fun AttachAccountForm(
    email: String,
    password: String,
    disposition: LocalDataDisposition?,
    isLoading: Boolean,
    errorMessage: String?,
    message: String?,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDispositionChange: (LocalDataDisposition) -> Unit,
    onSubmit: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Create an account to keep your data across devices.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(24.dp))

    LocalDataChoice(
        selected = disposition,
        onSelect = onDispositionChange,
    )

    Spacer(modifier = Modifier.height(24.dp))

    AuthFormFields(
        emailTag = TestTags.AUTH_ATTACH_EMAIL_INPUT,
        passwordTag = TestTags.AUTH_ATTACH_PASSWORD_INPUT,
        submitTag = TestTags.AUTH_ATTACH_SUBMIT_BUTTON,
        submitLabel = "Attach Account",
        email = email,
        password = password,
        isLoading = isLoading,
        errorMessage = errorMessage,
        message = message,
        onEmailChange = onEmailChange,
        onPasswordChange = onPasswordChange,
        onSubmit = onSubmit,
        submitEnabled = disposition != null,
    )
}

/**
 * The two things that can happen to offline data, as an unselected-by-default choice.
 *
 * Unselected on purpose. The alternative — a preselected "Keep" — is the app deciding
 * for the user, and REQ-UA-005 is explicit that neither outcome is a default. The
 * submit button stays disabled until one is picked, so the question cannot be skipped —
 * it can only be answered.
 *
 * When [LocalDataDisposition.Discard] is selected, a confirmation panel appears showing
 * the exact rows that will be removed (from [LocalDataDisposition.Discard.eraseCounts]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
private fun LocalDataChoice(
    selected: LocalDataDisposition?,
    onSelect: (LocalDataDisposition) -> Unit,
) {
    // Remember the previous Keep data so Cancel in DiscardConfirmation can restore it.
    var previousKeep by remember { mutableStateOf<LocalDataDisposition.Keep?>(null) }

    val previewSummary = (selected as? LocalDataDisposition.Keep)?.summary
        ?: LocalDataSummary(0, 0)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "You have ${previewSummary.describe} on this device.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(12.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selected is LocalDataDisposition.Keep,
                onClick = {
                    // Re-selecting Keep replaces any Discard selection with the current
                    // Keep counts (the full counts from summarise() are in selected).
                    val counts = (selected as? LocalDataDisposition.Keep)?.transferCounts
                        ?: ReassignCounts()
                    val summary = (selected as? LocalDataDisposition.Keep)?.summary
                        ?: LocalDataSummary(0, 0)
                    onSelect(LocalDataDisposition.Keep(counts, summary))
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.testTag(TestTags.AUTH_ATTACH_KEEP_BUTTON),
            ) {
                Text("Move to account")
            }
            SegmentedButton(
                selected = selected is LocalDataDisposition.Discard,
                onClick = {
                    // Switching to Discard: remember the current Keep so Cancel can restore it.
                    if (selected is LocalDataDisposition.Keep) {
                        previousKeep = selected
                    }
                    // Embed the EraseCounts from summarise() so the confirmation panel
                    // can show exactly what will be deleted.
                    val counts = (selected as? LocalDataDisposition.Discard)?.eraseCounts
                        ?: EraseCounts()
                    onSelect(LocalDataDisposition.Discard(counts))
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.testTag(TestTags.AUTH_ATTACH_DISCARD_BUTTON),
            ) {
                Text("Delete")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (selected) {
            is LocalDataDisposition.Keep -> {
                val counts = selected.transferCounts
                val total = OwnerOperationCounts.sum(counts)
                Text(
                    text = if (total > 0) {
                        "They move to this account and sync from now on. ($total rows total)"
                    } else {
                        "They move to this account and sync from now on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.AUTH_ATTACH_DISPOSITION_HINT),
                )
            }

            is LocalDataDisposition.Discard -> {
                DiscardConfirmation(
                    eraseCounts = selected.eraseCounts,
                    onCancel = {
                        val restored = previousKeep ?: LocalDataDisposition.Keep(
                            ReassignCounts(),
                            LocalDataSummary(0, 0),
                        )
                        onSelect(restored)
                    },
                )
            }

            null -> {
                Text(
                    text = "Choose what should happen to them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.AUTH_ATTACH_DISPOSITION_HINT),
                )
            }
        }
    }
}

/**
 * Shows the exact rows that will be erased when the user confirms Discard.
 *
 * Appears inline after the user clicks Delete, so they can verify exactly what
 * will be removed before committing to the erase.
 */
@Composable
private fun DiscardConfirmation(
    eraseCounts: EraseCounts,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "They are removed from this device and the account starts empty.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Summary line: primary counts a person can reason about
        val taskNote = buildString {
            if (eraseCounts.tasks > 0) append("${eraseCounts.tasks} tasks")
            if (eraseCounts.tasks > 0 && eraseCounts.notes > 0) append(", ")
            if (eraseCounts.notes > 0) append("${eraseCounts.notes} notes")
        }
        if (taskNote.isNotEmpty()) {
            Text(
                text = taskNote,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        // Expanded breakdown for the curious
        val childRows = eraseCounts.childRows
        val syncRows = eraseCounts.syncTotal
        val detailParts = buildList {
            if (childRows > 0) add("$childRows child rows")
            if (eraseCounts.projects > 0) add("${eraseCounts.projects} projects")
            if (eraseCounts.tags > 0) add("${eraseCounts.tags} tags")
            if (syncRows > 0) add("$syncRows sync rows")
        }
        if (detailParts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detailParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Delete forever")
            }
        }
    }
}
