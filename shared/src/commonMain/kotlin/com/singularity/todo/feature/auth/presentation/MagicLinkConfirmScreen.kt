package com.singularity.todo.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ButtonSpinner
import com.singularity.todo.core.ui.components.TopAppBar
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.auth.AuthIntent
import com.singularity.todo.feature.auth.AuthScreenEffects
import com.singularity.todo.feature.auth.AuthUiEvent
import com.singularity.todo.feature.auth.AuthUiState
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.auth.ConfirmationPendingContent
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import kotlinx.coroutines.flow.Flow
import org.koin.compose.viewmodel.koinViewModel

private const val TOKEN_TAG = "magic_link_token_input"
private const val SUBMIT_TAG = "magic_link_verify_button"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagicLinkConfirmScreen(
    prefillEmail: String = "",
    prefillToken: String = "",
    viewModel: AuthViewModel = koinViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    // The email and the token live in the fields; the deep link writes into them.
    val emailState = rememberTextFieldState()
    val tokenState = rememberTextFieldState()

    MagicLinkConfirmScreenEffects(
        events = viewModel.events,
        onNavigateHome = { navigator.navigate(AppDestination.AgendaGraph(AgendaStartRoute.Today)) },
    )

    MagicLinkConfirmEffects(
        prefillEmail = prefillEmail,
        prefillToken = prefillToken,
        emailState = emailState,
        viewModel = viewModel,
    )

    MagicLinkConfirmScaffold(onBack = { navigator.goBack() }) {
        val awaitingConfirmation = state as? AuthUiState.AwaitingEmailConfirmation
        if (awaitingConfirmation != null) {
            MagicLinkPending(
                email = awaitingConfirmation.email,
                isLoading = state is AuthUiState.Loading,
                onResend = {
                    viewModel.onIntent(AuthIntent.SendMagicLink(awaitingConfirmation.email.trim()))
                },
                onBack = {
                    viewModel.onIntent(AuthIntent.ResetState)
                },
            )
        } else {
            MagicLinkVerifyForm(
                emailState = emailState,
                tokenState = tokenState,
                emailEnabled = prefillEmail.isEmpty(),
                isLoading = state is AuthUiState.Loading,
                onSubmit = {
                    viewModel.onIntent(
                        AuthIntent.VerifyMagicLink(
                            email = emailState.text.toString().trim(),
                            token = tokenState.text.toString().trim(),
                        ),
                    )
                },
                onResend = {
                    val email = emailState.text.toString()
                    if (email.isNotBlank()) {
                        viewModel.onIntent(AuthIntent.SendMagicLink(email.trim()))
                    }
                },
            )
        }
    }
}

/**
 * Everything this screen does that is not drawing: the two deep-link effects.
 *
 * Split out of [MagicLinkConfirmScreen] so the screen body is the layout and the
 * reactions are a single named call. This composable owns no state of its own and
 * cannot disagree with the body.
 */
@Composable
private fun MagicLinkConfirmEffects(
    prefillEmail: String,
    prefillToken: String,
    emailState: TextFieldState,
    viewModel: AuthViewModel,
) {
    // Sync deep-link email when it arrives (e.g. app was killed and reopened via link).
    LaunchedEffect(prefillEmail) {
        if (prefillEmail.isNotBlank()) {
            emailState.setTextAndPlaceCursorAtEnd(prefillEmail)
        }
    }

    // Auto-submit when the deep-link token is received.
    LaunchedEffect(prefillToken) {
        val email = emailState.text.toString()
        if (prefillToken.isNotBlank() && email.isNotBlank()) {
            viewModel.onIntent(
                AuthIntent.VerifyMagicLink(
                    email = email.trim(),
                    token = prefillToken.trim(),
                ),
            )
        }
    }
}

/**
 * The magic-link screen's whole reaction to the auth event bus.
 *
 * The screen keeps no copy of an event: it presents it where it arrives — an
 * error as the shared error dialog, a neutral notice as a snackbar — and
 * forgets it. The implementation is [AuthScreenEffects], shared with the other
 * auth screens; this name is the magic-link screen's own contract, so the desktop
 * test can mount the screen's reaction on its own and hand it an event.
 */
@Composable
fun MagicLinkConfirmScreenEffects(
    events: Flow<AuthUiEvent>,
    onNavigateHome: () -> Unit,
) {
    AuthScreenEffects(events = events, onNavigateHome = onNavigateHome)
}

/** The "check your inbox" state, for the screen that has a form to show. */
@Composable
private fun MagicLinkPending(
    email: String,
    isLoading: Boolean,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    ConfirmationPendingContent(
        email = email,
        isLoading = isLoading,
        onResend = onResend,
        onBack = onBack,
    )
}

// ─── Shared pieces ──────────────────────────────────────────────────────────
//
// One implementation each, used by both the stateful screen above and the
// stateless [MagicLinkConfirmScreenContent] below. They were two copies of the
// same form, and a change to one of them (a test tag, a label) was a silent
// divergence in the other until something compared the two by eye.

/**
 * The screen's frame: top bar, padding, and the centred column every state draws
 * into. One definition so the stateful screen and [MagicLinkConfirmScreenContent]
 * cannot drift on padding or on the back button's test tag.
 */
@Composable
private fun MagicLinkConfirmScaffold(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Email") },
                navigationIcon = { MagicLinkBackButton(onClick = onBack) },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            content = content,
        )
    }
}

@Composable
private fun MagicLinkBackButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.testTag(TestTags.AUTH_ATTACH_BACK_BUTTON),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
        )
    }
}

/**
 * The email/code form, from the intro text down to the Verify / Resend buttons.
 *
 * Stateful and stateless screens differ only in where the values come from, so
 * this takes them as parameters and both callers keep their own state.
 */
@Composable
private fun MagicLinkVerifyForm(
    emailState: TextFieldState,
    tokenState: TextFieldState,
    isLoading: Boolean,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    emailEnabled: Boolean = true,
) {
    SelectableText(
        text = "A sign-in link was sent to your email. Enter the code from the link below.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(32.dp))

    OutlinedTextField(
        state = emailState,
        label = { Text("Email") },
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_ATTACH_EMAIL_INPUT),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        lineLimits = TextFieldLineLimits.SingleLine,
        enabled = emailEnabled,
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        state = tokenState,
        label = { Text("Code from email") },
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TOKEN_TAG),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        lineLimits = TextFieldLineLimits.SingleLine,
    )

    Spacer(modifier = Modifier.height(24.dp))

    if (isLoading) {
        ButtonSpinner(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_LOADING),
        )
    } else {
        Button(
            onClick = onSubmit,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(SUBMIT_TAG),
            enabled = emailState.text.isNotBlank() && tokenState.text.isNotBlank(),
        ) {
            Text("Verify")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onResend,
            modifier = Modifier.testTag(TestTags.AUTH_MAGIC_RESEND_BUTTON),
            enabled = emailState.text.isNotBlank(),
        ) {
            Text("Resend link")
        }
    }
}

// ===== Preview =====

@Composable
private fun MagicLinkConfirmScreenPreview() =
    PreviewThemed(darkTheme = false) {
        MagicLinkConfirmScreenContent(
            emailState = rememberTextFieldState("user@example.com"),
            tokenState = rememberTextFieldState(),
            isLoading = false,
            onSubmit = {},
            onResend = {},
            onBack = {},
        )
    }

@Composable
internal fun MagicLinkConfirmScreenContent(
    emailState: TextFieldState,
    tokenState: TextFieldState,
    isLoading: Boolean,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MagicLinkConfirmScaffold(onBack = onBack, modifier = modifier) {
        Spacer(modifier = Modifier.height(16.dp))

        MagicLinkVerifyForm(
            emailState = emailState,
            tokenState = tokenState,
            isLoading = isLoading,
            onSubmit = onSubmit,
            onResend = onResend,
        )
    }
}
