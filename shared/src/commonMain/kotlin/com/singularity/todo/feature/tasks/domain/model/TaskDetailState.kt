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
import com.singularity.todo.feature.timetracking.domain.TimeTrackingRepository
import kotlin.time.Clock

/**
 * Dependencies for [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator].
 */
data class TaskDetailDeps(
    val taskRepo: TaskRepository,
    val updateTask: UpdateTaskUseCase,
    val createTask: CreateTaskUseCase,
    val projectsRepo: ProjectsRepository,
    val tagsRepo: com.singularity.todo.feature.tags.TagsRepository,
    val checklistRepository: ChecklistRepository,
    val notesRepo: NotesRepository,
    val reminderRepo: ReminderRepository,
    val reminderScheduler: ReminderScheduler,
    val attachmentsRepo: AttachmentRepository,
    val timeZoneProvider: TimeZoneProvider,
    val clock: Clock,
    val completeRecurring: CompleteRecurringTaskUseCase,
    /** Time tracking repository — required for the task time slot. */
    val timeTrackingRepo: TimeTrackingRepository,
    /** Current user — required for time tracking user-scoped operations. */
    val currentUser: ProfileAwareCurrentUser,
    /** AI use cases — nullable so tests can omit them. */
    val refineTask: RefineTaskUseCase? = null,
    val generateDescription: GenerateDescriptionUseCase? = null,
    val generateChecklist: GenerateChecklistUseCase? = null,
    val decomposeTask: DecomposeTaskUseCase? = null,
    val pickTime: PickTimeUseCase? = null,
    /** Backlink queries — nullable so tests can omit them. */
    val linkRepo: InternalLinkRepository? = null,
    /** Proposal repository — nullable so tests can omit it. */
    val proposals: ProposalRepository? = null,
    /** Apply proposal use case — nullable so tests can omit it. */
    val applyProposal: ApplyProposalItemUseCase? = null,
    /** Debounce duration for title/description edits. Exposed for tests to use short durations. */
    val debounceMs: Long = 300L,
)
