package com.singularity.todo.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import kotlinx.coroutines.flow.Flow
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(viewModel: AuthViewModel = koinViewModel()) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val form = rememberLoginFormState()
    // The credentials live in the fields, not in the form state: the form keeps only the
    // choices that are not text.
    val emailState = rememberTextFieldState()
    val passwordState = rememberTextFieldState()

    val navigator = LocalAppNavigator.current

    // The event bus is collected here, before anything can return early. It used to be
    // collected *after* the no-server branch returned, so the error that branch produces
    // — "Both the project URL and the anon key are required" — had nowhere to go and the
    // user was told nothing.
    LoginScreenEffects(
        events = viewModel.events,
        onNavigateHome = { navigator.navigate(AppDestination.AgendaGraph(AgendaStartRoute.Today)) },
    )

    // With no project configured there is nothing to sign in to, and the
    // credential form's failure would arrive as a network error pointing at the
    // password. The configuration comes first so the user is never asked to retype
    // a correct password because the server was never named.
    //
    // Offline mode is the exception that proves it needs no server at all, so the
    // way out is on this screen too — not only on the credential form, where it
    // would be invisible to exactly the people who have nothing to configure.
    if (state is AuthUiState.SignedOutWithNoServer) {
        ServerConfigForm(
            onSave = { url, key -> viewModel.onIntent(AuthIntent.SaveServerConfig(url, key)) },
            onContinueOffline = { viewModel.onIntent(AuthIntent.ContinueOffline) },
        )
        return
    }

    Column(
        modifier =
            Modifier
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

        val awaitingConfirmation = state as? AuthUiState.AwaitingEmailConfirmation
        if (awaitingConfirmation != null) {
            ConfirmationPendingContent(
                email = awaitingConfirmation.email,
                isLoading = state is AuthUiState.Loading,
                onResend = {
                    viewModel.onIntent(AuthIntent.SendMagicLink(awaitingConfirmation.email))
                },
                onBack = {
                    viewModel.onIntent(AuthIntent.ResetState)
                },
            )
        } else {
            AuthFormFields(
                emailTag = TestTags.AUTH_EMAIL_INPUT,
                passwordTag = TestTags.AUTH_PASSWORD_INPUT,
                submitTag = TestTags.AUTH_SIGN_IN_BUTTON,
                submitLabel = if (form.value.isSignUp) "Sign Up" else "Sign In",
                emailState = emailState,
                passwordState = passwordState,
                isLoading = state is AuthUiState.Loading,
                onSubmit = {
                    val email = emailState.text.toString()
                    val password = passwordState.text.toString()
                    if (form.value.isSignUp) {
                        viewModel.onIntent(AuthIntent.SignUp(email, password))
                    } else {
                        viewModel.onIntent(AuthIntent.SignIn(email, password))
                    }
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = {
                    form.update { copy(isSignUp = !isSignUp) }
                },
                modifier =
                    Modifier
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
                onClick = { viewModel.onIntent(AuthIntent.ContinueOffline) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.AUTH_CONTINUE_OFFLINE_BUTTON),
            ) {
                Text("Continue Offline")
            }
        }
    }
}

/**
 * Everything the login screen does that is not drawing: the one collection of the
 * auth event bus, and the two ways an event can reach the user.
 *
 * The implementation is [AuthScreenEffects], shared with the other auth screens —
 * the login screen was the first to take this shape, and the other two follow it.
 * This name is the login screen's own contract: a contract that cannot be mounted
 * on its own cannot be tested on its own, so the desktop test mounts this, hands
 * it an event, and asserts that the user is told.
 */
@Composable
fun LoginScreenEffects(
    events: Flow<AuthUiEvent>,
    onNavigateHome: () -> Unit,
) {
    AuthScreenEffects(events = events, onNavigateHome = onNavigateHome)
}

/**
 * The project URL and anon key, entered once.
 *
 * The anon key is a **publishable** identifier — it ships inside every copy of the
 * app and is meant to be readable. The label says so, because a field named "key"
 * next to a password field invites the user to paste the service-role key, and
 * that key does not belong on a device.
 *
 * @param onContinueOffline the way out for a user who has no project and does not
 * want one. It carries the same test tag as the button on the credential form:
 * only one of the two screens is ever composed, so there is no duplicate to
 * disambiguate, and a test that looks for the tag finds the way out wherever
 * the user is.
 */
@Composable
private fun ServerConfigForm(
    onSave: (String, String) -> Unit,
    onContinueOffline: () -> Unit,
) {
    val urlState = rememberTextFieldState()
    val anonKeyState = rememberTextFieldState()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Connect your server", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        SelectableText(
            text =
                "Paste the project URL and the anon key from your Supabase project settings. " +
                    "The anon key is publishable, not a secret.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            state = urlState,
            label = { Text("Project URL") },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_SERVER_URL_INPUT),
            lineLimits = TextFieldLineLimits.SingleLine,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            state = anonKeyState,
            label = { Text("Anon key (publishable)") },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_SERVER_KEY_INPUT),
            lineLimits = TextFieldLineLimits.SingleLine,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onSave(urlState.text.toString(), anonKeyState.text.toString()) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_SAVE_SERVER_BUTTON),
        ) {
            Text("Save")
        }

        Spacer(modifier = Modifier.height(24.dp))

        TextButton(
            onClick = onContinueOffline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_CONTINUE_OFFLINE_BUTTON),
        ) {
            Text("Continue without an account")
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoginScreenFormPreview() =
    PreviewThemed(darkTheme = false) {
        LoginScreenFormContent(
            emailState = rememberTextFieldState("user@example.com"),
            passwordState = rememberTextFieldState("password"),
            isLoading = false,
            isSignUp = false,
            onSignIn = {},
            onToggleMode = {},
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoginScreenLoadingPreview() =
    PreviewThemed(darkTheme = false) {
        LoginScreenFormContent(
            emailState = rememberTextFieldState("user@example.com"),
            passwordState = rememberTextFieldState("password"),
            isLoading = true,
            isSignUp = false,
            onSignIn = {},
            onToggleMode = {},
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoginScreenDarkPreview() =
    PreviewThemed(darkTheme = true) {
        LoginScreenFormContent(
            emailState = rememberTextFieldState(),
            passwordState = rememberTextFieldState(),
            isLoading = false,
            isSignUp = true,
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
    emailState: TextFieldState,
    passwordState: TextFieldState,
    isLoading: Boolean,
    isSignUp: Boolean,
    onSignIn: () -> Unit,
    onToggleMode: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Singularity Todo", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(32.dp))
        AuthFormFields(
            emailTag = TestTags.AUTH_EMAIL_INPUT,
            passwordTag = TestTags.AUTH_PASSWORD_INPUT,
            submitTag = TestTags.AUTH_SIGN_IN_BUTTON,
            submitLabel = if (isSignUp) "Sign Up" else "Sign In",
            emailState = emailState,
            passwordState = passwordState,
            isLoading = isLoading,
            onSubmit = onSignIn,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onToggleMode, modifier = Modifier.fillMaxWidth()) {
            Text(if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Sign Up")
        }
    }
}
