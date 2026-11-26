package spike.scenario

/**
 * The Task List behavior both variants must reproduce. One contract, two
 * implementations — otherwise "equivalent behavior" is a claim nobody measured.
 *
 * Deliberately in terms of the *scenario*, not of either framework: no FlowMVI
 * type and no `MviViewModel` type appears here.
 */

/** The filter a Task List screen can show. */
enum class TaskFilter { All, Active, Completed }

/** A task row. Immutable, like the entity the screen renders. */
data class TaskRow(val id: String, val title: String, val completed: Boolean)

/** What a Task List screen is showing. */
sealed interface ListState {
    data object Loading : ListState
    data class Ready(val rows: List<TaskRow>, val filter: TaskFilter) : ListState
    data class Failed(val message: String) : ListState
}

/** What the user can do on the screen. */
sealed interface ListIntent {
    data object Refresh : ListIntent
    data class SetFilter(val filter: TaskFilter) : ListIntent
    data class Complete(val id: String) : ListIntent
}

/** What the screen must act on exactly once. */
sealed interface ListEvent {
    data class ShowMessage(val message: String) : ListEvent
}

/**
 * The store the Task List screen is driven by, in the app's own vocabulary.
 *
 * [render] is the screen: it is called with every state the store publishes.
 * [consume] stands for the one-shot effect layer.
 */
interface TaskListScenario {
    val name: String
    fun intent(intent: ListIntent)

    /**
     * The state the screen is rendering right now.
     *
     * A synchronous snapshot rather than `suspend fun awaitState`: a test scheduler
     * has to advance for a collector to publish, and awaiting inside the test body
     * then races the very scheduler that would publish. The harness settles first and
     * reads after, which is deterministic.
     */
    fun rendered(): ListState
}

/** Mean of injecting a repository — the app's real seam (Room Flow in production). */
interface TaskSource {
    /** Emits the current rows, then every change. Cold: collecting it starts the stream. */
    suspend fun watch(onEmit: suspend (List<TaskRow>) -> Unit)

    /** Writes a completion. The change arrives back through [watch]. */
    suspend fun complete(id: String): Result<Unit>
}

/**
 * What both variants are checked against, in the same order, on the same inputs.
 * Every expectation is a statement about the *screen*, not about the framework.
 */
object ScenarioContract {
    /** The Task List scenarios from the issue, in the order they are asserted. */
    val cases: List<String> = listOf(
        "initial load",
        "filter changes",
        "task completion",
        "Room Flow updates",
        "loading/error states",
        "rapid intents and concurrency",
        "cancellation",
        "screen leave/return lifecycle",
    )
}
