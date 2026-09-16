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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.settings.presentation.nav.LocalSettingsNavigator
import com.singularity.todo.test.fakes.FakeProfileRepository
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AccountSettingsScreen(modifier: Modifier = Modifier, vm: AccountSettingsViewModel = koinViewModel()) {
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle(initialValue = null)
    val settingsNavigator = LocalSettingsNavigator.current

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        activeProfile?.let { profile ->
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

        SettingsSection(title = "Profiles") {
            FilledTonalButton(
                onClick = { settingsNavigator.openProfileSwitcher() },
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
                text = "Switch to a different profile (Personal, AI Agent, etc.). Each profile has its own tasks, projects, and tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ===== Preview =====

@Preview
@Composable
private fun AccountSettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    val fakeProfileRepo = FakeProfileRepository()
    val activeProfile by fakeProfileRepo.activeProfile().collectAsStateWithLifecycle(initialValue = null)
    AccountSettingsScreenPreviewContent(
        activeProfile = activeProfile,
        onNavigateToProfileSwitcher = {},
    )
}

@Preview
@Composable
private fun AccountSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    val fakeProfileRepo = FakeProfileRepository()
    val activeProfile by fakeProfileRepo.activeProfile().collectAsStateWithLifecycle(initialValue = null)
    AccountSettingsScreenPreviewContent(
        activeProfile = activeProfile,
        onNavigateToProfileSwitcher = {},
    )
}

/**
 * Stateless preview variant that takes an explicit callback instead of [LocalSettingsNavigator].
 * Mirrors the layout of [AccountSettingsScreen] without requiring the nav graph context.
 */
@Composable
private fun AccountSettingsScreenPreviewContent(
    activeProfile: Profile?,
    onNavigateToProfileSwitcher: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        activeProfile?.let { profile ->
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

        SettingsSection(title = "Profiles") {
            FilledTonalButton(
                onClick = onNavigateToProfileSwitcher,
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
                text = "Switch to a different profile (Personal, AI Agent, etc.). Each profile has its own tasks, projects, and tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
