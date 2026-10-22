package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.BackTopAppBar
import com.singularity.todo.core.ui.components.ConfirmActionDialog
import com.singularity.todo.core.ui.components.DialogState
import com.singularity.todo.core.ui.components.DiscardChangesDialog
import com.singularity.todo.core.ui.components.ListPickerSheet
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.components.sheet.DatePickerSheet
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.selector.ConfigurableSelector
import com.singularity.todo.feature.agenda.domain.selector.SelectorOption
import com.singularity.todo.feature.agenda.domain.selector.SelectorTemplate
import com.singularity.todo.feature.agenda.domain.selector.typeDescription
import com.singularity.todo.feature.agenda.presentation.nav.AgendaNavigator
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewState
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Root composable for the saved agenda view display/edit/create screen.
 * Uses [koinViewModel] to obtain the [SavedAgendaViewModel] scoped to this nav entry.
 *
 * @param mode The runtime mode derived from the navigation route.
 * @param modeHint Informational label shown in the top bar ("Saved view", "Edit View", or "Create View").
 */
@Composable
fun SavedAgendaScreen(
    mode: SavedAgendaScreenMode,
    modeHint: String,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalAgendaNavigator.current
    val viewModel: SavedAgendaViewModel = koinViewModel { parametersOf(mode) }
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()

    SavedAgendaScreenBody(
        state = state,
        viewModel = viewModel,
        navigator = navigator,
        modeHint = modeHint,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedAgendaScreenBody(
    state: SavedAgendaViewState,
    viewModel: SavedAgendaViewModel,
    navigator: AgendaNavigator,
    modeHint: String,
    modifier: Modifier,
) {
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

    val dialogs = rememberDialogState<ActiveDialog>()
    var pendingTemplate by remember { mutableStateOf<SelectorTemplate?>(null) }

    LaunchedEffect(state) {
        if (state is SavedAgendaViewState.NotFound) {
            navigator.back()
        }
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { event: SavedAgendaEvent ->
            when (event) {
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

    DialogsHost(
        dialogs = dialogs,
        pendingTemplate = pendingTemplate,
        onPendingTemplateChanged = { pendingTemplate = it },
        onDeleteConfirmed = { viewModel.onIntent(SavedAgendaIntent.Delete) },
        onAddSection = { t, c, a -> addSection(viewModel, state, t, c, a) },
        onBack = { navigator.back() },
    )
}

// ─── Dialogs host ─────────────────────────────────────────────────────────────

@Composable
private fun DialogsHost(
    dialogs: DialogState<ActiveDialog>,
    pendingTemplate: SelectorTemplate?,
    onPendingTemplateChanged: (SelectorTemplate?) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onAddSection: (SelectorTemplate, Set<String>, List<SelectorOption>) -> Unit,
    onBack: () -> Unit,
) {
    ConfirmDeleteDialog(
        isVisible = dialogs.active == ActiveDialog.ConfirmDelete,
        onConfirm = {
            dialogs.dismiss()
            onDeleteConfirmed()
        },
        onDismiss = { dialogs.dismiss() },
    )

    ConfirmDiscardDialog(
        isVisible = dialogs.active == ActiveDialog.ConfirmDiscard,
        onDiscard = onBack,
        onKeepEditing = { dialogs.dismiss() },
    )

    AddSectionSheet(
        isVisible = dialogs.active == ActiveDialog.AddSection,
        onPendingTemplateChanged = onPendingTemplateChanged,
        onAddSection = onAddSection,
        onDismiss = { dialogs.dismiss() },
    )

    pendingTemplate?.let { template ->
        TemplateSheet(
            template = template,
            onConfirmed = { t, c, a ->
                onPendingTemplateChanged(null)
                onAddSection(t, c, a)
            },
            onDismiss = { onPendingTemplateChanged(null) },
        )
    }
}

// ─── Dialogs ─────────────────────────────────────────────────────────────────

@Composable
private fun ConfirmDeleteDialog(
    isVisible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (isVisible) {
        ConfirmActionDialog(
            title = "Delete view?",
            text = "This action cannot be undone.",
            confirmButtonText = "Delete",
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ConfirmDiscardDialog(
    isVisible: Boolean,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    if (isVisible) {
        DiscardChangesDialog(
            onDiscard = onDiscard,
            onKeepEditing = onKeepEditing,
        )
    }
}

// ─── Add Section sheet ───────────────────────────────────────────────────────

@Composable
private fun AddSectionSheet(
    isVisible: Boolean,
    onPendingTemplateChanged: (SelectorTemplate?) -> Unit,
    onAddSection: (SelectorTemplate, Set<String>, List<SelectorOption>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (isVisible) {
        ListPickerSheet<SelectorTemplate>(
            title = "Add section",
            onItemSelected = { template ->
                onDismiss()
                if (template.requiresParameters) {
                    onPendingTemplateChanged(template)
                } else {
                    onAddSection(template, emptySet(), emptyList())
                }
            },
            onDismiss = onDismiss,
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
}

// ─── Template parameter sheet ───────────────────────────────────────────────

@Composable
private fun TemplateSheet(
    template: SelectorTemplate,
    onConfirmed: (SelectorTemplate, Set<String>, List<SelectorOption>) -> Unit,
    onDismiss: () -> Unit,
) {
    when (template) {
        is SelectorTemplate.ByRegexp ->
            RegexpInputSheet(
                initialPattern = template.pattern,
                onConfirm = { pattern ->
                    onConfirmed(SelectorTemplate.ByRegexp(pattern), emptySet(), emptyList())
                },
                onDismiss = onDismiss,
            )

        is SelectorTemplate.ByDateRange ->
            DateRangePickerSheet(
                initialFrom = template.from,
                initialTo = template.to,
                onConfirm = { from, to ->
                    onConfirmed(SelectorTemplate.ByDateRange(from, to), emptySet(), emptyList())
                },
                onDismiss = onDismiss,
            )

        // SelectorParameterSheet reads tag/project options from their
        // repositories via koinInject. The local MultiSelectTemplateSheet that
        // used to live here returned emptyList() for ByTags/ByProjects — the
        // second step rendered a sheet with no options and no way to choose
        // one, so every parameterized template except priority/status was dead.
        else ->
            SelectorParameterSheet(
                template = template,
                onConfirm = { chosen, available, matchAll ->
                    val resolved =
                        (template as? SelectorTemplate.ByTags)
                            ?.copy(matchAll = matchAll) ?: template
                    onConfirmed(resolved, chosen, available)
                },
                onDismiss = onDismiss,
            )
    }
}

// ─── Regexp input sheet ──────────────────────────────────────────────────────

@Composable
private fun RegexpInputSheet(
    initialPattern: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val patternState = rememberTextFieldState(initialPattern)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.mapTestTagsAsResourceIds(),
        title = { Text("By text pattern") },
        text = {
            Column {
                SelectableText(
                    text = "Tasks whose title matches this regular expression (case-insensitive).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    state = patternState,
                    label = { Text("Pattern") },
                    placeholder = { Text("e.g. meeting|review") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    // AlertDialog is its own Android window — the app-root
                    // testTagsAsResourceId never reaches it. Without this the tag
                    // is invisible to Maestro and the flow has to select by label.
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(TestTags.AGENDA_REGEX_PATTERN_INPUT)
                            .mapTestTagsAsResourceIds(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(patternState.text.toString()) },
                enabled = patternState.text.isNotBlank(),
            ) {
                Text("Add section")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ─── Date range picker sheet ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerSheet(
    initialFrom: LocalDate?,
    initialTo: LocalDate?,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var fromDate by remember { mutableStateOf(initialFrom) }
    var toDate by remember { mutableStateOf(initialTo) }
    var stage by remember { mutableStateOf("from") }

    when (stage) {
        "from" ->
            DatePickerSheet(
                initialDate = fromDate,
                onDateSelected = { date ->
                    if (date != null) {
                        fromDate = date
                        stage = "to"
                    }
                },
                onDismiss = onDismiss,
            )

        "to" ->
            DatePickerSheet(
                initialDate = toDate,
                onDateSelected = { date ->
                    if (date != null) {
                        toDate = date
                        stage = "confirm"
                    }
                },
                onDismiss = { stage = "from" },
            )

        "confirm" -> {
            val f = fromDate
            val t = toDate
            if (f != null && t != null) {
                DateRangeConfirmDialog(
                    from = f,
                    to = t,
                    onConfirm = { onConfirm(f, t) },
                    onBack = { stage = "to" },
                )
            }
        }
    }
}

@Composable
private fun DateRangeConfirmDialog(
    from: LocalDate,
    to: LocalDate,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("By date range") },
        text = {
            Column {
                Text("From: $from")
                Text("To: $to")
                if (to < from) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "\"To\" must be on or after \"From\".",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = to >= from,
            ) {
                Text("Add section")
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) {
                Text("Back")
            }
        },
    )
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun addSection(
    viewModel: SavedAgendaViewModel,
    state: SavedAgendaViewState,
    template: SelectorTemplate,
    chosen: Set<String>,
    available: List<SelectorOption>,
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

/** Active overlay dialog. */
private sealed interface ActiveDialog {
    data object ConfirmDelete : ActiveDialog

    data object ConfirmDiscard : ActiveDialog

    data object AddSection : ActiveDialog
}
