package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.projects.domain.logic.ProjectTreeBuilder
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.presentation.components.ProjectIcon
import org.koin.compose.koinInject

private val ROW_MIN_HEIGHT = 48.dp
private val INDENT_PER_LEVEL = 16.dp

@Suppress("FunctionSignature", "LongMethod", "UnusedParameter")
@Composable
fun ProjectPickerSheet(
    selectedId: ProjectId?,
    onSelect: (ProjectId?) -> Unit,
    onDismiss: () -> Unit,
) {
    val projectsRepo: ProjectsRepository = koinInject()
    val allProjects by
        projectsRepo.observeAllIncludingClosed().collectAsStateWithLifecycle(initialValue = emptyList())

    val activeProjects = allProjects.filter { !it.isDeleted && it.closedAt == null }
    val defaultProjects = activeProjects.filter { it.isDefault }
    val inboxProject = defaultProjects.singleOrNull()
    val userProjects =
        if (inboxProject == null) activeProjects else activeProjects.filter { it.id != inboxProject.id }
    val nodes = ProjectTreeBuilder.build(userProjects, collapsedIds = emptySet())
    val selectedProject = selectedId?.let { id -> allProjects.firstOrNull { it.id == id } }
    val selectedIsVisible =
        selectedId == null ||
            inboxProject?.id == selectedId ||
            nodes.any { it.id == selectedId }
    val selectedOutsideTree =
        if (!selectedIsVisible && selectedProject != null && !selectedProject.isDeleted) {
            selectedProject
        } else {
            null
        }

    Column(modifier = Modifier.selectableGroup()) {
        SystemDestinationRow(
            label = "No project",
            selected = selectedId == null,
            testTag = TestTags.PROJECT_PICKER_NONE,
            onSelect = { onSelect(null) },
        ) { isSelected, tint ->
            Icon(
                imageVector = if (isSelected) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }

        if (inboxProject != null) {
            val isSelected = selectedId == inboxProject.id
            SystemDestinationRow(
                label = "Inbox",
                selected = isSelected,
                testTag = TestTags.PROJECT_PICKER_INBOX,
                onSelect = { onSelect(inboxProject.id) },
            ) { _, tint ->
                ProjectIcon(
                    icon = inboxProject.icon,
                    tint = tint,
                    size = 24.dp,
                    contentDescription = null,
                )
            }
        }

        if (nodes.isEmpty() && selectedOutsideTree == null) {
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            SelectableText(
                text = "No projects yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.PROJECT_PICKER_EMPTY).padding(16.dp),
            )
        } else {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            if (selectedOutsideTree != null && nodes.isEmpty()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProjectItem(
                        project = selectedOutsideTree,
                        depth = 0,
                        isSelected = true,
                        onSelect = { onSelect(selectedOutsideTree.id) },
                    )
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (selectedOutsideTree != null) {
                        item(key = "selected_${selectedOutsideTree.id.value}") {
                            ProjectItem(
                                project = selectedOutsideTree,
                                depth = 0,
                                isSelected = true,
                                onSelect = { onSelect(selectedOutsideTree.id) },
                            )
                        }
                    }
                    items(nodes, key = { it.id.value }) { node ->
                        ProjectItem(
                            project = node.project,
                            depth = node.depth,
                            isSelected = selectedId == node.id,
                            onSelect = { onSelect(node.id) },
                        )
                    }
                }
            }
        }
    }
}

@Suppress("FunctionSignature")
@Composable
private fun SystemDestinationRow(
    label: String,
    selected: Boolean,
    testTag: String,
    onSelect: () -> Unit,
    leading: @Composable (Boolean, Color) -> Unit,
) {
    val tint =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .testTag(testTag)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading(selected, tint)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Suppress("FunctionSignature")
@Composable
private fun ProjectItem(
    project: Project,
    depth: Int,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val tint =
        if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
                .testTag(TestTags.projectPickerRow(project.id.value))
                .padding(start = INDENT_PER_LEVEL * depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectIcon(
            icon = project.icon,
            tint = tint,
            size = 24.dp,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = project.name,
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
