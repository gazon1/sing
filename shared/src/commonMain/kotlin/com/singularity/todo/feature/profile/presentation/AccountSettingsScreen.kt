package com.singularity.todo.feature.profile.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.auth.AuthIntent
import com.singularity.todo.feature.auth.AuthViewModel
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.settings.presentation.nav.LocalSettingsNavigator
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AccountSettingsScreen(
    modifier: Modifier = Modifier,
    vm: AccountSettingsViewModel = koinViewModel(),
) {
    val authVm: AuthViewModel = koinViewModel()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle(initialValue = null)
    val session by authVm.session.collectAsStateWithLifecycle(initialValue = Session.Loading)
    val settingsNavigator = LocalSettingsNavigator.current

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        activeProfile?.let { profile ->
            ActiveProfileSection(profile)
        }

        // Change password — only shown for signed-in accounts.
        if (session is Session.SignedIn) {
            ChangePasswordSection(onChangePassword = { settingsNavigator.openChangePassword() })
        }

        ProfileSwitcherSection(onSwitchProfile = { settingsNavigator.openProfileSwitcher() })

        // Attach account — only shown for anonymous sessions.
        if (authVm.session.value is Session.Anonymous) {
            AttachAccountSection(onAttachAccount = { settingsNavigator.openAttachAccount() })
        }

        SignOutSection(onSignOut = { authVm.onIntent(AuthIntent.SignOut) })
    }
}

// ===== Preview =====

@Preview
@Composable
private fun AccountSettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    AccountSettingsScreenPreviewContent(
        activeProfile = PreviewSamples.previewProfile,
        onNavigateToProfileSwitcher = {},
        onChangePassword = {},
        onSignOut = {},
    )
}

@Preview
@Composable
private fun AccountSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    AccountSettingsScreenPreviewContent(
        activeProfile = PreviewSamples.previewProfile,
        onNavigateToProfileSwitcher = {},
        onChangePassword = {},
        onSignOut = {},
    )
}

/**
 * Stateless preview variant that takes an explicit callback instead of [LocalSettingsNavigator].
 * Mirrors the layout of [AccountSettingsScreen] without requiring the nav graph context.
 */
@Composable
internal fun AccountSettingsScreenPreviewContent(
    activeProfile: Profile?,
    onNavigateToProfileSwitcher: () -> Unit,
    onChangePassword: () -> Unit,
    onSignOut: () -> Unit,
    session: Session = Session.SignedIn(
        userId = com.singularity.todo.core.ids.UserId.fromString("preview-user-id"),
        email = "alice@example.com",
        accessToken = "preview-token",
        refreshToken = "preview-refresh",
    ),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        activeProfile?.let { profile ->
            ActiveProfileSection(profile)
        }

        // Change password — only shown for signed-in accounts.
        if (session is Session.SignedIn) {
            ChangePasswordSection(onChangePassword = onChangePassword)
        }

        ProfileSwitcherSection(onSwitchProfile = onNavigateToProfileSwitcher)

        SignOutSection(onSignOut = onSignOut)
    }
}

// ─── Sections ──────────────────────────────────────────────────────────────
//
// One definition per row of this screen, shared by [AccountSettingsScreen] and
// [AccountSettingsScreenPreviewContent]. The two were byte-identical for three of
// the four rows, so a label or test tag changed in one was a divergence the other
// only revealed in a screenshot.

@Composable
private fun ActiveProfileSection(profile: Profile) {
    SettingsSection(title = "Account") {
        Text(
            text = "Active profile",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = profile.emoji,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
            Column {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = profile.id.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Change password — only shown for signed-in accounts. */
@Composable
private fun ChangePasswordSection(onChangePassword: () -> Unit) {
    SettingsSection(title = "Security") {
        FilledTonalButton(
            onClick = onChangePassword,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Person, contentDescription = null)
            Text(
                text = "  Change password",
                modifier = Modifier.padding(end = 8.dp),
            )
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun ProfileSwitcherSection(onSwitchProfile: () -> Unit) {
    SettingsSection(title = "Profiles") {
        FilledTonalButton(
            onClick = onSwitchProfile,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Person, contentDescription = null)
            Text(
                text = "  Switch profile",
                modifier = Modifier.padding(end = 8.dp),
            )
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
        Text(
            text = "Switch to a different profile (Personal, AI Agent, etc.). Each profile has " +
                "its own tasks, projects, and tags.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Attach account — only shown for anonymous sessions. */
@Composable
private fun AttachAccountSection(onAttachAccount: () -> Unit) {
    SettingsSection(title = "Account") {
        FilledTonalButton(
            onClick = onAttachAccount,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.AUTH_ATTACH_ACCOUNT_BUTTON),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(
                text = "  Attach account",
                modifier = Modifier.padding(end = 8.dp),
            )
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
        Text(
            text = "Create an account using your current anonymous session's data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SignOutSection(onSignOut: () -> Unit) {
    SettingsSection(title = "Sign out") {
        FilledTonalButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.AUTH_SIGN_OUT_BUTTON),
        ) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
            Text(
                text = "  Sign out",
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            text = "You will stay on this device. To remove all data, delete the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
