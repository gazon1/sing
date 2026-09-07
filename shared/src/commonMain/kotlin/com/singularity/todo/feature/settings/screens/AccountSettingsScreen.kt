package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.settings.SettingsUiState

@Composable
fun AccountSettingsScreen(
    state: SettingsUiState.Content,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Account") {
            Text(
                text = "User ID",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.userId,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AccountSettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    AccountSettingsScreen(
        state = SettingsUiState.Content(userId = "user_abc123"),
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AccountSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    AccountSettingsScreen(
        state = SettingsUiState.Content(userId = "user_xyz789"),
    )
}
