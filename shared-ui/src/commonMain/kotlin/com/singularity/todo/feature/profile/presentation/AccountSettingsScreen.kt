package com.singularity.todo.feature.profile.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.singularity.todo.core.ui.components.ConfirmActionDialog
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.text.SelectableText
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
    var showSignOutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        activeProfile?.let { profile ->
            ActiveProfileSection(profile)
        }

        // The account the app is signed in to — or the fact that it is signed
        // in to none. The screen used to show two cards both titled "Account"
        // and never named the account, so a signed-in session and a signed-out
        // one looked the same.
        AccountStatusSection(session = session)

        // Change password — only shown for signed-in accounts.
        if (session is Session.SignedIn) {
            ChangePasswordSection(onChangePassword = { settingsNavigator.openChangePassword() })
        }

        ProfileSwitcherSection(onSwitchProfile = { settingsNavigator.openProfileSwitcher() })

        // Attach account — only shown for anonymous sessions.
        if (authVm.session.value is Session.Anonymous) {
            AttachAccountSection(onAttachAccount = { settingsNavigator.openAttachAccount() })
        }

        SignOutSection(session = session, onSignOut = { showSignOutConfirm = true })
    }

    if (showSignOutConfirm) {
        ConfirmActionDialog(
            title = "Sign out",
            text = signOutExplanation(session),
            confirmButtonText = "Sign out",
            onConfirm = {
                showSignOutConfirm = false
                authVm.onIntent(AuthIntent.SignOut)
            },
            onDismiss = { showSignOutConfirm = false },
        )
    }
}

// ===== Preview =====

@Preview
@Composable
private fun AccountSettingsScreenLightPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        AccountSettingsScreenPreviewContent(
            activeProfile = PreviewSamples.previewProfile,
            onNavigateToProfileSwitcher = {},
            onChangePassword = {},
            onSignOut = {},
        )
    }

@Preview
@Composable
private fun AccountSettingsScreenDarkPreview() =
    PreviewThemed(darkTheme = true, useSurface = false) {
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
    onAttachAccount: () -> Unit = {},
    session: Session =
        Session.SignedIn(
            userId =
                com.singularity.todo.core.ids.UserId
                    .fromString("preview-user-id"),
            email = "alice@example.com",
            accessToken = "preview-token",
            refreshToken = "preview-refresh",
        ),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        activeProfile?.let { profile ->
            ActiveProfileSection(profile)
        }

        AccountStatusSection(session = session)

        // Change password — only shown for signed-in accounts.
        if (session is Session.SignedIn) {
            ChangePasswordSection(onChangePassword = onChangePassword)
        }

        ProfileSwitcherSection(onSwitchProfile = onNavigateToProfileSwitcher)

        // Attach account — only shown for anonymous sessions.
        if (session is Session.Anonymous) {
            AttachAccountSection(onAttachAccount = onAttachAccount)
        }

        SignOutSection(session = session, onSignOut = onSignOut)
    }
}

// ─── Sections ──────────────────────────────────────────────────────────────
//
// One definition per row of this screen, shared by [AccountSettingsScreen] and
// [AccountSettingsScreenPreviewContent]. The two used to drift — a label or a
// test tag changed in one was a divergence the other only revealed in a
// screenshot — so every row below is rendered by both.

@Composable
private fun ActiveProfileSection(profile: Profile) {
    SettingsSection(title = "Active profile") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileAvatar(profile = profile)
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

/**
 * The profile's emoji, or the person icon when the profile has none.
 *
 * The emoji field of the new-profile form is optional — only the name is
 * required — so a profile can legitimately have an empty one, and an empty
 * circle said nothing at all. The buttons next to it already fall back to
 * [Icons.Default.Person].
 */
@Composable
private fun ProfileAvatar(profile: Profile) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (profile.emoji.isBlank()) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = profile.emoji,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

/**
 * The account the app is signed in to — or the fact that it is signed in to
 * none.
 *
 * The screen used to hold two cards both titled "Account" and never named the
 * account, so a signed-in session and a signed-out one were not visually
 * distinguishable.
 */
@Composable
private fun AccountStatusSection(session: Session) {
    when (session) {
        is Session.SignedIn ->
            SettingsSection(title = "Account") {
                Text(
                    text = "Signed in as",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectableText(
                    text = session.email,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(TestTags.AUTH_ACCOUNT_EMAIL),
                )
            }

        is Session.Anonymous ->
            SettingsSection(title = "Account") {
                Text(
                    text = "Not signed in",
                    style = MaterialTheme.typography.bodyLarge,
                )
                SelectableText(
                    text =
                        "This device works on its own. Attach an account to keep your data " +
                            "across devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

        // Nothing is known yet, and a signed-out session never reaches this
        // screen — the auth guard shows the sign-in screen instead.
        Session.Loading, Session.SignedOut -> Unit
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
        SelectableText(
            text =
                "Switch to a different profile (Personal, AI Agent, etc.). Each profile has " +
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
    SettingsSection(title = "Attach account") {
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
        SelectableText(
            text = "Create an account using your current anonymous session's data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SignOutSection(
    session: Session,
    onSignOut: () -> Unit,
) {
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
        SelectableText(
            text = signOutExplanation(session),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * What signing out will do — and that is not the same sentence when there is
 * no account to be signed out of.
 *
 * The card used to read the same in both states, so a signed-in session and a
 * signed-out one looked alike.
 */
private fun signOutExplanation(session: Session): String =
    when (session) {
        is Session.SignedIn ->
            "You will be signed out of this account on this device. " +
                "Your tasks stay on this device."

        else ->
            "You are not signed in. Signing out returns you to the sign-in screen. " +
                "Your tasks stay on this device."
    }
