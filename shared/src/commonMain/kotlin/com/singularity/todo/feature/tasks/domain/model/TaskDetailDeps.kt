package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CompleteRecurringTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import kotlin.time.Clock

/**
 * Dependencies for [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator],
 * grouped by what reads them.
 *
 * The grouping is **measured, not chosen.** Each slot was grepped for the fields it
 * actually reads, and every bundle below is exactly the set one slot or the coordinator
 * needs — no more. The old flat class listed 24 constructor parameters and every slot
 * held the whole bag, so `TaskAiSlot` could reach the reminder scheduler and
 * `TaskLifecycleSlot` could reach the AI use cases. Nothing stopped either, and nothing
 * would have failed if they had.
 *
 * The bundles are the reason this is a real restriction rather than a rename: a slot now
 * takes the two or three groups it uses, so a new dependency has to be threaded through
 * the type before it is reachable at all.
 *
 * **Size discipline.** Every bundle is at or under detekt's
 * `LongParameterList.allowedConstructorParameters` (8), and the aggregate is 6. The old
 * 24-parameter class was invisible to that rule only because
 * `ignoreDataClasses: true` — which is exactly why it survived unexamined.
 *
 * Nullable members are nullable per-bundle, so a caller that wants the screen without AI
 * supplies an empty [TaskAiDeps] rather than seven `= null` arguments.
 */
data class TaskDetailDeps(
    val core: TaskCoreDeps,
    val children: TaskChildrenDeps,
    val scheduling: TaskSchedulingDeps,
    val collaboration: TaskCollaborationDeps,
    val ai: TaskAiDeps,
    val context: TaskContextDeps,
)

/**
 * Reading and writing the task row itself.
 *
 * Held by the coordinator, the draft, entity, completion, children, and lifecycle slots —
 * the six that mutate the task. [TaskDetailCoordinator][com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator]
 * subscribes through [taskRepo]; everything else writes through [updateTask].
 */
data class TaskCoreDeps(
    val taskRepo: TaskRepository,
    val updateTask: UpdateTaskUseCase,
    val createTask: CreateTaskUseCase,
    val completeRecurring: CompleteRecurringTaskUseCase,
)

/**
 * Repositories for collections that hang off the task: checklist, subtasks, attachments,
 * and the project and tag catalogue the picker reads.
 *
 * Split from [TaskCoreDeps] because only the children and entity slots touch it, and it is
 * the widest group of the collection repositories.
 */
data class TaskChildrenDeps(
    val checklistRepository: ChecklistRepository,
    val attachmentsRepo: AttachmentRepository,
    val projectsRepo: ProjectsRepository,
    val tagsRepo: com.singularity.todo.feature.tags.TagsRepository,
)

/**
 * Reminders and the platform alarm that fires them.
 *
 * Both the reminders slot and the lifecycle slot need the scheduler — the first to arm an
 * alarm, the second to cancel them so a deleted task's alarm does not fire at nothing. The
 * timezone provider is here because reminder fire times are resolved in it.
 */
data class TaskSchedulingDeps(
    val reminderRepo: ReminderRepository,
    val reminderScheduler: ReminderScheduler,
    val timeZoneProvider: TimeZoneProvider,
)

/**
 * Cross-feature reads: notes that link to the task, time tracking, and the proposal flow.
 *
 * The coordinator owns these directly — the logbook collector, the time slot, and the
 * proposal intents are all assembled there rather than in a slot.
 */
data class TaskCollaborationDeps(
    val notesRepo: NotesRepository,
    val timeTrackingRepo: TimeTrackingRepository,
    val currentUser: ProfileAwareCurrentUser,
    val linkRepo: InternalLinkRepository? = null,
    val proposals: ProposalRepository? = null,
    val applyProposal: ApplyProposalItemUseCase? = null,
)

/**
 * AI-assisted actions, all optional.
 *
 * Every use case is nullable so a build without the AI module still constructs the screen.
 * A missing use case takes the same failure path as a failed call — [com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskAiSlot.withUseCase]
 * turns "not configured" into an error rather than a silent no-op, so a misconfigured
 * production build reports instead of quietly doing nothing.
 */
data class TaskAiDeps(
    val refineTask: RefineTaskUseCase? = null,
    val generateDescription: GenerateDescriptionUseCase? = null,
    val generateChecklist: GenerateChecklistUseCase? = null,
    val decomposeTask: DecomposeTaskUseCase? = null,
    val pickTime: PickTimeUseCase? = null,
)

/**
 * Ambient values rather than collaborators: the clock every slot stamps writes with, and
 * the draft slot's debounce.
 *
 * Grouped apart from the repositories so that "what time is it" and "how long do we wait"
 * are one substitution point in tests. `debounceMs` is exposed so slot tests can use a
 * short duration and stay on virtual time.
 */
data class TaskContextDeps(val clock: Clock, val debounceMs: Long = 300L)
