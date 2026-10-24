package com.singularity.todo.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end test: spawns the real mcp-server.jar as a subprocess and connects to it via
 * [StdioClientTransport]. Skipped when the fat JAR has not been built yet (e.g., clean
 * checkout without `./gradlew :mcp-server:jar` first).
 *
 * Covers:
 *   1. The JVM process stays alive after `Server.createSession(transport)` (the historical
 *      "Connection closed" bug — session was exiting without a blocking primitive).
 *   2. `initialize` roundtrip — serverInfo name matches.
 *   3. `tools/list` returns at least one registered tool.
 */
// Every test here drives a real server process over a pipe, and the calls that do
// the driving are blocking by nature: the answer comes when the server answers. The
// alternative — a scope that is launched and never awaited — would assert nothing.
//
// The note is above the annotations rather than between them: the tag-coverage gate
// reads the *contiguous* annotation block above the class header, so a comment in
// the middle of the block hides the tag from it — and an untagged class is one that
// no CI population ever selects.
@Tag("slow")
@Tag("slow:data")
@Suppress("NoRunBlocking") // the test drives a real process over a pipe; the calls block by nature
class McpServerEndToEndTest {

    // Absolute path: workingDir for this test suite is build/allure-results (set in
    // mcp-server/build.gradle.kts), so a relative path would resolve to
    // build/allure-results/build/libs/mcp-server.jar which does not exist.
    // The absolute path is passed as a system property from the Gradle build file.
    private val jar = File(
        System.getProperty("mcp-server.jar.path")
            ?: error("mcp-server.jar.path system property not set — run :mcp-server:jar first"),
    )

    /**
     * Spawns the fat JAR against an ISOLATED `-Duser.home` temp directory.
     * Without this the subprocess inherits the developer's real home, runs the
     * Room migrations against (and writes test tasks into) the developer's own
     * `~/.singularity-todo/singularity-todo.db` — on 2026-10-04 that both
     * exposed the v31→v32 migration bug and would have polluted real data.
     */
    private fun spawnServer(): Process = spawnServerWithHome().first

    /**
     * Spawns the fat JAR against an ISOLATED `-Duser.home` temp directory, and
     * returns that directory as well.
     *
     * The directory is where the server writes what it observed: the registry
     * snapshot and the tool-call records. A test that cannot look at them cannot
     * say that the server recorded anything.
     */
    private fun spawnServerWithHome(): Pair<Process, Path> {
        val e2eHome = java.nio.file.Files.createTempDirectory("mcp-e2e-home")
        val process = ProcessBuilder(
            "java",
            "-Duser.home=${e2eHome.toAbsolutePath()}",
            "-jar",
            jar.absolutePath,
            "--profile=e2e-${System.currentTimeMillis()}",
        ).redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        return process to e2eHome
    }

    @Test
    fun server_handles_initialize_and_lists_tools() {
        assumeTrue(
            jar.exists(),
            "mcp-server.jar not built — run `./gradlew :mcp-server:jar` first",
        )

        val process = spawnServer()

        try {
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
            )

            val client = Client(
                clientInfo = Implementation(name = "singularity-e2e-test", version = "0.0.1"),
            )

            runBlocking {
                withTimeout(timeMillis = 15_000) {
                    client.connect(transport)
                }
            }

            val serverInfo = client.serverVersion
            assertNotNull(serverInfo, "serverInfo should be present after initialize")
            assertEquals("singularity-todo", serverInfo.name)

            val toolNames = runBlocking {
                withTimeout(timeMillis = 5_000) {
                    client.listTools().tools.map { it.name }
                }
            }
            assertTrue(toolNames.isNotEmpty(), "Expected at least one registered tool, got $toolNames")

            // Regression: every tool's inputSchema must NOT carry a `$schema` key whose
            // value is the schema JSON serialized as a string. Some MCP clients (Fred Perry
            // Todo List, Claude Code) interpret `$schema` as the JSON Schema dialect URI
            // and reject the tool with "JSON Schema declares an unsupported dialect" if
            // the value is not a valid URI.
            runBlocking {
                withTimeout(timeMillis = 5_000) {
                    val listed = client.listTools().tools
                    for (tool in listed) {
                        val schemaProp = tool.inputSchema.schema
                        assertEquals(
                            expected = null,
                            actual = schemaProp,
                            message = "Tool ${tool.name} inputSchema still carries \$schema = $schemaProp",
                        )
                    }
                }
            }

            runBlocking { client.close() }
        } catch (t: Throwable) {
            // Destroy first: readText() on an ALIVE child's stderr blocks until
            // EOF. Kill the process so the pipe closes and the buffered output
            // can be drained (regression 2026-10-04: this hung the whole suite).
            if (process.isAlive) process.destroyForcibly()
            val stderr = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
            fail("MCP e2e failed: ${t.message}\n--- server stderr ---\n$stderr")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun server_blocks_until_stdin_closes() {
        assumeTrue(
            jar.exists(),
            "mcp-server.jar not built — run `./gradlew :mcp-server:jar` first",
        )

        val process = spawnServer()

        try {
            // With no traffic on stdin and no client connected, the JVM must remain alive
            // waiting for `done.join()` to fire. If the historic bug is back, the process
            // exits within ~1s.
            Thread.sleep(3_000)
            assertTrue(
                actual = process.isAlive,
                message = "Server exited prematurely — done.join() likely returned without a client",
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * The whole chain, over the real protocol: describe the toolbox, call a tool, and
     * read what the server wrote about it.
     *
     * The pieces were tested one at a time — the payload shape in ToolboxToolTest, the
     * snapshot shape in RegistrySnapshotTest, the report's reading of both in the
     * Python tests — and the chain itself was never exercised. A chain of correct
     * parts is not a working chain: the parts can each be right while the server
     * writes them somewhere the report does not look.
     *
     * So this test does the round trip: the server writes, and the report reads.
     */
    @Test
    fun the_toolbox_describes_itself_then_records_the_call_it_made() {
        assumeTrue(
            jar.exists(),
            "mcp-server.jar not built — run `./gradlew :mcp-server:jar` first",
        )

        val (process, home) = spawnServerWithHome()

        try {
            val client = connect(process)
            val toolset = describeTheToolbox(client)
            assertNotNull(
                toolset["surfaceContractVersion"]?.jsonPrimitive?.content,
                "the toolbox did not say which contract version it speaks; an agent " +
                    "cannot tell a newer server from an older one",
            )

            callAListTool(client)

            val dataDirectory = home.resolve(".singularity-todo").resolve("tool-calls")
            assertTheServerWroteItsObservations(dataDirectory)

            assertTheReportSeesTheCall(usageReport(dataDirectory))

            runBlocking { client.close() }
        } catch (t: Throwable) {
            if (process.isAlive) process.destroyForcibly()
            val stderr = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
            fail("MCP toolbox chain failed: ${t.message}\n--- server stderr ---\n$stderr")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Connects to the spawned server over the stdio transport. */
    private fun connect(process: Process): Client {
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
        )
        val client = Client(
            clientInfo = Implementation(name = "singularity-e2e-test", version = "0.0.1"),
        )
        runBlocking {
            withTimeout(timeMillis = 15_000) {
                client.connect(transport)
            }
        }
        return client
    }

    /** Step 1: the toolbox describes itself, and says which contract it speaks. */
    private fun describeTheToolbox(client: Client): JsonObject {
        val described: CallToolResult = runBlocking {
            withTimeout(timeMillis = 5_000) {
                client.callTool("describe_toolbox", emptyMap())
            }
        }
        assertTrue(
            described.isError == false,
            "describe_toolbox returned isError=true: ${described.content}",
        )
        val text = (described.content.first() as TextContent).text
        val toolset = Json.parseToJsonElement(text).jsonObject["toolset"]?.jsonObject
        assertNotNull(toolset, "describe_toolbox reported no toolset")
        return toolset
    }

    /** Step 2: call a tool. */
    private fun callAListTool(client: Client) {
        val called: CallToolResult = runBlocking {
            withTimeout(timeMillis = 5_000) {
                client.callTool("list_tasks", emptyMap())
            }
        }
        assertTrue(
            called.isError == false,
            "list_tasks returned isError=true: ${called.content}",
        )
    }

    /** Step 3: the server wrote down what the toolbox was and what it did. */
    private fun assertTheServerWroteItsObservations(dataDirectory: Path) {
        val registry = dataDirectory.resolve("registry.json")
        assertTrue(
            registry.toFile().isFile,
            "the registry snapshot is not in $registry",
        )
        val records = dataDirectory.toFile().listFiles { file -> file.name.endsWith(".jsonl") }
        assertTrue(
            records != null && records.isNotEmpty(),
            "no tool-call record was written to $dataDirectory",
        )
    }

    /** Step 4: the report reads what the server wrote. */
    private fun assertTheReportSeesTheCall(answer: JsonObject) {
        val tools = answer["tools"]?.jsonObject ?: JsonObject(emptyMap())
        val listTasks = tools["list_tasks"]?.jsonObject
        assertNotNull(
            listTasks,
            "the report does not see the call the server recorded: ${answer["tools"]}",
        )
        assertEquals(1, listTasks["calls"]?.jsonPrimitive?.content?.toInt() ?: -1)
    }

    /**
     * The local report's answer about a directory of records, as JSON.
     *
     * The report is a script, so it is run as a program.
     */
    private fun usageReport(dataDirectory: Path): JsonObject {
        val script = repoRoot().resolve("scripts").resolve("mcp-toolbox-usage.py")
        assertTrue(script.toFile().isFile, "no usage report at $script")

        val process = ProcessBuilder(
            "python3",
            script.toString(),
            "--data-dir",
            dataDirectory.toString(),
            "--json",
        )
            .directory(repoRoot().toFile())
            .redirectErrorStream(false)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor()
        assertEquals(0, finished, "the usage report failed:\n$output")
        return Json.parseToJsonElement(output).jsonObject
    }

    /** The repository root, found by walking up from the working directory. */
    private fun repoRoot(): Path = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) {
        it.parent
    }
        .firstOrNull { it.resolve("scripts").resolve("mcp-toolbox-usage.py").toFile().isFile }
        ?: error("could not find the repository root from ${System.getProperty("user.dir")}")

    /**
     * The write path proposes: `create_task` stores a proposal, and the task comes into
     * being when the user confirms it.
     *
     * The test used to assert the other shape — that the task the tool created could be
     * read back with the same title. That contract ended when the write path was given
     * one shape (`2026-10-22-the-write-path-asks-first`): the model proposes, the user
     * decides. So the roundtrip this test proves is the one the system has:
     *
     * - the tool call reached the app and was stored — as a proposal, in the app's own
     *   database, holding the id the task will have once it is confirmed;
     * - the task itself does not exist yet, and the read surface says so.
     *
     * The proposal is read from the database the server wrote, because no tool reads
     * proposals: they belong to the user's confirmation screen, not to the model.
     */
    @Test
    fun create_task_proposes_the_task_and_the_task_waits_for_the_user() {
        assumeTrue(
            jar.exists(),
            "mcp-server.jar not built — run `./gradlew :mcp-server:jar` first",
        )

        val (process, home) = spawnServerWithHome()

        try {
            val client = connect(process)

            // Step 1: the tool proposes.
            val title = "E2E roundtrip task ${System.currentTimeMillis()}"
            val createResult = callTool(
                client,
                "create_task",
                mapOf("title" to title, "priority" to "High"),
            )

            assertTrue(
                createResult.isError == false,
                "create_task returned isError=true: ${createResult.content}",
            )

            // The answer names a proposal and no task: nothing was created, and an id
            // would name a task that does not exist.
            val createJson = jsonOf(createResult)
            assertEquals(
                "true",
                createJson["proposalCreated"]?.jsonPrimitive?.content,
                "create_task did not report a proposal: ${createResult.content}",
            )
            val proposalId = createJson["proposalId"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(
                proposalId.isNotBlank(),
                "create_task returned no proposal id: ${createResult.content}",
            )
            assertEquals(
                title,
                createJson["title"]?.jsonPrimitive?.content,
                "create_task lost the title it was given",
            )
            assertTrue(
                createJson["taskId"] == null,
                "create_task named a task id, and no task was created: ${createResult.content}",
            )

            // Step 2: the proposal is in the app's database, holding the id the task
            // will have when the user says yes.
            val reservedTaskId = reservedTaskId(home, proposalId)

            // Step 3: the task is not there yet — nobody confirmed it.
            val getResult = callTool(client, "get_task", mapOf("taskId" to reservedTaskId))

            assertTrue(
                getResult.isError == false,
                "get_task returned isError=true: ${getResult.content}",
            )

            val getJson = jsonOf(getResult)
            assertEquals(
                "false",
                getJson["found"]?.jsonPrimitive?.content,
                "the task exists before the user confirmed it: ${getResult.content}",
            )

            runBlocking { client.close() }
        } catch (t: Throwable) {
            // Destroy first: readText() on an ALIVE child's stderr blocks until
            // EOF. Kill the process so the pipe closes and the buffered output
            // can be drained (regression 2026-10-04: this hung the whole suite).
            if (process.isAlive) process.destroyForcibly()
            val stderr = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
            fail("MCP e2e roundtrip failed: ${t.message}\n--- server stderr ---\n$stderr")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Calls a tool and returns the answer it gave. */
    private fun callTool(client: Client, name: String, arguments: Map<String, String>): CallToolResult =
        runBlocking {
            withTimeout(timeMillis = 5_000) {
                client.callTool(name, arguments)
            }
        }

    /** The JSON a tool answered with. */
    private fun jsonOf(result: CallToolResult): JsonObject = Json.parseToJsonElement(
        (result.content.first() as TextContent).text,
    ).jsonObject

    /**
     * The id the proposal reserved for the task, read from the database the server
     * wrote. The table and its columns are the ones `ProposalDao` declares.
     */
    private fun reservedTaskId(home: Path, proposalId: String): String {
        val database = home.resolve(".singularity-todo").resolve("singularity-todo.db")
        assertTrue(database.toFile().isFile, "the server did not create its database at $database")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            connection.prepareStatement("SELECT target_id FROM ai_proposal WHERE id = ?").use { statement ->
                statement.setString(1, proposalId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next(), "no proposal row with id $proposalId in $database")
                    return rows.getString(1)
                }
            }
        }
    }
}
