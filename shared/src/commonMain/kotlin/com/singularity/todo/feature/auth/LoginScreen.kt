package com.singularity.todo.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ButtonSpinner
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(viewModel: AuthViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = rememberLoginFormState()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Kept apart from `errorMessage` so a neutral "your account was created, go check
    // your mail" is not painted in the error colour and is not announced as a failure.
    var message by remember { mutableStateOf<String?>(null) }

    val navigator = LocalAppNavigator.current

    // With no project configured there is nothing to sign in to, and the
    // credential form's failure would arrive as a network error pointing at the
    // password. The configuration comes first so the user is never asked to retype
    // a correct password because the server was never named.
    if (state is AuthUiState.SignedOutWithNoServer) {
        ServerConfigForm(
            errorMessage = errorMessage,
            onSave = { url, key -> viewModel.onIntent(AuthIntent.SaveServerConfig(url, key)) },
        )
        return
    }

    CollectEvents(viewModel.events) { event ->
        when (event) {
            is AuthUiEvent.NavigateToHome -> {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Singularity Todo",
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = form.value.email,
            onValueChange = { newEmail -> form.update { copy(email = newEmail) } },
            label = { Text("Email") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_EMAIL_INPUT),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = form.value.password,
            onValueChange = { newPassword -> form.update { copy(password = newPassword) } },
            label = { Text("Password") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_PASSWORD_INPUT),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )

        val capturedError = errorMessage
        if (capturedError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = capturedError,
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

        if (state is AuthUiState.Loading) {
            ButtonSpinner(modifier = Modifier.testTag(TestTags.AUTH_LOADING))
        } else {
            Button(
                onClick = {
                    if (form.value.isSignUp) {
                        viewModel.onIntent(AuthIntent.SignUp(form.value.email, form.value.password))
                    } else {
                        viewModel.onIntent(AuthIntent.SignIn(form.value.email, form.value.password))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_SIGN_IN_BUTTON),
            ) {
                Text(if (form.value.isSignUp) "Sign Up" else "Sign In")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = {
                    form.update { copy(isSignUp = !isSignUp) }
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_TOGGLE_MODE_BUTTON),
            ) {
                Text(
                    if (form.value.isSignUp) {
                        "Already have an account? Sign In"
                    } else {
                        "Don't have an account? Sign Up"
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = { viewModel.onIntent(AuthIntent.SignInAnonymously) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_CONTINUE_OFFLINE_BUTTON),
            ) {
                Text("Continue Offline")
            }
        }
    }
}

/**
 * The project URL and anon key, entered once.
 *
 * The anon key is a **publishable** identifier — it ships inside every copy of the
 * app and is meant to be readable. The label says so, because a field named "key"
 * next to a password field invites the user to paste the service-role key, and
 * that key does not belong on a device.
 */
@Composable
private fun ServerConfigForm(errorMessage: String?, onSave: (String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var anonKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Connect your server", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Paste the project URL and the anon key from your Supabase project settings. " +
                "The anon key is publishable, not a secret.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Project URL") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_SERVER_URL_INPUT),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = anonKey,
            onValueChange = { anonKey = it },
            label = { Text("Anon key (publishable)") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_SERVER_KEY_INPUT),
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

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onSave(url, anonKey) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_SAVE_SERVER_BUTTON),
        ) {
            Text("Save")
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoginScreenFormPreview() = PreviewThemed(darkTheme = false) {
    LoginScreenFormContent(
        email = "user@example.com",
        password = "password",
        isLoading = false,
        errorMessage = null,
        isSignUp = false,
        onEmailChange = {},
        onPasswordChange = {},
        onSignIn = {},
        onToggleMode = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoginScreenLoadingPreview() = PreviewThemed(darkTheme = false) {
    LoginScreenFormContent(
        email = "user@example.com",
        password = "password",
        isLoading = true,
        errorMessage = null,
        isSignUp = false,
        onEmailChange = {},
        onPasswordChange = {},
        onSignIn = {},
        onToggleMode = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoginScreenDarkPreview() = PreviewThemed(darkTheme = true) {
    LoginScreenFormContent(
        email = "",
        password = "",
        isLoading = false,
        errorMessage = "Invalid email or password",
        isSignUp = true,
        onEmailChange = {},
        onPasswordChange = {},
        onSignIn = {},
        onToggleMode = {},
    )
}

/**
 * Stateless preview variant of LoginScreen — mirrors the layout without
 * requiring a ViewModel or CollectEvents.
 */
@Composable
private fun LoginScreenFormContent(
    email: String,
    password: String,
    isLoading: Boolean,
    errorMessage: String?,
    isSignUp: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onToggleMode: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Singularity Todo", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        if (isLoading) {
            ButtonSpinner(modifier = Modifier)
        } else {
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (isSignUp) "Sign Up" else "Sign In") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onToggleMode, modifier = Modifier.fillMaxWidth()) {
                Text(if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Sign Up")
            }
        }
    }
}
