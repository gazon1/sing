package com.singularity.todo.mcp

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The agent CLI's standard output carries its JSON result and nothing else.
 *
 * ## The defect this pins
 *
 * `agent open` printed the application's own log — `kotlin-logging: initializing…`,
 * `Debug: (AuthGateway) …` — to standard output **ahead of the JSON**, so the documented
 * `… | jq` pipe failed with a parse error on every command that started Koin. The MCP
 * server already reserved standard output for its protocol channel; the CLI branch of
 * `runToolServer` never applied the same guard.
 *
 * ## Why a class of its own
 *
 * The assertion reads `System.out` after a command has run, and the suite executes classes
 * concurrently. A neighbour writing to standard output while this test reads it would make
 * the result meaningless, so the class is `@Isolated` and single-threaded — the same
 * contract `ProtocolStdoutTest` uses for the same reason.
 *
 * ## Why `sessions` and not `help`
 *
 * `help` and `screens` answer before the container starts, so the application's log never
 * initialises and the test would pass even with the defect present. `sessions` builds a
 * runtime, which is the path where the log is the thing that used to reach standard output.
 */
@Tag("slow")
@Tag("slow:data")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@Suppress("NoRunBlocking") // the CLI command blocks until it answers; that is its contract
class AgentCliStdoutTest {
    @Test
    fun `a command that starts the container leaves only the JSON result on stdout`() {
        val realOut = System.out
        val out = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true))

        val temporaryHome =
            kotlin.io.path
                .createTempDirectory("agent-cli-stdout")
                .toFile()
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", temporaryHome.absolutePath)
        try {
            val code =
                runScreenInspectorCliHost(
                    args = arrayOf("agent", "sessions"),
                    koinStartup = koinStartup,
                    inspectorHostCompiledIn = true,
                )
            assertEquals(0, code, "the command must succeed; stdout so far=$out")

            val resultLines = out.toString().lines().filter { it.isNotBlank() }
            assertEquals(
                1,
                resultLines.size,
                "standard output must carry exactly the one JSON result line, got: $resultLines",
            )
            assertTrue(
                resultLines.single().startsWith("{\"ok\":true"),
                "the result line must be the JSON envelope, got: ${resultLines.single().take(120)}",
            )
        } finally {
            System.setOut(realOut)
            if (previousHome != null) {
                System.setProperty("user.home", previousHome)
            } else {
                System.clearProperty("user.home")
            }
            temporaryHome.deleteRecursively()
            runCatching { stopKoin() }
        }
    }

    /**
     * The `open` subcommand serialises a **settled, loaded** snapshot through the CLI.
     *
     * The runtime test drives `ScreenInspectorRuntime.open` directly, so it cannot see the
     * CLI half: `OpenCommand` calls it through `blockSuspend`/`runBlocking` and serialises
     * the answer. A change that dropped the settle would leave that test green.
     *
     * A missing task id would not do it either: that screen reaches `error` on its own, so
     * the assertion would hold without any wait. This seeds a real task and requires
     * `"status":"loaded"` in the CLI's own JSON — only the settle produces that in a
     * one-command process.
     */
    @Test
    fun `the open subcommand answers with a loaded snapshot through the CLI`() {
        val realOut = System.out
        val out = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true))

        val temporaryHome =
            kotlin.io.path
                .createTempDirectory("agent-cli-open")
                .toFile()
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", temporaryHome.absolutePath)
        try {
            // Start the container before the CLI runs, seed one task, then let the CLI find
            // the running container instead of starting its own — the graph the host drives
            // is the same either way, and this is the only way to put a task in front of it.
            koinStartup.start(fixedProfileId = null)
            val taskRepository: TaskRepository = GlobalContext.get().get()
            val userId = runBlocking { taskRepository.currentUserId() }
            val taskId = TaskId("cli-loaded-task")
            runBlocking {
                taskRepository.create(
                    Task(
                        id = taskId,
                        title = "Loaded through the CLI",
                        priority = TaskPriority.None,
                        userId = userId,
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now(),
                    ),
                )
            }.getOrThrow()

            val code =
                runScreenInspectorCliHost(
                    args = arrayOf("agent", "open", "--screen", "tasks.task-detail", "--task-id", taskId.value),
                    koinStartup = koinStartup,
                    inspectorHostCompiledIn = true,
                )

            val resultLines = out.toString().lines().filter { it.isNotBlank() }
            assertEquals(1, resultLines.size, "standard output must be one JSON line: $resultLines")
            val envelope = resultLines.single()
            assertEquals(0, code, "open must succeed: $envelope")
            assertTrue(
                envelope.contains("\"status\":\"loaded\""),
                "open must serialise a settled, loaded snapshot: ${envelope.take(300)}",
            )
        } finally {
            System.setOut(realOut)
            if (previousHome != null) {
                System.setProperty("user.home", previousHome)
            } else {
                System.clearProperty("user.home")
            }
            temporaryHome.deleteRecursively()
            runCatching { stopKoin() }
        }
    }

    /** The real container the CLI builds, over the application's own module list. */
    private val koinStartup =
        ToolServerKoinStartup { fixedProfileId ->
            startKoin { modules(toolServerModules(fixedProfileId)) }
        }
}
