package com.singularity.todo.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import org.junit.jupiter.api.Tag
import java.io.File
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
@Tag("slow")
class McpServerEndToEndTest {

    private val jar = File("build/libs/mcp-server.jar")

    @Test
    fun server_handles_initialize_and_lists_tools() {
        Assume.assumeTrue(
            "mcp-server.jar not built — run `./gradlew :mcp-server:jar` first",
            jar.exists(),
        )

        val process = ProcessBuilder(
            "java",
            "-jar",
            jar.absolutePath,
            "--profile=e2e-${System.currentTimeMillis()}",
        ).redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

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
            val stderr = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
            fail("MCP e2e failed: ${t.message}\n--- server stderr ---\n$stderr")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun server_blocks_until_stdin_closes() {
        Assume.assumeTrue(
            "mcp-server.jar not built — run `./gradlew :mcp-server:jar` first",
            jar.exists(),
        )

        val process = ProcessBuilder(
            "java",
            "-jar",
            jar.absolutePath,
            "--profile=e2e-${System.currentTimeMillis()}",
        ).redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

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

    @Test
    fun create_task_then_read_task_returns_same_title() {
        Assume.assumeTrue(
            "mcp-server.jar not built — run `./gradlew :mcp-server:jar` first",
            jar.exists(),
        )

        val process = ProcessBuilder(
            "java",
            "-jar",
            jar.absolutePath,
            "--profile=e2e-${System.currentTimeMillis()}",
        ).redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

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

            // Step 1: create a task
            val createArgs = mapOf(
                "title" to "E2E roundtrip task ${System.currentTimeMillis()}",
                "priority" to "High",
            )
            val createResult: CallToolResult = runBlocking {
                withTimeout(timeMillis = 5_000) {
                    client.callTool("tasks.create", createArgs)
                }
            }

            assertTrue(
                createResult.isError == false,
                "tasks.create returned isError=true: ${createResult.content}",
            )

            // Extract taskId from structured JSON: {"taskId":"...","title":"...","description":null}
            val createJson = Json.parseToJsonElement(
                (createResult.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text,
            ).jsonObject
            val taskId = createJson["taskId"]?.jsonPrimitive?.content
                ?: fail("tasks.create response missing taskId: ${createResult.content}")

            // Step 2: read it back
            val getResult: CallToolResult = runBlocking {
                withTimeout(timeMillis = 5_000) {
                    client.callTool("tasks.get", mapOf("taskId" to taskId))
                }
            }

            assertTrue(
                getResult.isError == false,
                "tasks.get returned isError=true: ${getResult.content}",
            )

            val getJson = Json.parseToJsonElement(
                (getResult.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text,
            ).jsonObject
            val gotTitle = getJson["title"]?.jsonPrimitive?.content
                ?: fail("tasks.get response missing title: ${getResult.content}")

            assertEquals(
                (createArgs["title"] as String),
                gotTitle,
                "tasks.get should return the same title that was created",
            )

            runBlocking { client.close() }
        } catch (t: Throwable) {
            val stderr = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
            fail("MCP e2e roundtrip failed: ${t.message}\n--- server stderr ---\n$stderr")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
