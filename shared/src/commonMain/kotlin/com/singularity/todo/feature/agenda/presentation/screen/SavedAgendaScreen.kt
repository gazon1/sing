package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.singularity.todo.core.ui.components.sheet.MultiSelectItem
import com.singularity.todo.core.ui.components.sheet.MultiSelectSheet
import com.singularity.todo.feature.agenda.domain.selector.ConfigurableSelector
import com.singularity.todo.feature.agenda.domain.selector.SelectorOption
import com.singularity.todo.feature.agenda.domain.selector.SelectorTemplate
import com.singularity.todo.feature.agenda.domain.selector.typeDescription
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tags.TagsRepository
import org.koin.compose.koinInject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.BackTopAppBar
import com.singularity.todo.core.ui.components.ConfirmActionDialog
import com.singularity.todo.core.ui.components.DiscardChangesDialog
import com.singularity.todo.core.ui.components.ListPickerSheet
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.presentation.components.SectionEditorCard
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.nav.PreviewAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.Draft
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewState
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Root composable for the saved agenda view display/edit/create screen.
 * Uses [koinViewModel] to obtain the [SavedAgendaViewModel] scoped to this nav entry.
 *
 * @param mode The runtime mode derived from the navigation route.
 * @param modeHint Informational label shown in the top bar ("Saved view", "Edit View", or "Create View").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedAgendaScreen(mode: SavedAgendaScreenMode, modeHint: String, modifier: Modifier = Modifier) {
    val navigator = LocalAgendaNavigator.current

    val viewModel: SavedAgendaViewModel = koinViewModel { parametersOf(mode) }
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()

    // In Results mode, render AgendaScreen directly — no edit chrome needed.
    if (state is SavedAgendaViewState.Results) {
        val results = state as SavedAgendaViewState.Results
        BackTopAppBar(
            title = results.viewName,
            onBack = { navigator.back() },
            modifier = modifier,
        ) { paddingValues ->
            AgendaScreen(
                definition = results.definition,
                modifier = Modifier.padding(paddingValues),
            )
        }
        return
    }

    // Dialog state: null = no dialog/sheet, else the active dialog
    val dialogs = rememberDialogState<ActiveDialog>()

    // A parameterized section type is chosen before its values are known, so the
    // second step of "add section" outlives the sheet that started it.
    var pendingTemplate by remember { mutableStateOf<SelectorTemplate?>(null) }

    // Auto-pop to previous screen when entering NotFound state
    LaunchedEffect(state) {
        if (state is SavedAgendaViewState.NotFound) {
            navigator.back()
        }
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { event: SavedAgendaEvent ->
            when (event) {
                // The contract (SavedAgendaCreateFlowTest): saving leaves the editor —
                // the write resolved, so pop back instead of parking on a dialog.
                is SavedAgendaEvent.SaveSuccess -> Notification.NavigateBack

                is SavedAgendaEvent.DeleteSuccess -> Notification.NavigateBack

                is SavedAgendaEvent.ShowError -> Notification.Error(event.message)
            }
        },
        onNavigateBack = { navigator.back() },
    )

    BackTopAppBar(
        title = modeHint,
        onBack = {
            val editing = state as? SavedAgendaViewState.Editing
            if (editing != null && editing.draft.isDirty) {
                dialogs.show(ActiveDialog.ConfirmDiscard)
            } else {
                navigator.back()
            }
        },
        modifier = modifier,
    ) { paddingValues ->
        SavedAgendaContent(
            state = state,
            onIntent = viewModel::onIntent,
            onRequestDelete = { dialogs.show(ActiveDialog.ConfirmDelete) },
            onRequestAddSection = { dialogs.show(ActiveDialog.AddSection) },
            modifier = Modifier.padding(paddingValues),
        )
    }

    // ConfirmDelete dialog
    if (dialogs.active == ActiveDialog.ConfirmDelete) {
        ConfirmActionDialog(
            title = "Delete view?",
            text = "This action cannot be undone.",
            confirmButtonText = "Delete",
            onConfirm = {
                dialogs.dismiss()
                viewModel.onIntent(SavedAgendaIntent.Delete)
            },
            onDismiss = { dialogs.dismiss() },
        )
    }

    // ConfirmDiscard dialog
    if (dialogs.active == ActiveDialog.ConfirmDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                dialogs.dismiss()
                navigator.back()
            },
            onKeepEditing = { dialogs.dismiss() },
        )
    }

    /**
     * Builds a [Section] from a template plus the values the user chose, and
     * hands it to the ViewModel.
     *
     * A `null` resolution (nothing valid selected) adds nothing rather than
     * adding a section that matches no task — see [SelectorTemplate.resolve].
     */
    fun addSection(
        template: SelectorTemplate,
        chosen: Set<String>,
        // The option list the picker actually showed. It has to be the same one
        // the resolver validates against — re-deriving it here would use empty
        // tag/project lists, drop every chosen id as "unknown", resolve to null
        // and silently add nothing.
        available: List<SelectorOption> = emptyList(),
    ) {
        val current = state as? SavedAgendaViewState.Editing ?: return
        val selector = ConfigurableSelector(template, available).resolve(chosen) ?: return
        val order = current.draft.sections.size
        viewModel.onIntent(
            SavedAgendaIntent.SectionAdded(
                Section(
                    id = "section_$order",
                    name = selector.typeDescription,
                    order = order,
                    selector = selector,
                ),
                order,
            ),
        )
    }

    // Add section, step 1: pick the section *type*.
    if (dialogs.active == ActiveDialog.AddSection) {
        ListPickerSheet<SelectorTemplate>(
            title = "Add section",
            onItemSelected = { template ->
                dialogs.dismiss()
                // Parameterless types are complete on their own. The rest need
                // values first: `Selector.Tags(emptySet())` matches no task, so
                // adding one now would create a section that can never have
                // content.
                if (template.requiresParameters) {
                    pendingTemplate = template
                } else {
                    addSection(template, emptySet(), selectorOptionsFor(template))
                }
            },
            onDismiss = { dialogs.dismiss() },
        ) {
            SelectorTemplate.catalogue.forEach { template ->
                item(
                    label = template.label,
                    key = template,
                    testTag = TestTags.agendaSectionTemplate(template.label),
                )
            }
        }
    }

    // Add section, step 2: pick the values a parameterized type needs.
    pendingTemplate?.let { template ->
        SelectorParameterSheet(
            template = template,
            onConfirm = { chosen, available, matchAll ->
                pendingTemplate = null
                // Rebuild the template with the semantics the user chose. Any
                // other type returns `this`, so the common path is unchanged.
                val resolved = (template as? SelectorTemplate.ByTags)
                    ?.copy(matchAll = matchAll) ?: template
                addSection(resolved, chosen, available)
            },
            onDismiss = { pendingTemplate = null },
        )
    }
}

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
private fun SelectorParameterSheet(
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
private fun selectorOptionsFor(
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

/** Active overlay dialog. */
private sealed interface ActiveDialog {
    data object ConfirmDelete : ActiveDialog
    data object ConfirmDiscard : ActiveDialog
    data object AddSection : ActiveDialog
}

/**
 * Content composable for the saved agenda view edit/create screen.
 * Stateless — receives [SavedAgendaViewState] and emits callbacks.
 * Used by [SavedAgendaScreen] (production) and preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedAgendaContent(
    state: SavedAgendaViewState,
    onIntent: (SavedAgendaIntent) -> Unit,
    onRequestDelete: () -> Unit,
    onRequestAddSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        SavedAgendaViewState.Loading -> {
            LoadingIndicator(modifier = modifier.fillMaxSize())
        }

        SavedAgendaViewState.NotFound -> {
            Column(
                modifier = modifier.fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "View not found",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // Results state is handled at the parent SavedAgendaScreen level —
        // this branch exists only to satisfy exhaustiveness and should never be reached.
        is SavedAgendaViewState.Results -> {
            LoadingIndicator(modifier = modifier.fillMaxSize())
        }

        is SavedAgendaViewState.Editing -> {
            val keyboardController = LocalSoftwareKeyboardController.current
            val listState = rememberLazyListState()

            Column(modifier = modifier.fillMaxSize()) {
                // A single LazyColumn owns scrolling. Nesting a LazyColumn inside a
                // Column(verticalScroll()) would propagate unbounded height constraints
                // and crash at measure time. Header (name field + section header / empty
                // state) are emitted via item {} so the sections list is the only
                // itemsIndexed block; the action row is a sibling below, not an item.
                LazyColumn(
                    // weight, not fillMaxSize: inside a Column, fillMaxSize resolves
                    // against the *whole* viewport and pushes the action row off-screen,
                    // which is the very bug this layout change exists to fix. weight(1f)
                    // takes exactly the height the action row did not claim.
                    modifier = Modifier
                        .weight(1f)
                        .padding(24.dp)
                        .imePadding(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = "name_field") {
                        OutlinedTextField(
                            value = state.draft.name,
                            onValueChange = { onIntent(SavedAgendaIntent.NameChanged(it)) },
                            label = { Text("View name") },
                            placeholder = { Text("My saved view") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.SAVED_AGENDA_NAME_INPUT),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() },
                            ),
                            enabled = !state.isSaving,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    when {
                        state.decodeError -> {
                            item(key = "decode_error") {
                                Text(
                                    text = "Unable to decode sections",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        state.draft.sections.isNotEmpty() -> {
                            item(key = "sections_header") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Sections",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = onRequestAddSection) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add")
                                    }
                                }
                            }
                            itemsIndexed(
                                items = state.draft.sections,
                                key = { index, section -> "${section.name}#${section.order}#$index" },
                            ) { index, section ->
                                SectionEditorCard(
                                    section = section,
                                    index = index,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < state.draft.sections.lastIndex,
                                    onMoveUp = {
                                        val moved = state.draft.sections.toMutableList()
                                        moved.add(index - 1, moved.removeAt(index))
                                        onIntent(SavedAgendaIntent.SectionsReordered(moved))
                                    },
                                    onMoveDown = {
                                        val moved = state.draft.sections.toMutableList()
                                        moved.add(index + 1, moved.removeAt(index))
                                        onIntent(SavedAgendaIntent.SectionsReordered(moved))
                                    },
                                    onDelete = { onIntent(SavedAgendaIntent.SectionRemoved(index)) },
                                )
                            }
                        }

                        else -> {
                            item(key = "no_sections") {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = "No sections",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(onClick = onRequestAddSection) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add first section")
                                    }
                                }
                            }
                        }
                    }

                    item(key = "actions_spacer") {
                        // The action row is no longer the last item: it is pinned
                        // below the list, so the scrolling area only needs enough
                        // trailing room that the final section is not flush against
                        // it. Keeping the buttons in the LazyColumn put them below
                        // the fold on a 1024x768 window with a full section list —
                        // the form's primary action unreachable without scrolling.
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                SavedAgendaActionRow(
                    state = state,
                    onSave = { onIntent(SavedAgendaIntent.Save) },
                    onRequestDelete = onRequestDelete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .imePadding(),
                )
            }
        }
    }
}

/**
 * The editor's Save / Delete row, pinned to the bottom of the viewport.
 *
 * Split out of [SavedAgendaContent] so the scrolling [LazyColumn] and the
 * always-visible action row are siblings rather than the actions being the
 * last item in the scroll — a single nested scrolling container is the one
 * arrangement that measures correctly, and the alternative (sticky header
 * inside the list) is not expressible with `LazyColumn` items.
 */
@Composable
private fun SavedAgendaActionRow(
    state: SavedAgendaViewState.Editing,
    onSave: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Button(
            onClick = onSave,
            enabled = state.canSave && !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.SAVED_AGENDA_SAVE_BUTTON),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(18.dp),
                )
            } else {
                Text("Save")
            }
        }
        // Delete button only shown in Edit mode (view != null)
        if (state.view != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRequestDelete,
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.SAVED_AGENDA_DELETE_BUTTON),
            ) {
                Text("Delete view")
            }
        }
    }
}

// ===== Previews =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaContentLoadingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaContent(
            state = SavedAgendaViewState.Loading,
            onIntent = {},
            onRequestDelete = {},
            onRequestAddSection = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaContentEditingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaContent(
            state = SavedAgendaViewState.Editing(
                view = SavedAgendaView(
                    id = SavedAgendaViewId("v1"),
                    userId = UserId("u1"),
                    name = "My Work Setup",
                    sectionsJson = """{"sections":[{"type":"tasks"},{"type":"notes"}]}""",
                    createdAt = kotlin.time.Instant.fromEpochSeconds(1784253600),
                    updatedAt = kotlin.time.Instant.fromEpochSeconds(1785496200),
                ),
                draft = Draft(
                    name = "My Work Setup",
                    sections = listOf(
                        Section("today", "Today", 0, Selector.DateBucket(RelativeBucket.Today)),
                        Section("overdue", "Overdue", 1, Selector.DateBucket(RelativeBucket.Overdue)),
                    ),
                    originalName = "My Work Setup",
                    originalSections = emptyList(),
                    initialized = true,
                ),
                sectionCount = 2,
                isSaving = false,
                decodeError = false,
            ),
            onIntent = {},
            onRequestDelete = {},
            onRequestAddSection = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaContentSavingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaContent(
            state = SavedAgendaViewState.Editing(
                view = SavedAgendaView(
                    id = SavedAgendaViewId("v1"),
                    userId = UserId("u1"),
                    name = "My Work Setup",
                    sectionsJson = """{"sections":[]}""",
                    createdAt = kotlin.time.Instant.fromEpochSeconds(1784253600),
                    updatedAt = kotlin.time.Instant.fromEpochSeconds(1785496200),
                ),
                draft = Draft(
                    name = "My Work Setup",
                    sections = emptyList(),
                    originalName = "My Work Setup",
                    originalSections = emptyList(),
                    initialized = true,
                ),
                sectionCount = 0,
                isSaving = true,
                decodeError = false,
            ),
            onIntent = {},
            onRequestDelete = {},
            onRequestAddSection = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaContentNotFoundPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaContent(
            state = SavedAgendaViewState.NotFound,
            onIntent = {},
            onRequestDelete = {},
            onRequestAddSection = {},
        )
    }
}
