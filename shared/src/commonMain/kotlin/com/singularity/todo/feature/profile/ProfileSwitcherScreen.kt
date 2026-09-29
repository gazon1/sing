package com.singularity.todo.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.test.fakes.FakeProfileRepository
import org.koin.compose.viewmodel.koinViewModel

private val PROFILE_COLORS = listOf(
    Color(0xFF2196F3), // blue
    Color(0xFF4CAF50), // green
    Color(0xFFF44336), // red
    Color(0xFFFF9800), // orange
    Color(0xFF9C27B0), // purple
    Color(0xFF00BCD4), // cyan
    Color(0xFFE91E63), // pink
    Color(0xFF607D8B), // grey
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherScreen(modifier: Modifier = Modifier, onBack: () -> Unit = {}) {
    val viewModel: ProfileSwitcherViewModel = koinViewModel()
    ProfileSwitcherContent(viewModel = viewModel, modifier = modifier, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSwitcherContent(viewModel: ProfileSwitcherViewModel, modifier: Modifier = Modifier, onBack: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<Profile?>(null) }

    if (state.isLoading) {
        LoadingIndicator(modifier = modifier)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        ProfileListContent(
            profiles = state.profiles,
            activeProfileId = state.activeProfileId,
            modifier = modifier.padding(padding),
            onSelect = { viewModel.onIntent(ProfileSwitcherIntent.SwitchTo(it)) },
            onDeleteRequest = { profileToDelete = it },
            onCreateClick = { showCreateDialog = true },
        )
    }

    if (showCreateDialog) {
        CreateProfileDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, emoji, colorIdx ->
                viewModel.onIntent(ProfileSwitcherIntent.Create(name, emoji, colorIdx))
                showCreateDialog = false
            },
        )
    }

    profileToDelete?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            onDismiss = { profileToDelete = null },
            onConfirm = {
                viewModel.onIntent(ProfileSwitcherIntent.Delete(profile.id))
                profileToDelete = null
            },
        )
    }
}

@Composable
private fun ProfileListContent(
    profiles: List<Profile>,
    activeProfileId: ProfileId,
    modifier: Modifier = Modifier,
    onSelect: (ProfileId) -> Unit,
    onDeleteRequest: (Profile) -> Unit,
    onCreateClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Profiles",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            FilledTonalButton(
                onClick = onCreateClick,
                modifier = Modifier.testTag(TestTags.PROFILE_CREATE_BUTTON),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles, key = { it.id.value }) { profile ->
                ProfileCard(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    onSelect = { onSelect(profile.id) },
                    onDelete = if (!profile.isDefault && profiles.size > 1) {
                        { onDeleteRequest(profile) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun DeleteProfileDialog(profile: Profile, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete profile?") },
        text = { Text("\"${profile.name}\" will be permanently deleted. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ProfileCard(profile: Profile, isActive: Boolean, onSelect: () -> Unit, onDelete: (() -> Unit)?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.profileItem(profile.name))
            .clickable { onSelect() },
        colors = if (isActive) {
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            androidx.compose.material3.CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Color dot
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PROFILE_COLORS.getOrElse(profile.colorIdx) { PROFILE_COLORS[0] }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.emoji,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (profile.isDefault) {
                    Text(
                        text = "Default",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isActive) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, emoji: String, colorIdx: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🤖") }
    var colorIdx by remember { mutableIntStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { if (it.length <= 2) emoji = it },
                    label = { Text("Emoji") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROFILE_COLORS.forEachIndexed { idx, color ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { colorIdx = idx },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (idx == colorIdx) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, emoji, colorIdx) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Suppress("VIEW_MODEL_IN_COMPOSABLE", "ViewModelConstructorInComposable") // Preview pattern: construct VM with Fake* deps directly
@Composable
private fun ProfileSwitcherScreenPreview() = PreviewThemed {
    val fakeProfileRepo = FakeProfileRepository()
    val vm = ProfileSwitcherViewModel(profileRepository = fakeProfileRepo)
    ProfileSwitcherContent(viewModel = vm)
}
