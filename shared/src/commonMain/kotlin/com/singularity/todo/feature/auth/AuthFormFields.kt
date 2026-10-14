package com.singularity.todo.feature.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
 * @param email              Current email value.
 * @param password           Current password value.
 * @param isLoading          Whether to show the loading spinner instead of the button.
 * @param isFormEnabled      Deprecated. The form is no longer shown during
 *                           [AuthUiState.AwaitingEmailConfirmation] — that state renders
 *                           [ConfirmationPendingContent] instead. This parameter is kept
 *                           only to avoid breaking callers and will be removed in a future
 *                           release. Do not pass it; the component defaults to enabled.
 * @param errorMessage       Non-null error message to display, or null to show nothing.
 * @param message            Non-null neutral (non-error) message to display, or null.
 * @param onEmailChange      Called when the email field changes.
 * @param onPasswordChange   Called when the password field changes.
 * @param onSubmit           Called when the submit button is clicked.
 */
@Composable
fun AuthFormFields(
    emailTag: String,
    passwordTag: String,
    submitTag: String,
    submitLabel: String,
    email: String,
    password: String,
    isLoading: Boolean,
    errorMessage: String?,
    message: String? = null,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(emailTag),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(passwordTag),
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
                enabled = email.isNotBlank() && password.isNotBlank(),
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
        email = "user@example.com",
        password = "password",
        isLoading = false,
        errorMessage = null,
        message = null,
        onEmailChange = {},
        onPasswordChange = {},
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
        email = "user@example.com",
        password = "password",
        isLoading = false,
        errorMessage = null,
        message = "Account created. Check your email to confirm the address.",
        onEmailChange = {},
        onPasswordChange = {},
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
        email = "user@example.com",
        password = "password",
        isLoading = true,
        errorMessage = null,
        message = null,
        onEmailChange = {},
        onPasswordChange = {},
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
        email = "user@example.com",
        password = "password",
        isLoading = false,
        errorMessage = null,
        message = null,
        onEmailChange = {},
        onPasswordChange = {},
        onSubmit = {},
    )
}
