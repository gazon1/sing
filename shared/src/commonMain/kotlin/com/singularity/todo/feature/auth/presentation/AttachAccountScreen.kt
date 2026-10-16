package com.singularity.todo.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.singularity.todo.feature.auth.rememberLoginFormState
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.settings.presentation.nav.LocalSettingsNavigator
import org.koin.compose.viewmodel.koinViewModel

/**
 * Screen for attaching an email/password account to the current anonymous session.
 *
 * Only reachable when the current session is [com.singularity.todo.core.auth.Session.Anonymous].
 * Submitting the form sends [AuthIntent.MigrateAnonymousTo] to [AuthViewModel].
 *
 * On success navigates to the agenda home; pressing back returns to the settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachAccountScreen(
    viewModel: AuthViewModel = koinViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
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
                isLoading = state is AuthUiState.Loading,
                errorMessage = errorMessage,
                message = message,
                onEmailChange = { newEmail -> form.update { copy(email = newEmail) } },
                onPasswordChange = { newPassword -> form.update { copy(password = newPassword) } },
                onSubmit = {
                    errorMessage = null
                    message = null
                    viewModel.onIntent(
                        AuthIntent.MigrateAnonymousTo(
                            email = form.value.email.trim(),
                            password = form.value.password,
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
        isLoading = false,
        errorMessage = null,
        onEmailChange = {},
        onPasswordChange = {},
        onSubmit = {},
        onBack = {},
    )
}

@Composable
internal fun AttachAccountScreenContent(
    email: String,
    password: String,
    isLoading: Boolean,
    errorMessage: String?,
    message: String? = null,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Attach Account") },
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
            AttachAccountForm(
                email = email,
                password = password,
                isLoading = isLoading,
                errorMessage = errorMessage,
                message = message,
                onEmailChange = onEmailChange,
                onPasswordChange = onPasswordChange,
                onSubmit = onSubmit,
            )
        }
    }
}

/**
 * The attach-account body: two lines of explanation above [AuthFormFields].
 *
 * Shared by [AttachAccountScreen] and [AttachAccountScreenContent]. Both rendered
 * the same copy of this block, so a wording change reached one screen until someone
 * opened the other.
 */
@Composable
private fun AttachAccountForm(
    email: String,
    password: String,
    isLoading: Boolean,
    errorMessage: String?,
    message: String?,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Create an account with your current anonymous session.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Your data will be preserved and linked to this account.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(32.dp))

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
    )
}
