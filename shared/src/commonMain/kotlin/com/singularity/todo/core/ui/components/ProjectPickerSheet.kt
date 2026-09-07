package com.singularity.todo.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    onProjectSelected: (Project?) -> Unit,
    onDismiss: () -> Unit,
) {
    val projectsRepo: ProjectsRepository = koinInject()
    val settingsRepo: SettingsRepository = koinInject()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }

    LaunchedEffect(Unit) {
        val userId = settingsRepo.userId.first()
        projects = projectsRepo.watchProjects(userId).first()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Select Project",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            // No Project (Inbox) option
            Text(
                text = "No Project (Inbox)",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProjectSelected(null); onDismiss() }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            LazyColumn {
                items(projects, key = { it.id.value }) { project ->
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProjectSelected(project); onDismiss() }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
