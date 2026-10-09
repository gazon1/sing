package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.BackTopAppBar
import com.singularity.todo.core.ui.components.ConfirmActionDialog
import com.singularity.todo.core.ui.components.DiscardChangesDialog
import com.singularity.todo.core.ui.components.ListPickerSheet
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.selector.ConfigurableSelector
import com.singularity.todo.feature.agenda.domain.selector.SelectorOption
import com.singularity.todo.feature.agenda.domain.selector.SelectorTemplate
import com.singularity.todo.feature.agenda.domain.selector.typeDescription
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewState
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

/** Active overlay dialog. */
private sealed interface ActiveDialog {
    data object ConfirmDelete : ActiveDialog
    data object ConfirmDiscard : ActiveDialog
    data object AddSection : ActiveDialog
}
