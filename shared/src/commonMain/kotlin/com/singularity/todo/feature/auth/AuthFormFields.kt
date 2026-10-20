package com.singularity.todo.feature.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ButtonSpinner
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Shared email + password + feedback + submit UI for all auth form screens.
 *
 * Both [LoginScreen] and [AttachAccountScreen] render the same field structure
 * with different tags, button labels, and submit actions. Centralising the layout
 * ensures both screens behave identically and new auth forms need only provide
 * the tag values and callbacks.
 *
 * @param emailTag            Test tag for the email text field.
 * @param passwordTag         Test tag for the password text field.
 * @param submitTag           Test tag for the submit button (also used for [ButtonSpinner]).
 * @param submitLabel         Label shown on the submit button (e.g. "Sign In", "Attach Account").
 * @param emailState         State of the email field. The field owns its text.
 * @param passwordState      State of the password field. The field owns its text.
 * @param isLoading          Whether to show the loading spinner instead of the button.
 * @param isFormEnabled      Deprecated. The form is no longer shown during
 *                           [AuthUiState.AwaitingEmailConfirmation] — that state renders
 *                           [ConfirmationPendingContent] instead. This parameter is kept
 *                           only to avoid breaking callers and will be removed in a future
 *                           release. Do not pass it; the component defaults to enabled.
 * @param errorMessage       Non-null error message to display, or null to show nothing.
 * @param message            Non-null neutral (non-error) message to display, or null.
 * @param onSubmit           Called when the submit button is clicked.
 */
@Composable
fun AuthFormFields(
    emailTag: String,
    passwordTag: String,
    submitTag: String,
    submitLabel: String,
    emailState: TextFieldState,
    passwordState: TextFieldState,
    isLoading: Boolean,
    errorMessage: String?,
    message: String? = null,
    onSubmit: () -> Unit,
    /**
     * Extra condition for the submit button, beyond both fields being filled.
     *
     * `true` by default so every existing caller is unchanged. The attach screen
     * passes `disposition != null`, because submitting without having answered what
     * happens to the user's offline data would leave the only sensible outcome as a
     * silent default — and REQ-UA-005 forbids exactly that one.
     */
    submitEnabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            state = emailState,
            label = { Text("Email") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(emailTag),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            lineLimits = TextFieldLineLimits.SingleLine,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedSecureTextField(
            state = passwordState,
            label = { Text("Password") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(passwordTag),
            textObfuscationMode = TextObfuscationMode.Hidden,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                    .testTag(submitTag),
                enabled = emailState.text.isNotBlank() &&
                    passwordState.text.isNotBlank() &&
                    submitEnabled,
            ) {
                Text(submitLabel)
            }
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AuthFormFieldsPreview() = PreviewThemed(darkTheme = false) {
    AuthFormFields(
        emailTag = "auth_email",
        passwordTag = "auth_password",
        submitTag = "auth_submit",
        submitLabel = "Sign In",
        emailState = rememberTextFieldState("user@example.com"),
        passwordState = rememberTextFieldState("password"),
        isLoading = false,
        errorMessage = null,
        message = null,
        onSubmit = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AuthFormFieldsWithMessagePreview() = PreviewThemed(darkTheme = false) {
    AuthFormFields(
        emailTag = "auth_email",
        passwordTag = "auth_password",
        submitTag = "auth_submit",
        submitLabel = "Sign In",
        emailState = rememberTextFieldState("user@example.com"),
        passwordState = rememberTextFieldState("password"),
        isLoading = false,
        errorMessage = null,
        message = "Account created. Check your email to confirm the address.",
        onSubmit = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AuthFormFieldsLoadingPreview() = PreviewThemed(darkTheme = false) {
    AuthFormFields(
        emailTag = "auth_email",
        passwordTag = "auth_password",
        submitTag = "auth_submit",
        submitLabel = "Sign In",
        emailState = rememberTextFieldState("user@example.com"),
        passwordState = rememberTextFieldState("password"),
        isLoading = true,
        errorMessage = null,
        message = null,
        onSubmit = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AuthFormFieldsDisabledPreview() = PreviewThemed(darkTheme = false) {
    AuthFormFields(
        emailTag = "auth_email",
        passwordTag = "auth_password",
        submitTag = "auth_submit",
        submitLabel = "Sign In",
        emailState = rememberTextFieldState("user@example.com"),
        passwordState = rememberTextFieldState("password"),
        isLoading = false,
        errorMessage = null,
        message = null,
        onSubmit = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AuthFormFieldsErrorPreview() = PreviewThemed(darkTheme = false) {
    AuthFormFields(
        emailTag = "auth_email",
        passwordTag = "auth_password",
        submitTag = "auth_submit",
        submitLabel = "Sign In",
        emailState = rememberTextFieldState("user@example.com"),
        passwordState = rememberTextFieldState("wrong-password"),
        isLoading = false,
        errorMessage = "Invalid email or password.",
        message = null,
        onSubmit = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AuthFormFieldsDarkPreview() = PreviewThemed(darkTheme = true) {
    AuthFormFields(
        emailTag = "auth_email",
        passwordTag = "auth_password",
        submitTag = "auth_submit",
        submitLabel = "Sign In",
        emailState = rememberTextFieldState("user@example.com"),
        passwordState = rememberTextFieldState("password"),
        isLoading = false,
        errorMessage = null,
        message = null,
        onSubmit = {},
    )
}
