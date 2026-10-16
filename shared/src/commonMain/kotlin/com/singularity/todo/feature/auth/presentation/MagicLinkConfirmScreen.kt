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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ButtonSpinner
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.auth.AuthIntent
import com.singularity.todo.feature.auth.AuthUiEvent
import com.singularity.todo.feature.auth.AuthUiState
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.auth.ConfirmationPendingContent
import com.singularity.todo.feature.auth.MagicLinkFormState
import com.singularity.todo.feature.auth.rememberMagicLinkFormState
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
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
    val form = rememberMagicLinkFormState()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    MagicLinkConfirmEffects(
        prefillEmail = prefillEmail,
        prefillToken = prefillToken,
        form = form,
        viewModel = viewModel,
        onNavigateHome = { navigator.navigate(AppDestination.AgendaGraph(AgendaStartRoute.Today)) },
        onError = { errorMessage = it },
        onMessage = {
            message = it
            errorMessage = null
        },
    )

    MagicLinkConfirmScaffold(onBack = { navigator.goBack() }) {
        val awaitingConfirmation = state as? AuthUiState.AwaitingEmailConfirmation
        if (awaitingConfirmation != null) {
            MagicLinkPending(
                email = awaitingConfirmation.email,
                message = message,
                isLoading = state is AuthUiState.Loading,
                onResend = {
                    message = null
                    viewModel.onIntent(AuthIntent.SendMagicLink(awaitingConfirmation.email.trim()))
                },
                onBack = {
                    message = null
                    errorMessage = null
                    viewModel.onIntent(AuthIntent.ResetState)
                },
            )
        } else {
            MagicLinkVerifyForm(
                email = form.value.email,
                token = form.value.token,
                emailEnabled = prefillEmail.isEmpty(),
                isLoading = state is AuthUiState.Loading,
                errorMessage = errorMessage,
                message = message,
                onEmailChange = { newEmail ->
                    form.update { copy(email = newEmail) }
                    errorMessage = null
                    message = null
                },
                onTokenChange = { newToken ->
                    form.update { copy(token = newToken) }
                    errorMessage = null
                    message = null
                },
                onSubmit = {
                    errorMessage = null
                    message = null
                    viewModel.onIntent(
                        AuthIntent.VerifyMagicLink(
                            email = form.value.email.trim(),
                            token = form.value.token.trim(),
                        ),
                    )
                },
                onResend = {
                    if (form.value.email.isNotBlank()) {
                        errorMessage = null
                        message = null
                        viewModel.onIntent(AuthIntent.SendMagicLink(form.value.email.trim()))
                    }
                },
            )
        }
    }
}

/**
 * Everything this screen does that is not drawing: the two deep-link effects and
 * the one-shot event collector.
 *
 * Split out of [MagicLinkConfirmScreen] so the screen body is the layout and the
 * reactions are a single named call. The callbacks write the screen's own state,
 * so this composable owns no state of its own and cannot disagree with the body.
 */
@Composable
private fun MagicLinkConfirmEffects(
    prefillEmail: String,
    prefillToken: String,
    form: MagicLinkFormState,
    viewModel: AuthViewModel,
    onNavigateHome: () -> Unit,
    onError: (String?) -> Unit,
    onMessage: (String?) -> Unit,
) {
    // Sync deep-link email when it arrives (e.g. app was killed and reopened via link).
    LaunchedEffect(prefillEmail) {
        if (prefillEmail.isNotBlank()) {
            form.update { copy(email = prefillEmail) }
        }
    }

    // Auto-submit when the deep-link token is received.
    LaunchedEffect(prefillToken) {
        if (prefillToken.isNotBlank() && form.value.email.isNotBlank()) {
            viewModel.onIntent(
                AuthIntent.VerifyMagicLink(
                    email = form.value.email.trim(),
                    token = prefillToken.trim(),
                ),
            )
        }
    }

    CollectEvents(viewModel.events) { event ->
        when (event) {
            is AuthUiEvent.NavigateToHome -> onNavigateHome()
            is AuthUiEvent.Error -> onError(event.message)
            is AuthUiEvent.Message -> onMessage(event.message)
        }
    }
}

/** The "check your inbox" state, for the screen that has a form to show. */
@Composable
private fun MagicLinkPending(
    email: String,
    message: String?,
    isLoading: Boolean,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    ConfirmationPendingContent(
        email = email,
        message = message,
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
            modifier = Modifier
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
    email: String,
    token: String,
    isLoading: Boolean,
    errorMessage: String?,
    message: String?,
    onEmailChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    emailEnabled: Boolean = true,
) {
    Text(
        text = "A sign-in link was sent to your email. Enter the code from the link below.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(32.dp))

    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("Email") },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.AUTH_ATTACH_EMAIL_INPUT),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        singleLine = true,
        enabled = emailEnabled,
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = token,
        onValueChange = onTokenChange,
        label = { Text("Code from email") },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TOKEN_TAG),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        singleLine = true,
    )

    if (errorMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = errorMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(TestTags.AUTH_ERROR_TEXT),
        )
    }

    if (message != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(TestTags.AUTH_MESSAGE_TEXT),
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    if (isLoading) {
        ButtonSpinner(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_LOADING),
        )
    } else {
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SUBMIT_TAG),
            enabled = email.isNotBlank() && token.isNotBlank(),
        ) {
            Text("Verify")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onResend,
            modifier = Modifier.testTag(TestTags.AUTH_MAGIC_RESEND_BUTTON),
            enabled = email.isNotBlank(),
        ) {
            Text("Resend link")
        }
    }
}

// ===== Preview =====

@Composable
private fun MagicLinkConfirmScreenPreview() = PreviewThemed(darkTheme = false) {
    MagicLinkConfirmScreenContent(
        email = "user@example.com",
        token = "",
        isLoading = false,
        errorMessage = null,
        message = null,
        onEmailChange = {},
        onTokenChange = {},
        onSubmit = {},
        onResend = {},
        onBack = {},
    )
}

@Composable
internal fun MagicLinkConfirmScreenContent(
    email: String,
    token: String,
    isLoading: Boolean,
    errorMessage: String?,
    message: String? = null,
    onEmailChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MagicLinkConfirmScaffold(onBack = onBack, modifier = modifier) {
        Spacer(modifier = Modifier.height(16.dp))

        MagicLinkVerifyForm(
            email = email,
            token = token,
            isLoading = isLoading,
            errorMessage = errorMessage,
            message = message,
            onEmailChange = onEmailChange,
            onTokenChange = onTokenChange,
            onSubmit = onSubmit,
            onResend = onResend,
        )
    }
}
