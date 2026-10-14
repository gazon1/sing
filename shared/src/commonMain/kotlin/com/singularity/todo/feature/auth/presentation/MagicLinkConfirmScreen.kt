package com.singularity.todo.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.singularity.todo.feature.auth.ConfirmationPendingContent
import com.singularity.todo.feature.auth.rememberMagicLinkFormState
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
import com.singularity.todo.feature.auth.AuthViewModel
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
                com.singularity.todo.feature.auth.AuthIntent.VerifyMagicLink(
                    email = form.value.email.trim(),
                    token = prefillToken.trim(),
                ),
            )
        }
    }

    CollectEvents(viewModel.events) { event ->
        when (event) {
            is com.singularity.todo.feature.auth.AuthUiEvent.NavigateToHome -> {
                navigator.navigate(
                    com.singularity.todo.feature.nav.AppDestination.AgendaGraph(
                        com.singularity.todo.feature.nav.AgendaStartRoute.Today,
                    ),
                )
            }

            is com.singularity.todo.feature.auth.AuthUiEvent.Error -> {
                errorMessage = event.message
            }

            is com.singularity.todo.feature.auth.AuthUiEvent.Message -> {
                message = event.message
                errorMessage = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Email") },
                navigationIcon = {
                    IconButton(
                        onClick = { navigator.goBack() },
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
            Spacer(modifier = Modifier.height(16.dp))

            val awaitingConfirmation = state as? com.singularity.todo.feature.auth.AuthUiState.AwaitingEmailConfirmation
            if (awaitingConfirmation != null) {
                ConfirmationPendingContent(
                    email = awaitingConfirmation.email,
                    message = message,
                    isLoading = state is com.singularity.todo.feature.auth.AuthUiState.Loading,
                    onResend = {
                        message = null
                        viewModel.onIntent(
                            com.singularity.todo.feature.auth.AuthIntent.SendMagicLink(
                                awaitingConfirmation.email.trim(),
                            ),
                        )
                    },
                    onBack = {
                        message = null
                        errorMessage = null
                        viewModel.onIntent(com.singularity.todo.feature.auth.AuthIntent.ResetState)
                    },
                )
            } else {
                Text(
                    text = "A sign-in link was sent to your email. Enter the code from the link below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(32.dp))

                OutlinedTextField(
                    value = form.value.email,
                    onValueChange = { newEmail ->
                        form.update { copy(email = newEmail) }
                        errorMessage = null
                        message = null
                    },
                    label = { Text("Email") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.AUTH_ATTACH_EMAIL_INPUT),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    enabled = prefillEmail.isEmpty(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = form.value.token,
                    onValueChange = { newToken ->
                        form.update { copy(token = newToken) }
                        errorMessage = null
                        message = null
                    },
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
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(TestTags.AUTH_ERROR_TEXT),
                    )
                }

                val capturedMessage = message
                if (capturedMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = capturedMessage,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(TestTags.AUTH_MESSAGE_TEXT),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (state is com.singularity.todo.feature.auth.AuthUiState.Loading) {
                    ButtonSpinner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TestTags.AUTH_LOADING),
                    )
                } else {
                    Button(
                        onClick = {
                            errorMessage = null
                            message = null
                            viewModel.onIntent(
                                com.singularity.todo.feature.auth.AuthIntent.VerifyMagicLink(
                                    email = form.value.email.trim(),
                                    token = form.value.token.trim(),
                                ),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SUBMIT_TAG),
                        enabled = form.value.email.isNotBlank() && form.value.token.isNotBlank(),
                    ) {
                        Text("Verify")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            if (form.value.email.isNotBlank()) {
                                errorMessage = null
                                message = null
                                viewModel.onIntent(
                                    com.singularity.todo.feature.auth.AuthIntent.SendMagicLink(
                                        email = form.value.email.trim(),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.testTag(TestTags.AUTH_MAGIC_RESEND_BUTTON),
                        enabled = form.value.email.isNotBlank(),
                    ) {
                        Text("Resend link")
                    }
                }
            }
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
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Verify Email") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(
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
            Spacer(modifier = Modifier.height(16.dp))

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
                    text = errorMessage!!,
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
    }
}
