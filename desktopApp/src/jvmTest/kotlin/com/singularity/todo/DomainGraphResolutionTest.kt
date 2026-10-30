package com.singularity.todo

import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewModel
import com.singularity.todo.feature.attachments.AttachmentsViewModel
import com.singularity.todo.feature.attachments.annotation.AttachmentAnnotationViewModel
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel
import com.singularity.todo.feature.nav.TasksRoute
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.presentation.viewmodel.NoteProposalsViewModel
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectEditorViewModel
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import com.singularity.todo.test.helpers.testPlatformModule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.error.NoParameterFoundException
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.junit.jupiter.api.Tag
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every definition in the domain graph must be *creatable*.
 *
 * The other DI guard in this repo — `TestPlatformModuleParityTest` — compares
 * *registrations*: which types the production platform module binds against the
 * test one. It never resolves anything, so it cannot see a definition whose
 * own dependencies are missing. That is the blind spot this test closes.
 *
 * The defect it would have caught: `RewriteNoteUseCase` takes the `TypedTool`
 * *interface*, and `factoryOf(::RewriteNoteUseCase)` resolves the constructor
 * parameter by its declared type, which erases to the raw `TypedTool` — for
 * which nothing is bound. The definition was registered, so the parity check
 * was happy; every *creation* of it threw `NoDefinitionFoundException`, on
 * every platform, and the note editor was unreachable. It stayed invisible
 * because no test ever resolved the definition.
 *
 * `getOrNull` did not hide it either: it swallows a missing *definition*, not a
 * definition whose creation fails.
 *
 * The graph is built from the same pieces the desktop harness uses
 * (`domainModule()` + `testPlatformModule()`), so nothing here touches the
 * developer's home directory, a real database or a real port.
 *
 * ## The definitions that need a runtime parameter
 *
 * The definitions that take a runtime parameter
 *
 * Nine definitions take a parameter the caller supplies — a ViewModel that edits one
 * entity needs that entity's id. They used to be *skipped* here: the test counted them
 * as "needs a parameter" and moved on, which left their own dependencies unverified. A
 * definition whose dependency is missing is exactly the defect this test exists to
 * catch, and they were the definitions most likely to have one — they are the
 * ones that reach into a repository, a use case and a crash reporter at once.
 *
 * They are resolved now, with the parameters listed in [runtimeParameters]. The list is
 * a registry, not a convenience: a definition that starts needing a parameter and is
 * not in the registry fails the test, and an entry that no longer matches a definition
 * fails it too. A new parameterised ViewModel cannot slip in unverified.
 */
@Tag("fast")
class DomainGraphResolutionTest {

    @Test
    fun `every domain definition can be created`() {
        val app = koinApplication {
            // KOIN-W003 here is an accepted, documented tradeoff — the same one
            // `TaskDetailCoordinatorGraphTest` takes: the module set is computed
            // at runtime, so the compile-time graph check cannot see it. This
            // test is the runtime validation that replaces it.
            @Suppress("KoinCompilationForInternal")
            modules(
                listOf(coreLoggingModule()) +
                    domainModule() +
                    listOf(testPlatformModule()),
            )
        }
        try {
            val koin = app.koin
            val failures = mutableListOf<String>()
            val uncovered = mutableListOf<String>()
            val used = mutableSetOf<KClass<*>>()
            var resolved = 0
            for (definition in koin.definitions()) {
                val type = definition.primaryType
                val qualifier = definition.qualifier
                val parameters = runtimeParameters[type]
                try {
                    if (parameters == null) {
                        koin.get<Any>(type, qualifier)
                    } else {
                        used += type
                        koin.get<Any>(type, qualifier) { parametersOf(*parameters.toTypedArray()) }
                    }
                    resolved++
                } catch (t: Throwable) {
                    // A definition that takes a runtime parameter which the registry
                    // does not cover cannot be created. That is not a broken graph:
                    // the caller supplies the parameter. It *is* an unverified
                    // definition, and the registry is what makes it verified.
                    if (t.causes().any { it is NoParameterFoundException }) {
                        uncovered += "${type.qualifiedName} [${definition.kind}, " +
                            "qualifier=${qualifier?.value}]"
                        continue
                    }
                    failures += "${type.qualifiedName} [${definition.kind}, " +
                        "qualifier=${qualifier?.value}]: ${t.causes().describe()}"
                }
            }
            assertTrue(
                failures.isEmpty(),
                "${failures.size} of the graph's definitions cannot be created:\n" +
                    failures.joinToString("\n") { "  - $it" } +
                    "\n\nA definition that throws here is a definition that throws the " +
                    "first time a user reaches the screen that uses it.",
            )
            assertTrue(
                uncovered.isEmpty(),
                "These definitions take a runtime parameter and the test does not supply " +
                    "one, so their own dependencies are never checked:\n" +
                    uncovered.joinToString("\n") { "  - $it" } +
                    "\n\nAdd them to `runtimeParameters` in this file. A definition that " +
                    "reaches into a repository and a use case at once is exactly where a " +
                    "missing dependency hides.",
            )
            assertTrue(
                used.size == runtimeParameters.size,
                "These `runtimeParameters` entries match no definition in the graph:\n" +
                    runtimeParameters.keys.filterNot { it in used }
                        .joinToString("\n") { "  - ${it.qualifiedName}" } +
                    "\n\nA stale entry hides the fact that the definition it was written " +
                    "for is gone — or has stopped needing a parameter.",
            )
            assertTrue(
                resolved > 0,
                "the graph resolved no definitions at all — the test is vacuous",
            )
        } finally {
            app.close()
        }
    }
}

/**
 * The chain of causes of a failure, innermost last.
 *
 * Koin wraps whatever a definition threw in an `InstanceCreationException` whose
 * message is "Could not create instance for '[Factory: …]'" — the *reason* is in
 * the cause. Without the chain the report says only that something failed, which
 * is the same information the production crash gave.
 */
private fun Throwable.causes(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && chain.size < 8) {
        chain += current
        current = current.cause
    }
    return chain
}

/** A one-line description of a cause chain: the innermost cause and its type. */
private fun List<Throwable>.describe(): String {
    val root = last()
    val chain = joinToString(" <- ") { it::class.simpleName ?: "?" }
    val message = root.message ?: "no message"
    return "$chain: $message"
}

/**
 * The definitions in the graph, as Koin sees them.
 *
 * The instance registry is internal API: it is the only way to enumerate what a
 * graph holds, and enumerating it is the whole point of the test.
 */
@OptIn(KoinInternalApi::class)
private fun org.koin.core.Koin.definitions() =
    instanceRegistry.instances.values.map { it.beanDefinition }

/**
 * The runtime parameters of the definitions that take one.
 *
 * The values are irrelevant — what matters is that each one has the type the
 * definition destructures, and that the definition can therefore be *created*, so its
 * own dependencies are resolved. The order matters: Koin hands the parameters to the
 * definition's destructuring lambda by position.
 *
 * A definition that is not in this table and still takes a parameter fails the test
 * (see `runtimeParameters` in the test body), and an entry that no longer matches a
 * definition fails it too.
 */
private val runtimeParameters: Map<KClass<*>, List<Any>> = mapOf(
    // CalendarViewModel(year, month, mode)
    CalendarViewModel::class to listOf(2026, Month.SEPTEMBER, CalendarViewMode.MONTH),
    // AttachmentAnnotationViewModel(attachmentId) — one panel per open attachment
    AttachmentAnnotationViewModel::class to listOf(AttachmentId("attachment-1")),
    // AttachmentsViewModel(taskId) — the panel of one task's attachments
    AttachmentsViewModel::class to listOf(TaskId("task-1")),
    // ProjectDetailViewModel(projectId)
    ProjectDetailViewModel::class to listOf(ProjectId("project-1")),
    // ProjectEditorViewModel(id)
    ProjectEditorViewModel::class to listOf(ProjectId("project-1")),
    // TaskDetailCoordinator(route) — the route carries the task and the view it came from
    TaskDetailCoordinator::class to listOf(TasksRoute.Detail(TaskId("task-1"))),
    // TaskCreateViewModel(initialDueDate, draftKey)
    TaskCreateViewModel::class to listOf(LocalDate(2026, 9, 16), "section-1"),
    // AgendaViewModel(definition)
    AgendaViewModel::class to listOf(sampleAgendaDefinition()),
    // SavedAgendaViewModel(mode)
    SavedAgendaViewModel::class to listOf(SavedAgendaScreenMode.Create(sampleAgendaDefinition())),
    // NoteProposalsViewModel(noteId) — the pending-proposal panel of one note
    NoteProposalsViewModel::class to listOf(NoteId("note-1")),
)

/** An [AgendaDefinition] with no sections — enough to construct a ViewModel with it. */
private fun sampleAgendaDefinition() = AgendaDefinition(title = "Sample", sections = emptyList())
