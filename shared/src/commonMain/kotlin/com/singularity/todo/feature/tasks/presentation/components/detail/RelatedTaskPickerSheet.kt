package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.search.FreeTextRule
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.search.TextMatchPlan
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

internal const val RELATED_TASK_PICKER_RESULT_LIMIT = 30
internal const val RELATED_TASK_PICKER_FETCH_LIMIT = 50
internal const val RELATED_TASK_PICKER_MAX_FETCH_LIMIT = 500
private val PICKER_VERBS = listOf(DependencyVerb.BLOCKS, DependencyVerb.FOLLOWS_UP)

private sealed interface PickerSearchState {
    data object Idle : PickerSearchState

    data object Loading : PickerSearchState

    data class Results(val tasks: List<Task>) : PickerSearchState

    data object NoResults : PickerSearchState

    data class Error(val message: String) : PickerSearchState
}

/**
 * Searches for tasks to relate, excluding [taskId] and [existingRelationIds].
 *
 * Uses bounded fetch expansion because exclusions are applied after reading from the
 * index and the first page may be fully excluded.
 */
internal suspend fun searchRelatedTaskCandidates(
    rawQuery: String,
    taskId: TaskId,
    existingRelationIds: Set<TaskId>,
    searchIndex: SearchTextIndex,
    resultLimit: Int = RELATED_TASK_PICKER_RESULT_LIMIT,
    initialFetchLimit: Int = RELATED_TASK_PICKER_FETCH_LIMIT,
    maxFetchLimit: Int = RELATED_TASK_PICKER_MAX_FETCH_LIMIT,
): List<Task> {
    val q = rawQuery.trim()
    val query = relatedTaskSearchQuery(q)
    if (query == null) return emptyList()

    var fetchLimit = initialFetchLimit.coerceAtLeast(resultLimit)
    val cap = maxFetchLimit.coerceAtLeast(fetchLimit)
    var result = emptyList<Task>()
    while (true) {
        val batch = searchIndex.recentTaskTitles(query, fetchLimit)
        val filtered = batch.asSequence().filter { it.id != taskId && it.id !in existingRelationIds }.toList()
        val done = filtered.size >= resultLimit || batch.size < fetchLimit || fetchLimit >= cap
        if (done) {
            result = filtered.take(resultLimit)
            break
        }
        fetchLimit = (fetchLimit * 2).coerceAtMost(cap)
    }
    return result
}

private fun relatedTaskSearchQuery(rawQuery: String): TextMatchQuery? {
    if (rawQuery.isBlank()) return null
    val rule = FreeTextRule.singleTerm(rawQuery)
    val query =
        when (rule.plan(SearchMode.Word)) {
            is TextMatchPlan.Narrowed -> rule.indexQuery(SearchMode.Word)
            TextMatchPlan.BroadScan -> null
        }
    return query?.takeUnless { it == TextMatchQuery.EMPTY }
}

/**
 * Spacious single-add picker for finding and adding one related task.
 *
 * Searches on demand via [SearchTextIndex] (bounded, FTS Word mode) and excludes
 * [taskId] and [existingRelationIds]. Results show task title and project name
 * when available. The user selects one candidate, chooses a relation verb, and
 * confirms to add exactly that relation.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun RelatedTaskPickerSheet(
    taskId: TaskId,
    existingRelationIds: ImmutableSet<TaskId>,
    onAdd: (TaskId, DependencyVerb) -> Unit,
    onDismiss: () -> Unit,
    searchIndex: SearchTextIndex = koinInject(),
    projectsRepo: ProjectsRepository = koinInject(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val queryState = rememberTextFieldState()
    var selectedId by remember { mutableStateOf<TaskId?>(null) }
    var selectedVerb by remember { mutableStateOf(DependencyVerb.BLOCKS) }
    var searchState: PickerSearchState by remember { mutableStateOf(PickerSearchState.Idle) }

    val projects by projectsRepo.observeAllIncludingClosed().collectAsStateWithLifecycle(initialValue = emptyList())
    val projectsById = remember(projects) { projects.associateBy(Project::id) }
    val excludedIds = remember(existingRelationIds) { existingRelationIds.toSet() }

    LaunchedEffect(queryState, excludedIds, taskId) {
        snapshotFlow { queryState.text.toString() }
            .debounce(300.milliseconds)
            .distinctUntilChanged()
            .collectLatest { raw ->
                selectedId = null
                selectedVerb = DependencyVerb.BLOCKS
                if (raw.isBlank()) {
                    searchState = PickerSearchState.Idle
                } else {
                    searchState = PickerSearchState.Loading
                    searchState =
                        runRelatedTaskSearch(
                            rawQuery = raw,
                            taskId = taskId,
                            existingRelationIds = excludedIds,
                            searchIndex = searchIndex,
                        )
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.mapTestTagsAsResourceIds(),
    ) {
        RelatedTaskPickerContent(
            queryState = queryState,
            searchState = searchState,
            projectsById = projectsById,
            selectedId = selectedId,
            selectedVerb = selectedVerb,
            onDismiss = onDismiss,
            onSelect = { id ->
                selectedId = id
                selectedVerb = DependencyVerb.BLOCKS
            },
            onVerbChange = { selectedVerb = it },
            onAdd = {
                val id = selectedId ?: return@RelatedTaskPickerContent
                onAdd(id, selectedVerb)
            },
        )
    }
}

private suspend fun runRelatedTaskSearch(
    rawQuery: String,
    taskId: TaskId,
    existingRelationIds: Set<TaskId>,
    searchIndex: SearchTextIndex,
): PickerSearchState {
    val tasks =
        runCatchingCancellable {
            searchRelatedTaskCandidates(rawQuery, taskId, existingRelationIds, searchIndex)
        }.getOrElse { error ->
            return PickerSearchState.Error(error.message ?: "Search failed")
        }
    return if (tasks.isEmpty()) PickerSearchState.NoResults else PickerSearchState.Results(tasks)
}

@Composable
private fun RelatedTaskPickerContent(
    queryState: TextFieldState,
    searchState: PickerSearchState,
    projectsById: Map<ProjectId, Project>,
    selectedId: TaskId?,
    selectedVerb: DependencyVerb,
    onDismiss: () -> Unit,
    onSelect: (TaskId) -> Unit,
    onVerbChange: (DependencyVerb) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .imePadding()
                .padding(horizontal = 16.dp),
    ) {
        RelatedTaskPickerHeader(onDismiss = onDismiss)
        Spacer(Modifier.height(8.dp))
        RelatedTaskPickerSearchField(queryState = queryState)
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PickerResults(
                searchState = searchState,
                projectsById = projectsById,
                selectedId = selectedId,
                selectedVerb = selectedVerb,
                onSelect = onSelect,
                onVerbChange = onVerbChange,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAdd,
            enabled = selectedId != null,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag(TestTags.RELATED_TASK_PICKER_ADD),
        ) {
            Text("Add related task")
        }
    }
}

@Composable
private fun RelatedTaskPickerHeader(onDismiss: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Add related task",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun RelatedTaskPickerSearchField(queryState: TextFieldState) {
    OutlinedTextField(
        state = queryState,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RELATED_TASK_PICKER_SEARCH),
        placeholder = { Text("Search tasks") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (queryState.text.isNotEmpty()) {
                IconButton(
                    onClick = { queryState.clearText() },
                    modifier = Modifier.testTag(TestTags.RELATED_TASK_PICKER_CLEAR),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear query")
                }
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
private fun BoxScope.PickerResults(
    searchState: PickerSearchState,
    projectsById: Map<ProjectId, Project>,
    selectedId: TaskId?,
    selectedVerb: DependencyVerb,
    onSelect: (TaskId) -> Unit,
    onVerbChange: (DependencyVerb) -> Unit,
) {
    when (searchState) {
        PickerSearchState.Idle -> {
            PickerMessage("Type to find a task to relate")
        }

        PickerSearchState.Loading -> {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        PickerSearchState.NoResults -> {
            PickerMessage("No matching tasks")
        }

        is PickerSearchState.Error -> {
            PickerError(message = searchState.message)
        }

        is PickerSearchState.Results -> {
            LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(searchState.tasks, key = { it.id.value }) { task ->
                    val projectName = task.projectId?.let { projectsById[it]?.name }
                    val selected = selectedId == task.id
                    RelatedTaskCandidateRow(
                        task = task,
                        projectName = projectName,
                        selected = selected,
                        selectedVerb = if (selected) selectedVerb else null,
                        onSelect = { onSelect(task.id) },
                        onVerbChange = onVerbChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun PickerError(message: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Edit or clear the query to try again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RelatedTaskCandidateRow(
    task: Task,
    projectName: String?,
    selected: Boolean,
    selectedVerb: DependencyVerb?,
    onSelect: () -> Unit,
    onVerbChange: (DependencyVerb) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TestTags.relatedTaskCandidate(task.id.value))
                .clickable(onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = task.title.ifBlank { "(Untitled)" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (!projectName.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = projectName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected && selectedVerb != null) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PICKER_VERBS.forEach { verb ->
                    FilterChip(
                        selected = selectedVerb == verb,
                        onClick = { onVerbChange(verb) },
                        label = { Text(if (verb == DependencyVerb.BLOCKS) "Blocks" else "Follows up") },
                    )
                }
            }
        }
    }
}
