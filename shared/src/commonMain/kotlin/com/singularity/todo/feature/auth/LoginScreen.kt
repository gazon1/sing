package com.singularity.todo.feature.auth

import com.singularity.todo.core.ui.preview.PreviewThemed
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
import com.singularity.todo.core.ui.components.ButtonSpinner
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.CollectEvents
import org.koin.compose.koinInject

@Composable
fun LoginScreen(
    onSuccess: () -> Unit,
    onContinueOffline: () -> Unit,
    viewModel: AuthViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }

    CollectEvents(viewModel.events) { event ->
        when (event) {
            is AuthUiEvent.NavigateToHome -> onSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Singularity Todo",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_EMAIL_INPUT),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_PASSWORD_INPUT),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true
        )

        if (state is AuthUiState.Error) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = (state as AuthUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(TestTags.AUTH_ERROR_TEXT)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (state is AuthUiState.Loading) {
            ButtonSpinner(modifier = Modifier.testTag(TestTags.AUTH_LOADING))
        } else {
            Button(
                onClick = {
                    if (isSignUp) viewModel.signUp(email, password)
                    else viewModel.signIn(email, password)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_SIGN_IN_BUTTON)
            ) {
                Text(if (isSignUp) "Sign Up" else "Sign In")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = { isSignUp = !isSignUp },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_TOGGLE_MODE_BUTTON)
            ) {
                Text(
                    if (isSignUp) "Already have an account? Sign In"
                    else "Don't have an account? Sign Up"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = { viewModel.signInAnonymously() },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_CONTINUE_OFFLINE_BUTTON)
            ) {
                Text("Continue Offline")
            }
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoginScreenFormPreview() = PreviewThemed(darkTheme = false) {
    // Preview the static form layout — email/password state is local to the composable
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
        onContinueOffline = {},
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
        onContinueOffline = {},
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
        onContinueOffline = {},
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
    onContinueOffline: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Singularity Todo", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(32.dp))
        androidx.compose.material3.OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        androidx.compose.material3.OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        if (isLoading) {
            ButtonSpinner(modifier = Modifier)
        } else {
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (isSignUp) "Sign Up" else "Sign In") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onToggleMode, modifier = Modifier.fillMaxWidth()) {
                Text(if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Sign Up")
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onContinueOffline, modifier = Modifier.fillMaxWidth()) {
                Text("Continue Offline")
            }
        }
    }
}
