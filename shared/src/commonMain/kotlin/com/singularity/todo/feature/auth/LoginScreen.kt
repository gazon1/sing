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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
 * ## Why the events are presented here and not copied into local flags
 *
 * The screen used to keep `errorMessage` and `message` as `remember` flags and
 * copy each event into one of them, which meant the flag had to be cleared by
 * hand wherever the screen moved on — four places, each of them a chance to
 * leave a stale error on the form. The event says what happened once; the
 * screen shows it where it arrives and forgets it. That is the shape
 * `BackupScreen` already has for `BackupUiEvent`.
 *
 * ## Why the notifications travel over a channel of their own
 *
 * The bus is a channel, and a channel has one consumer: a second collector on
 * `events` takes its share of the events away from the first. The screen owns
 * the single collection, so the notifications it maps are handed to the host
 * over a channel of their own and the host is the only consumer of those.
 *
 * ## Why this is public
 *
 * It is the screen's whole event contract, and a contract that cannot be
 * mounted on its own cannot be tested on its own: the desktop test mounts this,
 * hands it an event, and asserts that the user is told. `BackupScreen` takes
 * its event flow as a parameter for the same reason.
 */
@Composable
fun LoginScreenEffects(
    events: Flow<AuthUiEvent>,
    onNavigateHome: () -> Unit,
) {
    val notificationChannel = remember { Channel<Notification>(Channel.BUFFERED) }
    val notificationFlow = remember(notificationChannel) { notificationChannel.receiveAsFlow() }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                // A destination is the screen's business, not a notification.
                AuthUiEvent.NavigateToHome -> onNavigateHome()

                // Everything else is something to tell the user, and it is told
                // where it arrives rather than copied into a flag.
                else -> event.toNotification()?.let { notificationChannel.send(it) }
            }
        }
    }

    NotificationHost(events = notificationFlow)
}

/**
 * How an auth event reaches the user.
 *
 * An error is a dialog and a neutral notice is a snackbar: the two are told apart
 * on purpose, so "your account was created" is never read as a failure, and a
 * rejected password is never read as a success.
 *
 * [AuthUiEvent.NavigateToHome] is not a notification — it is a destination, and
 * the screen handles it itself.
 */
internal fun AuthUiEvent.toNotification(): Notification? =
    when (this) {
        is AuthUiEvent.Error -> Notification.Error(message)
        is AuthUiEvent.Message -> Notification.Toast(message)
        AuthUiEvent.NavigateToHome -> null
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
            errorMessage = null,
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
            errorMessage = null,
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
            errorMessage = "Invalid email or password",
            isSignUp = true,
            onSignIn = {},
            onToggleMode = {},
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoginScreenMessagePreview() =
    PreviewThemed(darkTheme = false) {
        LoginScreenFormContent(
            emailState = rememberTextFieldState("user@example.com"),
            passwordState = rememberTextFieldState("password"),
            isLoading = false,
            errorMessage = null,
            message = "Account created. Check your email to confirm the address.",
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
    errorMessage: String?,
    message: String? = null,
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
            errorMessage = errorMessage,
            message = message,
            onSubmit = onSignIn,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onToggleMode, modifier = Modifier.fillMaxWidth()) {
            Text(if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Sign Up")
        }
    }
}
