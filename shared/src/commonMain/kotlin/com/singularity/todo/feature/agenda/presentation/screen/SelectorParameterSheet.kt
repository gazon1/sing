package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.sheet.MultiSelectItem
import com.singularity.todo.core.ui.components.sheet.MultiSelectSheet
import com.singularity.todo.feature.agenda.domain.selector.SelectorOption
import com.singularity.todo.feature.agenda.domain.selector.SelectorTemplate
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/**
 * Second step of "add section": choose the values a [SelectorTemplate] needs.
 *
 * Reads its options the same way the copy-to-profile picker reads profiles — a
 * repository injected at the call site with `koinInject`, not routed through the
 * ViewModel. The ViewModel owns the draft; a transient modal's contents have no
 * business living in state that survives a rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectorParameterSheet(
    template: SelectorTemplate,
    onConfirm: (chosen: Set<String>, available: List<SelectorOption>, matchAll: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val tagRepo: TagsRepository = koinInject()
    val projectRepo: ProjectsRepository = koinInject()
    val tags by remember(template) { tagRepo.observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by remember(template) { projectRepo.observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val options = remember(template, tags, projects) { selectorOptionsFor(template, tags, projects) }
    val selected = remember(template) { mutableStateListOf<String>() }
    // ByTags is the one template whose *semantics* — not just its values — are
    // the user's choice: any of the tags, or all of them. Engine-only before,
    // so a "tasks with all three tags" view could not be built from the editor.
    val matchAll = remember(template) { mutableStateOf(false) }

    MultiSelectSheet(
        title = template.label,
        items = options.map { option ->
            MultiSelectItem(
                key = option.id,
                label = option.label,
                testTag = TestTags.agendaSelectorOption(option.id),
            )
        },
        selectedKeys = selected.toSet(),
        onToggle = { id ->
            if (id in selected) selected.remove(id) else selected.add(id)
        },
        // Disabled until something is chosen: an empty selection resolves to no
        // section at all, and a button that silently does nothing is exactly
        // the failure this step exists to prevent.
        onConfirm = { onConfirm(selected.toSet(), options, matchAll.value) },
        onDismiss = onDismiss,
        confirmLabel = "Add section",
        confirmTestTag = TestTags.SAVED_AGENDA_ADD_SECTION_CONFIRM,
        emptyMessage = "No ${template.label.lowercase()} available yet",
        footer = if (template is SelectorTemplate.ByTags) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(
                        checked = matchAll.value,
                        onCheckedChange = { checked -> matchAll.value = checked },
                        modifier = Modifier.testTag(TestTags.AGENDA_TAG_MATCH_ALL),
                    )
                    Text(
                        text = "Match all of these tags",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            null
        },
    )
}

/** The values available for [template], in the order the picker shows them. */
internal fun selectorOptionsFor(
    template: SelectorTemplate,
    tags: List<Tag> = emptyList(),
    projects: List<Project> = emptyList(),
): List<SelectorOption> = when (template) {
    is SelectorTemplate.ByTags -> tags.map { SelectorOption(it.id.value, it.name) }

    is SelectorTemplate.ByProjects -> {
        val live = projects.filterNot { it.isDeleted }
        live.map { SelectorOption(it.id.value, it.name) }
    }

    is SelectorTemplate.ByPriority -> TaskPriority.entries.map { SelectorOption(it.name, it.name) }

    is SelectorTemplate.ByStatus -> TaskStatus.entries.map { SelectorOption(it.name, it.name) }

    // Fixed templates never open this sheet; the catalogue is the fallback so a
    // future parameterized type cannot resolve to a silently empty picker.
    else -> emptyList()
}
