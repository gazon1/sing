package com.singularity.todo.feature.ai.tools

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.logic.HealthRules
import com.singularity.todo.feature.tasks.domain.logic.TaskHealth
import com.singularity.todo.feature.tasks.domain.logic.TaskHealthBucket
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionResolver
import com.singularity.todo.feature.tasks.domain.logic.stagnationWindowOf
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * The facts a project brief is built from.
 *
 * Everything here is a fact the application can establish: the project's own row,
 * the tasks that belong to it, their health as the app classifies it, and the notes
 * attached to those tasks. The model reads these and says what they mean; it does
 * not add to them.
 *
 * @property tasks the open project tasks, bounded. [tasksTotal] is how many open
 *   tasks the project has, so a truncated page is stated rather than implied.
 * @property notes the notes attached to the project's tasks, bounded. [notesTotal]
 *   is how many there are in all.
 */
@Serializable
internal data class ProjectBrief(
    val projectId: String,
    val name: String,
    val description: String? = null,
    val totalTasks: Int = 0,
    val openTasks: Int = 0,
    val completedTasks: Int = 0,
    val overdue: Int = 0,
    val blocked: Int = 0,
    val stagnant: Int = 0,
    val tasks: List<PlanTask> = emptyList(),
    val tasksTotal: Int = 0,
    val notes: List<ProjectBriefNote> = emptyList(),
    val notesTotal: Int = 0,
)

/**
 * A note attached to one of the project's tasks.
 *
 * The body is an excerpt, not the note: a brief is a summary of the project, and a
 * corpus of note bodies would be neither.
 */
@Serializable
internal data class ProjectBriefNote(val taskId: String, val title: String, val excerpt: String? = null)

/**
 * Builds the project-scoped view a project review reads.
 *
 * ## What it reuses
 *
 * Membership, health and blockers are the app's, not this object's: the tasks come
 * from the same repository the screens read, health comes from [HealthRules] with the
 * user's own staleness window, and the blockers come from
 * [com.singularity.todo.feature.tasks.domain.logic.Blocking]. A brief that counted
 * differently from the agenda would be a second interpretation of the same tasks.
 *
 * ## What it reads, and why it reads all of it
 *
 * Health and blockers are properties of a task *in the context of every other task*:
 * a task blocked by a prerequisite in another project is blocked, and a brief that
 * only saw the project's own tasks would call it fine. So the whole task list is the
 * input, and the project's membership is applied afterwards.
 *
 * A prerequisite that lives outside the project is a dependency, never a member: it
 * is named as a blocker, and it is not one of the project's tasks.
 */
internal object ProjectBriefSupport {
    /** How many open tasks a brief carries. */
    private const val TASK_LIMIT = 50

    /** How many attached notes a brief carries. */
    private const val NOTE_LIMIT = 20

    /** How much of a note's body a brief carries. */
    private const val NOTE_EXCERPT_LIMIT = 240

    /**
     * The brief of [projectId], or `null` when there is no such project.
     *
     * A deleted project is no such project: the brief is about work the user can
     * still see, and a soft-deleted row is not that.
     */
    suspend fun brief(
        projectId: ProjectId,
        taskRepository: TaskRepository,
        projectsRepository: ProjectsRepository,
        notesRepository: NotesRepository,
        clock: Clock,
        zone: TimeZone,
        stalenessDays: Int,
    ): ProjectBrief? {
        val project = projectsRepository.observeProject(projectId).first() ?: return null
        if (project.isDeleted) return null

        // Every task, not the project's tasks: health and blockers are decided
        // against the whole list, and a subset would hide a dependency that lives
        // in another project.
        val all = taskRepository.observeByFilter(TaskSelectionResolver.baseFilter()).first()
        val context = TaskToolSupport.context(all, clock, zone)
        val window = stagnationWindowOf(stalenessDays) ?: TaskHealth.DEFAULT_STAGNATION_WINDOW
        val rules = HealthRules(window)

        val projectTasks = all.filter { it.projectId == projectId && !it.isTrashed }
        val open = projectTasks.filter { !it.isCompleted }

        // The counts come from the whole set, before any page is cut: a brief that
        // says "3 overdue" must mean three of the project's tasks are overdue, not
        // three of the fifty it happened to carry.
        val buckets = open.map { rules.bucketOf(it, context) }

        val ordered = open.sortedWith(TaskPlanSupport.ORDER)
        val notes =
            if (projectTasks.isEmpty()) {
                emptyList()
            } else {
                notesRepository
                    .watchForTasks(projectTasks.map { it.id }.toSet())
                    .first()
            }

        return ProjectBrief(
            projectId = project.id.value,
            name = project.name,
            description = project.description?.takeIf { it.isNotBlank() },
            totalTasks = projectTasks.size,
            openTasks = open.size,
            completedTasks = projectTasks.size - open.size,
            overdue = buckets.count { it == TaskHealthBucket.OVERDUE },
            blocked = buckets.count { it == TaskHealthBucket.BLOCKED },
            stagnant = buckets.count { it == TaskHealthBucket.STAGNANT },
            tasks = ordered.take(TASK_LIMIT).map { TaskPlanSupport.planTask(it, all, context, rules) },
            tasksTotal = open.size,
            notes = notes.sortedWith(NOTE_ORDER).take(NOTE_LIMIT).map { it.toBriefNote() },
            notesTotal = notes.size,
        )
    }

    /**
     * The order the notes come back in.
     *
     * Newest first, then by id: the id breaks the tie, so two calls cannot swap
     * two notes that were written in the same second.
     */
    private val NOTE_ORDER: Comparator<Note> =
        compareByDescending<Note> { it.createdAt }
            .thenBy { it.id.value }

    /** The note, as a brief carries it: title and an excerpt of the body. */
    private fun Note.toBriefNote(): ProjectBriefNote =
        ProjectBriefNote(
            taskId = taskId?.value.orEmpty(),
            title = title,
            excerpt =
                bodyMarkdown
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { body ->
                        if (body.length > NOTE_EXCERPT_LIMIT) {
                            body.take(NOTE_EXCERPT_LIMIT) + "…"
                        } else {
                            body
                        }
                    },
        )
}
