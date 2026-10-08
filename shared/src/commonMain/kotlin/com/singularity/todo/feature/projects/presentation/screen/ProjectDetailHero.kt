package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailActions
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Hero section of [ProjectDetailScreen]: project icon, name editor, description editor,
 * and a "Saved X ago" label.
 *
 * Exposed as public to support preview providers and unit tests without a VM.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ProjectHeroSection(
    ui: ProjectDetailUi,
    lastEditedAt: Instant?,
    now: Instant,
    nameDraft: String,
    descriptionDraft: String,
    actions: ProjectDetailActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(ui.project.color))
                    .clickable(
                        onClickLabel = "Change project icon and color",
                        onClick = actions::onOpenColorSheet,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val icon = ProjectIconRegistry.iconByKey(ui.project.icon)
                    ?: Icons.Filled.Folder
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = nameDraft,
                    onValueChange = { actions.onUpdateName(it) },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (descriptionDraft.isNotEmpty() || ui.project.description != null) {
                    Spacer(Modifier.height(4.dp))
                    BasicTextField(
                        value = descriptionDraft,
                        onValueChange = { actions.onUpdateDescription(it) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                lastEditedAt?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatSavedRelative(now, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Chips row showing project metadata: due date, parent project, and child-project count.
 *
 * Exposed as public to support preview providers and unit tests without a VM.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectMetaChipsRow(ui: ProjectDetailUi, actions: ProjectDetailActions, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ui.project.dueDate?.let { date ->
            FilterChip(
                selected = false,
                onClick = actions::onOpenDueDateSheet,
                label = { Text(date.toString()) },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
        ui.parent?.let { parent ->
            FilterChip(
                selected = false,
                onClick = { actions.onOpenParentSheet(parent.id) },
                label = { Text(parent.name) },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        if (ui.childProjects.isNotEmpty()) {
            FilterChip(
                selected = false,
                onClick = actions::onOpenChildrenSheet,
                label = { Text("${ui.childProjects.size} sub-projects") },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
        if (ui.project.inheritedTagGroupIds.isNotEmpty()) {
            FilterChip(
                selected = false,
                onClick = actions::onOpenInheritedTagGroupsSheet,
                label = { Text("${ui.project.inheritedTagGroupIds.size} tag groups") },
                leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}
