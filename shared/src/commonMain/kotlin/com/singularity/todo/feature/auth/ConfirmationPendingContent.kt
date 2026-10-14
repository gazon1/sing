package com.singularity.todo.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ButtonSpinner
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Full-screen confirmation-pending state used by both [LoginScreen] and
 * [com.singularity.todo.feature.auth.presentation.MagicLinkConfirmScreen].
 *
 * Replaces the auth form while the user waits for an email confirmation,
 * clearly communicating which address is awaiting verification and providing
 * a way to resend the email or go back.
 *
 * @param email          The email address that is awaiting confirmation.
 * @param message        Optional neutral message to display (e.g. "Account created…").
 * @param isLoading      Whether to show the spinner — true during a resend round-trip.
 * @param onResend       Called when the user taps "Resend email".
 * @param onBack         Called when the user taps "Back to sign in".
 */
@Composable
fun ConfirmationPendingContent(
    email: String,
    message: String?,
    isLoading: Boolean,
    onResend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.MailOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Check your email",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(TestTags.AUTH_CONFIRMATION_HEADLINE),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "We sent a confirmation link to",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = email,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(TestTags.AUTH_CONFIRMATION_EMAIL),
        )

        if (message != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isLoading) {
            ButtonSpinner(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_LOADING),
            )
        } else {
            TextButton(
                onClick = onResend,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_RESEND_BUTTON),
            ) {
                Text("Resend email")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.AUTH_BACK_BUTTON),
            ) {
                Text("Back to sign in")
            }
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ConfirmationPendingPreview() = PreviewThemed(darkTheme = false) {
    ConfirmationPendingContent(
        email = "user@example.com",
        message = null,
        isLoading = false,
        onResend = {},
        onBack = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ConfirmationPendingWithMessagePreview() = PreviewThemed(darkTheme = false) {
    ConfirmationPendingContent(
        email = "user@example.com",
        message = "Account created. Check your email to confirm the address, then sign in.",
        isLoading = false,
        onResend = {},
        onBack = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ConfirmationPendingLoadingPreview() = PreviewThemed(darkTheme = false) {
    ConfirmationPendingContent(
        email = "user@example.com",
        message = null,
        isLoading = true,
        onResend = {},
        onBack = {},
    )
}
