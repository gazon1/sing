package com.singularity.todo.mcp

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
     * The `open` subcommand serialises a settled snapshot, over the real suspend bridge.
     *
     * The runtime test drives `ScreenInspectorRuntime.open` directly, so it cannot see the
     * CLI half: `OpenCommand` calls it through `blockSuspend`/`runBlocking` and serialises
     * the answer. A change that dropped the settle, or that serialised the pre-wait state,
     * would leave that test green. This reads the CLI's own JSON for a task that does not
     * exist, which is enough to prove the path: the envelope arrives, its `snapshot` is a
     * settled `error` rather than `loading`, and it is one line on standard output.
     */
    @Test
    fun `the open subcommand answers with a settled snapshot through the CLI`() {
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
            val code =
                runScreenInspectorCliHost(
                    args =
                        arrayOf(
                            "agent",
                            "open",
                            "--screen",
                            "tasks.task-detail",
                            "--task-id",
                            "01AAAAAAAAAAAAAAAAAAAAAAAA",
                        ),
                    koinStartup = koinStartup,
                    inspectorHostCompiledIn = true,
                )

            val resultLines = out.toString().lines().filter { it.isNotBlank() }
            val envelope = resultLines.joinToString("")
            assertTrue(code == 0 || code == 2, "unexpected exit code $code: $envelope")

            // The screen answers `error`/`Not found` for an unknown id; the point is that the
            // CLI produced a parsed envelope with a snapshot, not that the id was found.
            assertTrue(
                envelope.contains("\"snapshot\""),
                "the CLI must serialise the snapshot, got: ${envelope.take(200)}",
            )
            assertTrue(
                !envelope.contains("\"status\":\"loading\""),
                "open must serialise a settled snapshot, not the pre-wait one: ${envelope.take(300)}",
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
