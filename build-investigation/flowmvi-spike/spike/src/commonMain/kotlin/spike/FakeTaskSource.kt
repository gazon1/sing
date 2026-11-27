package spike

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import spike.scenario.ListState
import spike.scenario.TaskRow
import spike.scenario.TaskSource

/**
 * One Task List repository, both variants subscribe to it. A fake rather than a mock:
 * the scenario is about what each framework does with an emitting flow, so the flow has
 * to be real.
 *
 * Mirrors the production shape — Room `Flow` → repository → ViewModel — including the
 * "a write arrives back through the same stream" part that makes the completion and
 * Room-update scenarios meaningful.
 */
class FakeTaskSource(
    initial: List<TaskRow> = listOf(
        TaskRow("1", "Write the spike", completed = false),
        TaskRow("2", "Read the assessment", completed = true),
    ),
    /** Lets a test make the repository fail, for the loading/error scenario. */
    var failOnComplete: Boolean = false,
) : TaskSource {

    private val rows = MutableStateFlow(initial)
    val emitted: StateFlow<List<TaskRow>> = rows.asStateFlow()

    /** Buffered so a subscriber that appears late still gets the current value. */
    private val gate = Channel<Unit>(Channel.CONFLATED)

    override suspend fun watch(onEmit: suspend (List<TaskRow>) -> Unit) {
        rows.collect { onEmit(it) }
    }

    override suspend fun complete(id: String): Result<Unit> {
        if (failOnComplete) return Result.failure(IllegalStateException("repository is unavailable"))
        rows.update { current ->
            current.map { if (it.id == id) it.copy(completed = !it.completed) else it }
        }
        return Result.success(Unit)
    }

    /** A change nobody asked for — the "Room Flow updates" case. */
    fun externalInsert(row: TaskRow) = rows.update { it + row }

    private val firstEmission = CompletableDeferred<Unit>()

    init {
        // Nothing to gate on: the flow is a `StateFlow`, so a subscriber always sees
        // the current value. Kept so the helper does not lie about what it waits for.
        firstEmission.complete(Unit)
    }

    /** Suspends until the source has emitted at least once. */
    suspend fun awaitFirstEmission(): Unit = withTimeout(5_000) { firstEmission.await() }
}
