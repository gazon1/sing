package com.singularity.todo.detekt

/**
 * The composables that still take a raw collection, and why.
 *
 * This is a register of debt, not an allowance. Every entry names a
 * `@Composable` whose parameter is a raw `List` / `Set` / `Map`, so the Compose
 * compiler compares that parameter by identity and the composable recomposes
 * whenever its parent does.
 *
 * ## Why the list exists at all
 *
 * The alternative was to convert all of them now. Each conversion is a
 * signature change plus every call site, and each one changes what the
 * compiler compares: a *stable* parameter is compared by `equals`, which over a
 * collection is a deep walk. That is the right trade for a leaf — one task's
 * tags — and the wrong one for a screen that holds a month of entries. The
 * conversions that were worth making are made; the rest are here, named, so
 * that the debt is visible and every *new* raw collection parameter is a
 * finding.
 *
 * ## How it shrinks
 *
 * An entry disappears when the composable's parameter becomes persistent
 * (`ImmutableList` / `ImmutableSet` / `ImmutableMap`) and the value is built at
 * the boundary. The measurement to check the result is the Compose compiler's
 * own report:
 *
 * ```
 * ./gw :shared:compileKotlinJvm -PcomposeStabilityReport=true
 * ```
 *
 * The list was measured on 2026-10-24, after the stability contract and the
 * conversion of the hot states. The values are the parameters each entry
 * still carries — every parameter, not the first one: an entry that names
 * only some of its raw parameters would let the others through unnoticed.
 *
 * @see NoUnstableCollectionParamRule
 * @see 2026-10-22-compose-stability-contract
 */
internal object ComposeCollectionExemptions {

    /**
     * The known sites: a composable's qualified name, and the raw collection
     * parameters it still declares.
     */
    val KNOWN: Map<String, String> = mapOf(
        "com.singularity.todo.core.ui.components.BlockedBulkCompletionDialog" to "blockedTaskTitles: List<String>",
        "com.singularity.todo.core.ui.components.BlockedCompletionDialog" to "blockerTitles: List<String>",
        "com.singularity.todo.core.ui.components.sheet.ListPickerSheet" to "items: List<ListPickerItem<T>>",
        "com.singularity.todo.core.ui.components.sheet.MonthGrid" to "selected: Set<LocalDate>",
        "com.singularity.todo.core.ui.components.sheet.MultiDatePickerSheet" to "initialDates: Set<LocalDate>",
        "com.singularity.todo.core.ui.components.sheet.MultiSelectSheet" to
            "items: List<MultiSelectItem<T>>; selectedKeys: Set<T>",
        "com.singularity.todo.core.ui.menu.ComposeTopMenuBar" to "entries: List<MenuNode>",
        "com.singularity.todo.core.ui.menu.ContextMenuHost" to "entries: List<MenuNode>",
        "com.singularity.todo.core.ui.menu.MenuPanel" to "entries: List<MenuNode>",
        "com.singularity.todo.feature.agenda.presentation.components.ReorderableSectionList" to
            "sections: List<Section>",
        "com.singularity.todo.feature.agenda.presentation.screen.AgendaList" to "sections: List<RenderedSection>",
        "com.singularity.todo.feature.ai.chat.ChatMessagesList" to "messages: List<ChatMessage>",
        "com.singularity.todo.feature.attachments.components.AttachmentsSheet" to "attachments: List<Attachment>",
        "com.singularity.todo.feature.attachments.viewer.ViewerMessage" to "actions: List<ViewerAction>",
        "com.singularity.todo.feature.calendar.presentation.components.calendar.HourRow" to
            "dates: List<LocalDate>; tasksByDate: Map<LocalDate, List<CalendarTaskUi>>",
        "com.singularity.todo.feature.calendar.presentation.components.calendar.MonthGridPage" to
            "tasksByDate: Map<LocalDate, List<CalendarTaskUi>>; " +
            "selectedDates: Set<LocalDate>?",
        "com.singularity.todo.feature.calendar_sync.presentation.CalendarAppPicker" to
            "availableApps: List<CalendarAppInfo>",
        "com.singularity.todo.feature.checklist.components.ChecklistEditorSheet" to "items: List<ChecklistItem>",
        "com.singularity.todo.feature.notes.presentation.screen.BacklinksSheet" to "backlinks: List<Note>",
        "com.singularity.todo.feature.notes.presentation.screen.NoteEditorScreenContent" to
            "pendingProposals: List<AiProposal>?; proposalFailures: Map<ProposalItemId, String>",
        "com.singularity.todo.feature.notes.presentation.screen.NoteList" to
            "allNotes: List<Note>; pinned: List<Note>; unpinned: List<Note>; " +
            "templates: List<Note>; dailyNotes: List<Note>; selectedIds: Set<NoteId>",
        "com.singularity.todo.feature.pomodoro.PomodoroContentPreview" to "tasks: List<Task>?",
        "com.singularity.todo.feature.pomodoro.TaskChipRow" to "tasks: List<Task>",
        "com.singularity.todo.feature.profile.ProfileListContent" to "profiles: List<Profile>",
        "com.singularity.todo.feature.projects.presentation.components.ChildProjectsSheet" to "children: List<Project>",
        "com.singularity.todo.feature.projects.presentation.components.ConfirmBlockedCloseDialog" to
            "blockedTaskTitles: List<String>",
        "com.singularity.todo.feature.projects.presentation.components.InheritedTagGroupsSheet" to
            "options: List<TagGroupOption>; currentIds: Set<TagGroupId>",
        "com.singularity.todo.feature.projects.presentation.components.ParentPickerSheet" to
            "options: List<ParentOption>",
        "com.singularity.todo.feature.projects.presentation.components.ProjectDetailSheetsHost" to
            "parentOptions: List<ParentOption>; tagGroups: List<TagGroupOption>; " +
            "inheritedTagGroupIds: Set<TagGroupId>",
        "com.singularity.todo.feature.projects.presentation.screen.AddExistingTaskPopup" to "tasks: List<Task>",
        "com.singularity.todo.feature.projects.presentation.screen.ProjectBodySection" to "availableTasks: List<Task>",
        "com.singularity.todo.feature.projects.presentation.screen.ProjectDetailQuickAddInput" to
            "availableTasks: List<Task>",
        "com.singularity.todo.feature.proposals.presentation.components.ProposalCardsSection" to
            "proposals: List<AiProposal>; failures: Map<ProposalItemId, String>",
        "com.singularity.todo.feature.search.SimpleResultCard" to "matchRanges: List<TextMatchRange>?",
        "com.singularity.todo.feature.search.presentation.PriorityChips" to "selected: Set<TaskPriority>",
        "com.singularity.todo.feature.search.presentation.SavedSearchesRow" to "savedSearches: List<SavedSearch>",
        "com.singularity.todo.feature.search.presentation.StateChips" to "selected: Set<TaskStatus>?",
        "com.singularity.todo.feature.settings.SettingsContent" to
            "previewOverrides: Map<SettingsTab, Function2<Composer, Int, Unit>>?",
        "com.singularity.todo.feature.statistics.InsightsStackedBarChart" to "buckets: List<DayInsightsBucket>",
        "com.singularity.todo.feature.statistics.ProjectBreakdown" to "projectBuckets: List<ProjectInsightsBucket>",
        "com.singularity.todo.feature.statistics.TasksBarChart" to "buckets: List<DayBucket>",
        "com.singularity.todo.feature.tags.TagList" to "tags: List<Tag>",
        "com.singularity.todo.feature.tags.components.TagsPickerSheet" to "selectedIds: List<TagId>",
        "com.singularity.todo.feature.tags.presentation.screen.TagGroupList" to "groups: List<TagGroup>",
        "com.singularity.todo.feature.tasks.presentation.components.DefaultTaskCardBody" to
            "matchRanges: List<TextMatchRange>?",
        "com.singularity.todo.feature.tasks.presentation.components.TaskCard" to "matchRanges: List<TextMatchRange>?",
        "com.singularity.todo.feature.tasks.presentation.components.TaskText" to "matchRanges: List<TextMatchRange>?",
        "com.singularity.todo.feature.tasks.presentation.components.detail.BacklinkSection" to "items: List<String>",
        "com.singularity.todo.feature.tasks.presentation.components.detail.LinkedBacklinksCard" to
            "linkedNotes: List<Note>; linkedTasks: List<Task>",
        "com.singularity.todo.feature.tasks.presentation.components.detail.LogbookLoadedCard" to
            "entries: List<LogbookEntry>",
        "com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailProposalSection" to
            "proposals: List<AiProposal>; failures: Map<ProposalItemId, String>",
        "com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorContent" to
            "dueDates: List<LocalDate>?; tags: List<TagId>?; blockedBy: Set<TaskId>?; " +
            "availableTasks: List<Task>?; menuItems: List<TaskEditorMenuItem>",
        "com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorDueDateRow" to
            "dueDates: List<LocalDate>?",
        "com.singularity.todo.feature.tasks.presentation.components.detail.WeekdayEditor" to "selected: Set<Int>",
        "com.singularity.todo.feature.tasks.presentation.components.list.TaskFilterChips" to
            "counts: Map<TaskStatus, Int>?",
        // The sites below arrived on `main` after the register was measured. The
        // rule found them the first time it actually ran: it had been mute,
        // because every finding it produced was marked as suppressed.
        "com.singularity.todo.core.ui.onboarding.rememberSpotlightTour" to
            "steps: List<SpotlightStep>",
        "com.singularity.todo.core.ui.text.highlightedText" to
            "ranges: List<TextMatchRange>",
        "com.singularity.todo.feature.ai.chat.AgentActivitySection" to
            "plan: List<AgentPlanStep>; calls: List<AgentToolCall>",
        "com.singularity.todo.feature.ai.ui.AgentPlanCard" to
            "steps: List<AgentPlanStep>",
        "com.singularity.todo.feature.nav.toDecoratedEntries.Nav3State" to
            "entryDecorators: List<NavEntryDecorator<NavKey>>",
        "com.singularity.todo.feature.proposals.presentation.components.ProposalCard" to
            "failures: Map<ProposalItemId, String>",
        "com.singularity.todo.core.ui.textfields.ObservedField.TextFieldStateBridgeUiTest" to
            "edits: MutableList<String>",
    )

    /** True when the composable is one of the known sites. */
    fun isKnown(qualifiedName: String): Boolean = KNOWN.containsKey(qualifiedName)
}
