package com.singularity.todo

import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.test.helpers.testPlatformModule
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.error.NoParameterFoundException
import org.koin.dsl.koinApplication
import org.junit.jupiter.api.Tag
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
            var resolved = 0
            var needsParameter = 0
            for (definition in koin.definitions()) {
                val type = definition.primaryType
                val qualifier = definition.qualifier
                try {
                    koin.get<Any>(type, qualifier)
                    resolved++
                } catch (t: Throwable) {
                    // A definition that takes a runtime parameter — a ViewModel that
                    // needs the id of the entity it edits — cannot be created without
                    // one. That is not a broken graph: the caller supplies it. The
                    // *dependencies* of such a definition are still checked when the
                    // definition is created with a parameter, which the screens do.
                    if (t.causes().any { it is NoParameterFoundException }) {
                        needsParameter++
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
                resolved > 0,
                "the graph resolved no definitions at all ($needsParameter needed a runtime " +
                    "parameter) — the test is vacuous",
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
