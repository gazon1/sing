package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.ui.debounce.Debouncer
import com.singularity.todo.core.notifications.NotificationsSettingsRepository
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.model.ParentOption
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.projects.presentation.model.TagGroupOption
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState
import com.singularity.todo.feature.reminders.domain.port.ProjectRemindersRepository
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * ViewModel for [com.singularity.todo.feature.projects.presentation.screen.ProjectDetailScreen].
 *
 * Subscribes to the project repository ONCE; the task/child/parent streams derive
 * tasks, and aggregate counts into a single [ProjectDetailUi].
 * Inline edits (name, description) use silent debounce — they update [_lastEditedAt]
 * but do NOT emit [ProjectDetailUiEvent.Saved].
 *
 * [_lastEditedAt] is a continuous state exposed as [lastEditedAt] for the screen
 * to render "Saved X ago" via [formatSavedRelative].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModel(
    private val projectId: ProjectId,
    private val projectRepo: ProjectsRepository,
    private val taskRepo: TaskRepository,
    private val tagGroupRepo: TagGroupRepository,
    private val deleteProject: DeleteProjectUseCase,
    private val updateProject: UpdateProjectUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val createTaskUseCase: CreateTaskUseCase,
    private val projectReminders: ProjectRemindersRepository,
    /**
     * Read for exactly one value: `reminderDefault`, the user's default reminder
     * offset from Notification settings.
     *
     * It preselects the reminder picker when the project has no reminder yet — it
     * does not create one. Before this read the setting was persisted, exported,
     * rendered in Settings, and consumed by nothing: a control whose value changed
     * nothing anywhere, which is what `check-dead-settings` exists to name.
     */
    private val notificationsSettings: NotificationsSettingsRepository,
    val clock: Clock,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ProjectDetailUiState, ProjectDetailIntent.Domain, ProjectDetailUiEvent>(
        initialState = ProjectDetailUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    // ─── UI State ───────────────────────────────────────────────────────────────

    // The three flows below are inputs to the state `combine` in [init], not part of
    // the public surface. The screen reads them from [ProjectDetailUiState.Content], so
    // it observes one atomic snapshot instead of collecting four flows independently.
    // [lastEditedAt] is deliberately NOT folded in: it is written by [mutate] after the
    // repository write, not by the combine, so putting it in Content would give the
    // state two writers and open a lost-update window.

    private val hideCompletedFlow = MutableStateFlow(false)

    /**
     * Hides tasks blocked by unfinished dependencies.
     *
     * Folded into [TaskVisibility] rather than combined as a sixth flow — see that
     * type's KDoc for the arity reason.
     */
    private val hideBlockedFlow = MutableStateFlow(false)

    private val visibilityFlow = combine(hideCompletedFlow, hideBlockedFlow, ::TaskVisibility)

    /** Emits null on start (loading placeholder), then the project flow. */
    private val _projectFlow = MutableStateFlow<Project?>(null)

    /**
     * Reactive list of parent-picker options, derived from [projectFlow] and
     * [ProjectsRepository.watchProjects]. Excludes the current project (cycle prevention)
     * and already-deleted / non-root projects.
     */
    private val parentOptionsFlow = MutableStateFlow<List<ParentOption>>(emptyList())

    /**
     * All active tasks that are NOT in this project — for the "add existing task"
     * quick-add picker. Excludes inbox tasks (projectId == null) and completed tasks.
     * Sorted by dueDate ascending (nulls last), then updatedAt descending.
     */
    private val availableTasksFlow = MutableStateFlow<List<Task>>(emptyList())

    /**
     * Reactive list of tag-group picker options, derived from [_projectFlow] and
     * [TagGroupRepository.observeAll]. Excludes soft-deleted groups.
     */
    private val tagGroupOptionsFlow = MutableStateFlow<List<TagGroupOption>>(emptyList())

    /**
     * When this project's reminder fires, and what the picker should preselect.
     *
     * Declared before the `init` below because a collector launched from that
     * `init` reads it — see [draftState] for why that ordering is load-bearing
     * rather than cosmetic.
     */
    private val reminderCoordinator = ProjectReminderCoordinator(
        projectId = projectId,
        project = { _projectFlow.value },
        reminders = projectReminders,
        defaultOffset = notificationsSettings.reminderDefault,
    )

    /**
     * The ViewModel's own slot for the pair, feeding the second `combine`.
     *
     * Seeded with [SettingsDefaults.Notifications.REMINDER_DEFAULT] rather than a
     * null default so the picker always has something to preselect before the
     * first emission arrives. Seeded from the constant rather than from the
     * repository's own default so this class does not silently become a second
     * source of truth for what "no setting" means.
     */
    private val reminderOffsetsFlow = MutableStateFlow(
        ReminderOffsets(
            current = null,
            default = SettingsDefaults.Notifications.REMINDER_DEFAULT,
        ),
    )

    /**
     * Draft state — single source of truth for editable name/description.
     *
     * Declared before the `init` blocks that use it. Kotlin runs property
     * initializers and `init` blocks in declaration order, so a property declared
     * *after* such an `init` is still null while a coroutine launched from that
     * `init` may already be running. Not a theoretical hazard: this ordering made
     * `ProjectsFlowTest.opening_a_project_reaches_its_detail_screen` fail with an
     * NPE roughly 1 run in 2, depending on whether the coroutine got dispatched
     * before construction finished.
     */
    val draftState = ProjectDetailDraftState()

    init {
        // Single project observer: feeds [projectFlow] and seeds the editable draft.
        // All other streams derive from [projectFlow] instead of re-subscribing to the
        // repository (one Room observer, not five).
        vmScope.launch {
            projectRepo.observe(projectId)
                .onStart { emit(null) }
                .collect { project ->
                    _projectFlow.value = project
                    if (project != null && !project.isDeleted) {
                        draftState.seed(
                            project.name,
                            project.description
                                ?: "",
                        )
                    }
                }
        }

        // The configured default offset — preselects the picker, creates nothing.
        // Both reminder values (the project's own offset and the user's default)
        // arrive as one [ReminderOffsets] emission, so the collector below is the
        // only thing in this file that knows reminders have two inputs.
        vmScope.launch {
            reminderCoordinator.offsets.collect { reminderOffsetsFlow.value = it }
        }

        // Parent-picker options
        vmScope.launch {
            combine(
                _projectFlow,
                projectRepo.observeAll(),
            ) { project, allProjects ->
                if (project == null) {
                    emptyList()
                } else {
                    allProjects.filter { it.id != project.id && it.parentId == null && !it.isDeleted }
                        .map { ParentOption(it.id, it.name, it.id == project.parentId) }
                }
            }.collect { parentOptionsFlow.value = it }
        }

        // Quick-add picker candidates
        vmScope.launch {
            taskRepo.observeByFilter(TaskFilter.All)
                .map { all -> ProjectTaskListDeriver.quickAddCandidates(all, projectId) }
                .collect { availableTasksFlow.value = it }
        }

        // Tag-group picker options
        vmScope.launch {
            combine(
                _projectFlow,
                tagGroupRepo.observeAll(),
            ) { project, allGroups ->
                if (project == null) {
                    emptyList()
                } else {
                    allGroups
                        .filter { it.deletedAt == null }
                        .map { group ->
                            TagGroupOption(
                                id = group.id,
                                name = group.name,
                                color = group.color,
                                isCurrent = group.id in project.inheritedTagGroupIds,
                            )
                        }
                }
            }.collect { tagGroupOptionsFlow.value = it }
        }

        // Collect state. Two nested combines rather than one 7-argument combine:
        // kotlinx only ships typed `combine` overloads up to 5 flows, and a 7-flow
        // vararg call would collapse to `Array<Any?>`.
        //
        // The tasks and children streams depend only on *whether* a project is loaded,
        // not on any field of it. Triggering them off a nullability signal rather than
        // the object itself means a field write does not cancel and recreate the
        // subscription — `flatMapLatest` restarts its upstream on every trigger emission.
        vmScope.launch {
            val hasProject = _projectFlow.map { it != null }.distinctUntilChanged()
            val projectState = combine(
                _projectFlow,
                hasProject.flatMapLatest { loaded ->
                    if (!loaded) flowOf(emptyList()) else taskRepo.observeByFilter(TaskFilter.ByProject(projectId))
                },
                hasProject.flatMapLatest { loaded ->
                    if (!loaded) flowOf(emptyList()) else projectRepo.observeChildrenOf(projectId)
                },
                // The parent stream does depend on a field, so it stays on the object.
                _projectFlow.flatMapLatest { p ->
                    if (p == null || p.parentId == null) flowOf(null) else projectRepo.observe(p.parentId)
                },
                visibilityFlow,
            ) { project, tasks, childProjects, parent, visibility ->
                when {
                    project == null -> ProjectDetailUiState.Loading

                    project.isDeleted -> ProjectDetailUiState.NotFound

                    else -> {
                        // `blockedIds` is resolved against the unfiltered list and only
                        // when the filter is on — see ProjectTaskListDeriver.
                        val blocked = if (visibility.hideBlocked) {
                            ProjectTaskListDeriver.blockedIds(tasks)
                        } else {
                            emptySet()
                        }
                        ProjectDetailUiState.Content(
                            ui = ProjectDetailUi(
                                project = project,
                                tasks = ProjectTaskListDeriver.preview(tasks, visibility, blocked),
                                // Counts come from the *unfiltered* list: "5 of 23"
                                // must not become "5 of 5" when rows are hidden.
                                totalCount = ProjectTaskListDeriver.totalCount(tasks),
                                completedCount = ProjectTaskListDeriver.completedCount(tasks),
                                childProjects = childProjects,
                                parent = parent,
                            ),
                            hideCompleted = visibility.hideCompleted,
                            hideBlocked = visibility.hideBlocked,
                            parentOptions = emptyList(),
                            availableTasks = emptyList(),
                            tagGroups = emptyList(),
                        )
                    }
                }
            }
            combine(
                projectState,
                parentOptionsFlow,
                availableTasksFlow,
                reminderOffsetsFlow,
                tagGroupOptionsFlow,
            ) { state, parentOptions, availableTasks, offsets, tagGroups ->
                if (state is ProjectDetailUiState.Content) {
                    state.copy(
                        parentOptions = parentOptions,
                        availableTasks = availableTasks,
                        tagGroups = tagGroups,
                        ui = state.ui.copy(
                            reminderOffsetMinutes = offsets.current,
                            defaultReminderOffset = offsets.default,
                        ),
                    )
                } else {
                    state
                }
            }.collect { setState(it) }
        }
    }

    // ─── Silent debounce for inline edits ───────────────────────────────────────

    private val _lastEditedAt = MutableStateFlow<Instant?>(null)
    val lastEditedAt: StateFlow<Instant?> = _lastEditedAt

    /** Debouncer for silent inline edits (name, description). */
    private val debouncer = Debouncer(scope, 300.milliseconds)

    init {
        // Name and description edits. `mutate` re-reads the project through
        // [UpdateProjectUseCase] inside its own launch, so neither debounce can write back
        // a stale snapshot.
        debouncer.debounce(draftState.state.map { it.name }) { name ->
            mutate { copy(name = name) }
        }
        debouncer.debounce(draftState.state.map { it.description }) { desc ->
            mutate { copy(description = desc) }
        }
    }

    // ─── Intent dispatcher ─────────────────────────────────────────────────────

    /**
     * Единственный публичный метод для всех доменных операций.
     *
     * Routing-интенты ([ProjectDetailIntent.Routing]) обрабатываются экраном
     * и сюда не попадают.
     */
    override fun onIntent(intent: ProjectDetailIntent.Domain) {
        when (intent) {
            // ── Visibility ──────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.ToggleHideCompleted -> hideCompletedFlow.value = !hideCompletedFlow.value

            is ProjectDetailIntent.Domain.ToggleHideBlocked -> hideBlockedFlow.value = !hideBlockedFlow.value

            is ProjectDetailIntent.Domain.SetReminder -> setReminder(intent.offsetMinutes)

            // ── Inline edits — debounced, written to draft StateFlows ────────
            is ProjectDetailIntent.Domain.UpdateName -> draftState.setName(intent.name)

            is ProjectDetailIntent.Domain.UpdateDescription -> draftState.setDescription(
                intent.description
                    ?: "",
            )

            // ── Pickers ─────────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.UpdateColor -> {
                mutate { copy(color = intent.color) }
            }

            is ProjectDetailIntent.Domain.UpdateIcon -> {
                mutate { copy(icon = intent.icon) }
            }

            is ProjectDetailIntent.Domain.UpdateParent -> {
                mutate { copy(parentId = intent.parentId) }
            }

            is ProjectDetailIntent.Domain.UpdateInheritedTagGroups -> {
                // `setInheritedForProject` writes the join table AND the project document
                // (including updatedAt) AND enqueues the project for sync. The subsequent
                // `mutate` is the optimistic UI update — the actual persistence is done here.
                vmScope.launch {
                    tagGroupRepo.setInheritedForProject(projectId, intent.groupIds)
                }
                mutate { copy(inheritedTagGroupIds = intent.groupIds) }
            }

            is ProjectDetailIntent.Domain.UpdateDueDate -> {
                mutate { copy(dueDate = intent.dueDate) }
            }

            // ── Lifecycle ──────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.ToggleArchive -> {
                mutate { copy(isDeleted = !isDeleted) }
            }

            is ProjectDetailIntent.Domain.Delete -> vmScope.launch {
                deleteProject(projectId)
                    .onSuccess { emit(ProjectDetailUiEvent.NavigateBack) }
                    .onFailure { emit(ProjectDetailUiEvent.ShowError(it.toMessage("Delete failed"))) }
            }

            // ── Tasks ──────────────────────────────────────────────────────
            is ProjectDetailIntent.Domain.CreateTask -> {
                val trimmed = intent.title.trim()
                if (trimmed.isEmpty()) return
                emitError("Create task failed", ProjectDetailUiEvent::ShowError) {
                    createTaskUseCase(
                        CreateTaskInput(
                            title = trimmed,
                            projectId = projectId,
                            kind = TaskKind.Task,
                        ),
                    )
                }
            }

            is ProjectDetailIntent.Domain.MoveTaskToProject ->
                emitError("Move task failed", ProjectDetailUiEvent::ShowError) {
                    updateTask.invoke(intent.taskId) { it.copy(projectId = projectId) }
                }

            is ProjectDetailIntent.Domain.ToggleTaskPin ->
                emitError("Pin failed", ProjectDetailUiEvent::ShowError) {
                    taskRepo.togglePinned(intent.taskId)
                }

            is ProjectDetailIntent.Domain.DeleteTask ->
                emitError("Delete task failed", ProjectDetailUiEvent::ShowError) {
                    taskRepo.softDelete(intent.taskId)
                }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Applies a mutation to the current project and persists via [updateProject].
     *
     * [UpdateProjectUseCase] re-reads the entity inside its own call, so the transform
     * always runs against the current row — there is no cached snapshot to go stale here.
     */
    private fun mutate(transform: Project.() -> Project) {
        emitError("Update project failed", ProjectDetailUiEvent::ShowError) {
            updateProject(projectId, transform).also {
                if (it.isSuccess) _lastEditedAt.value = clock.now()
            }
        }
    }

    /**
     * Delegates the reminder write to [ProjectReminderCoordinator] and maps the
     * outcome onto a one-shot event.
     *
     * The coordinator returns a value rather than emitting, because it holds no
     * event bus — the ViewModel stays the only thing that can put something in
     * front of the user. The three cases below are the same three the old inline
     * helper produced; none of them is a new branch.
     */
    private fun setReminder(offsetMinutes: Int?) {
        vmScope.launch {
            when (val outcome = reminderCoordinator.set(offsetMinutes)) {
                is SetReminderOutcome.NoDueDate ->
                    emit(ProjectDetailUiEvent.ShowError("Set a due date before adding a reminder."))

                is SetReminderOutcome.Failed ->
                    emit(ProjectDetailUiEvent.ShowError("Reminder: ${outcome.cause.message}"))

                is SetReminderOutcome.Applied, is SetReminderOutcome.Removed -> Unit
            }
        }
    }
}
