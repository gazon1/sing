package com.singularity.todo.feature.profile.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.singularity.todo.core.ui.components.TopAppBar
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
import com.singularity.todo.core.ui.textfields.ObserveTextFieldEdits
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.nav.LocalAppNavigator
import org.koin.compose.viewmodel.koinViewModel

private const val PASSWORD_TAG = "change_password_input"
private const val SUBMIT_TAG = "change_password_submit"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(
    viewModel: ChangePasswordViewModel = koinViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    // The settings graph's own navigator is *not* read here, and reading it was
    // a crash: this screen is opened by leaving the settings graph
    // (`openChangePassword` → `onExitGraph`), so the graph's CompositionLocal is
    // gone by the time this screen is composed, and the local's default is an
    // `error(...)`. The app's back stack still holds the settings graph
    // underneath — the screen is pushed onto it, not swapped for it — so the
    // app-level back is the way out of this screen.
    val navigator = LocalAppNavigator.current

    val passwordState = rememberTextFieldState()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    CollectEvents(viewModel.events) { event ->
        when (event) {
            ChangePasswordEvent.Success -> {
                navigator.goBack()
            }

            is ChangePasswordEvent.Error -> {
                errorMessage = event.message
            }
        }
    }

    // Clear error when state resets to Idle
    LaunchedEffect(state) {
        if (state is ChangePasswordUiState.Idle && errorMessage != null) {
            // keep error visible until user dismisses or retries
        }
    }

    ChangePasswordScreenContent(
        passwordState = passwordState,
        isLoading = state is ChangePasswordUiState.Loading,
        errorMessage = errorMessage,
        onFieldEdited = { errorMessage = null },
        onSubmit = {
            errorMessage = null
            viewModel.onIntent(ChangePasswordIntent.Submit(passwordState.text.toString()))
        },
        onBack = { navigator.goBack() },
    )
}

// ===== Preview =====

@Composable
private fun ChangePasswordScreenPreview() = PreviewThemed(darkTheme = false) {
    ChangePasswordScreenContent(
        passwordState = rememberTextFieldState("NewPassword123!"),
        isLoading = false,
        errorMessage = null,
        onFieldEdited = {},
        onSubmit = {},
        onBack = {},
    )
}

@Composable
internal fun ChangePasswordScreenContent(
    passwordState: TextFieldState,
    isLoading: Boolean,
    errorMessage: String?,
    onFieldEdited: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change Password") },
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
            ChangePasswordForm(
                passwordState = passwordState,
                isLoading = isLoading,
                errorMessage = errorMessage,
                onFieldEdited = onFieldEdited,
                onSubmit = onSubmit,
            )
        }
    }
}

/**
 * The password form, from the intro text down to the Change Password button.
 *
 * Shared by [ChangePasswordScreen] and [ChangePasswordScreenContent], which differ
 * only in where the password and the error come from — the two used to be two copies
 * of this block, and only one of them could be right after a label change.
 */
@Composable
private fun ChangePasswordForm(
    passwordState: TextFieldState,
    isLoading: Boolean,
    errorMessage: String?,
    onFieldEdited: () -> Unit,
    onSubmit: () -> Unit,
) {
    // The field owns its text, so "the user typed something" is observed rather than reported.
    // Typing clears the stale error, the way the value-based `onValueChange` did.
    ObserveTextFieldEdits(passwordState) { onFieldEdited() }

    Spacer(modifier = Modifier.height(16.dp))

    SelectableText(
        text = "Enter a new password for your account.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Password field
    OutlinedSecureTextField(
        state = passwordState,
        label = { Text("New Password") },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PASSWORD_TAG),
        textObfuscationMode = TextObfuscationMode.Hidden,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = errorMessage != null,
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
            enabled = passwordState.text.isNotBlank(),
        ) {
            Text("Change Password")
        }
    }
}
