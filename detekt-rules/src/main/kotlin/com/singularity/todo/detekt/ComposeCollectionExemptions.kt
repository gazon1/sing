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
 * conversion of the hot states; and again after the menu, the proposals and the
 * logbook took their collections from the persistent kind.
 *
 * ## The key is the parameter, not the composable
 *
 * An entry names a composable and, under it, **every** raw collection
 * parameter that composable still declares, with the type each one declares.
 * The key is therefore the pair (parameter, type) under a qualified name —
 * not the qualified name alone.
 *
 * The difference is not cosmetic. A key that names only the composable exempts
 * the whole function: a *new* raw collection parameter added to a known
 * composable is then invisible, and the register cannot tell the difference
 * between "this site is known" and "this site is known, and this is the
 * parameter that is known". Keying by the parameter makes the second
 * question answerable, and it makes the register checkable: a parameter that
 * was retyped leaves an entry that names a type the code no longer declares,
 * and [NoUnstableCollectionParamRule] reports it as stale.
 *
 * A parameter that was **renamed** or **removed** is a different case, and the
 * rule does not see it: a parameter that is gone has no declared type to
 * compare, and a composable may have overloads, so "this parameter is missing
 * here" is not the same as "this parameter is gone". The gate
 * (`scripts/check-compose-stability-config.py`) reads the sources of the whole
 * repository and answers that question there.
 *
 * @see NoUnstableCollectionParamRule
 * @see 2026-10-22-compose-stability-contract
 */
internal object ComposeCollectionExemptions {

    /**
     * The known sites: a composable's qualified name, and under it the raw
     * collection parameters it still declares, with the type of each.
     *
     * The qualified name is the one the Compose compiler prints in its
     * stability report — the classes a function is nested in come first, the
     * function last — so a name can be copied from the report into this
     * register without a translation table.
     */
    val KNOWN: Map<String, Map<String, String>> = mapOf(
        "com.singularity.todo.core.ui.components.BlockedBulkCompletionDialog" to
            mapOf("blockedTaskTitles" to "List<String>"),
        "com.singularity.todo.core.ui.components.BlockedCompletionDialog" to
            mapOf("blockerTitles" to "List<String>"),
        "com.singularity.todo.core.ui.components.sheet.ListPickerSheet" to
            mapOf("items" to "List<ListPickerItem<T>>"),
        "com.singularity.todo.core.ui.components.sheet.MonthGrid" to
            mapOf("selected" to "Set<LocalDate>"),
        "com.singularity.todo.core.ui.components.sheet.MultiDatePickerSheet" to
            mapOf("initialDates" to "Set<LocalDate>"),
        "com.singularity.todo.core.ui.components.sheet.MultiSelectSheet" to
            mapOf(
                "items" to "List<MultiSelectItem<T>>",
                "selectedKeys" to "Set<T>",
            ),
        "com.singularity.todo.feature.agenda.presentation.components.ReorderableSectionList" to
            mapOf("sections" to "List<Section>"),
        "com.singularity.todo.feature.agenda.presentation.screen.AgendaList" to
            mapOf("sections" to "List<RenderedSection>"),
        "com.singularity.todo.feature.ai.chat.ChatMessagesList" to
            mapOf("messages" to "List<ChatMessage>"),
        "com.singularity.todo.feature.attachments.components.AttachmentsSheet" to
            mapOf("attachments" to "List<Attachment>"),
        "com.singularity.todo.feature.attachments.viewer.ViewerMessage" to
            mapOf("actions" to "List<ViewerAction>"),
        "com.singularity.todo.feature.calendar.presentation.components.calendar.HourRow" to
            mapOf(
                "dates" to "List<LocalDate>",
                "tasksByDate" to "Map<LocalDate, List<CalendarTaskUi>>",
            ),
        "com.singularity.todo.feature.calendar.presentation.components.calendar.MonthGridPage" to
            mapOf(
                "tasksByDate" to "Map<LocalDate, List<CalendarTaskUi>>",
                "selectedDates" to "Set<LocalDate>",
            ),
        "com.singularity.todo.feature.calendarSync.presentation.CalendarAppPicker" to
            mapOf("availableApps" to "List<CalendarAppInfo>"),
        "com.singularity.todo.feature.checklist.components.ChecklistEditorSheet" to
            mapOf("items" to "List<ChecklistItem>"),
        "com.singularity.todo.feature.notes.presentation.screen.BacklinksSheet" to
            mapOf("backlinks" to "List<Note>"),
        "com.singularity.todo.feature.notes.presentation.screen.NoteList" to
            mapOf(
                "allNotes" to "List<Note>",
                "pinned" to "List<Note>",
                "unpinned" to "List<Note>",
                "templates" to "List<Note>",
                "dailyNotes" to "List<Note>",
                "selectedIds" to "Set<NoteId>",
            ),
        "com.singularity.todo.feature.pomodoro.PomodoroContentPreview" to
            mapOf("tasks" to "List<Task>"),
        "com.singularity.todo.feature.pomodoro.TaskChipRow" to
            mapOf("tasks" to "List<Task>"),
        "com.singularity.todo.feature.profile.ProfileListContent" to
            mapOf("profiles" to "List<Profile>"),
        "com.singularity.todo.feature.search.presentation.PriorityChips" to
            mapOf("selected" to "Set<TaskPriority>"),
        "com.singularity.todo.feature.search.presentation.SavedSearchesRow" to
            mapOf("savedSearches" to "List<SavedSearch>"),
        "com.singularity.todo.feature.search.presentation.StateChips" to
            mapOf("selected" to "Set<TaskStatus>?"),
        "com.singularity.todo.feature.settings.SettingsContent" to
            mapOf("previewOverrides" to "Map<SettingsTab, @Composable () -> Unit>"),
        "com.singularity.todo.feature.statistics.InsightsStackedBarChart" to
            mapOf("buckets" to "List<DayInsightsBucket>"),
        "com.singularity.todo.feature.statistics.ProjectBreakdown" to
            mapOf("projectBuckets" to "List<ProjectInsightsBucket>"),
        "com.singularity.todo.feature.statistics.TasksBarChart" to
            mapOf("buckets" to "List<DayBucket>"),
        "com.singularity.todo.feature.tags.TagList" to
            mapOf("tags" to "List<Tag>"),
        "com.singularity.todo.feature.tags.components.TagsPickerSheet" to
            mapOf("selectedIds" to "List<TagId>"),
        "com.singularity.todo.feature.tags.presentation.screen.TagGroupList" to
            mapOf("groups" to "List<TagGroup>"),
        "com.singularity.todo.core.ui.onboarding.rememberSpotlightTour" to
            mapOf("steps" to "List<SpotlightStep>"),
        "com.singularity.todo.feature.ai.chat.AgentActivitySection" to
            mapOf(
                "plan" to "List<AgentPlanStep>",
                "calls" to "List<AgentToolCall>",
            ),
        "com.singularity.todo.feature.ai.ui.AgentPlanCard" to
            mapOf("steps" to "List<AgentPlanStep>"),
        "com.singularity.todo.feature.nav.Nav3State.toDecoratedEntries" to
            mapOf("entryDecorators" to "List<NavEntryDecorator<NavKey>>"),
        "com.singularity.todo.core.ui.textfields.TextFieldStateBridgeUiTest.ObservedField" to
            mapOf("edits" to "MutableList<String>"),
    )

    /**
     * The raw collection parameters [qualifiedName] is known to carry, and the
     * type the register records for each.
     *
     * Empty when the composable is not a known site — which says nothing about
     * its parameters: a composable that is not in the register has no exempted
     * parameter at all.
     */
    fun parametersOf(qualifiedName: String): Map<String, String> =
        KNOWN[qualifiedName] ?: emptyMap()
}
