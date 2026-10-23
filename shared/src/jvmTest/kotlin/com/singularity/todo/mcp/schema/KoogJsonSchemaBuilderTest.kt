package com.singularity.todo.mcp.schema

import ai.koog.agents.core.tools.SimpleTool
import com.singularity.todo.feature.ai.tools.CreateTaskTool
import com.singularity.todo.feature.ai.tools.GetTaskTool
import com.singularity.todo.feature.ai.tools.ListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.ListTasksTool
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import com.singularity.todo.feature.search.SearchUseCase
import com.singularity.todo.feature.search.query.DefaultSearchQueryResolver
import com.singularity.todo.feature.search.query.ProjectLookup
import com.singularity.todo.feature.search.query.ProjectLookupResult
import com.singularity.todo.feature.search.query.TagLookup
import com.singularity.todo.feature.search.query.TagLookupResult
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeSearchTextIndex
import com.singularity.todo.test.fakes.FakeSettingsRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks in the schema-builder contract that MCP clients depend on:
 *  - The `\$schema` field is NEVER populated (it's the JSON Schema dialect URI).
 *  - Required parameters show up under `required`.
 *  - Optional parameters show up under `properties` but not `required`.
 *  - `anyOf` (e.g. `String?`) renders correctly.
 *  - Lists and nested objects descend recursively.
 *
 * We drive the builder through real tool descriptors (ListTasksTool, GetTaskTool, …)
 * rather than hand-rolling ToolDescriptor instances — the Koog constructor
 * signature isn't part of the public API we want to lock in.
 */
@Tag("fast")
class KoogJsonSchemaBuilderTest {

    @Test
    fun schema_field_is_never_populated_for_any_tool() {
        // The MCP SDK serializes ToolSchema.schema: String? as `$schema`, which
        // MCP clients interpret as the JSON Schema dialect URI. If we ever
        // embed the schema JSON there again, Fred Perry's Todo List (and
        // Claude Code) reject every tool with "unsupported dialect".
        for (tool in dataOnlyTools()) {
            val descriptor = (tool as SimpleTool<*>).descriptor
            val built = KoogJsonSchemaBuilder.build(descriptor)
            assertNull(
                actual = built.schema,
                message = "Tool ${descriptor.name} must not populate \$schema; got '${built.schema}'",
            )
            assertNull(
                actual = built.defs,
                message = "Tool ${descriptor.name} must not populate \$defs; got '${built.defs}'",
            )
        }
    }

    @Test
    fun properties_contain_every_required_and_optional_parameter() {
        val descriptor = (listTasksTool() as SimpleTool<*>).descriptor
        val built = KoogJsonSchemaBuilder.build(descriptor)
        val properties = built.properties ?: error("properties should not be null")
        // projectId, limit
        assertTrue("projectId" in properties.keys, "projectId must be in properties")
        assertTrue("limit" in properties.keys, "limit must be in properties")
    }

    @Test
    fun required_field_lists_required_parameters() {
        val descriptor = (getTaskTool() as SimpleTool<*>).descriptor
        val built = KoogJsonSchemaBuilder.build(descriptor)
        val required = built.required ?: emptyList()
        assertTrue("taskId" in required, "taskId should be required for get_task")
    }

    @Test
    fun type_field_is_always_object() {
        // ToolSchema.type is hard-coded to "object" by the SDK; verify our builder
        // doesn't accidentally null it out through JsonObject construction.
        for (tool in dataOnlyTools()) {
            val descriptor = (tool as SimpleTool<*>).descriptor
            val built = KoogJsonSchemaBuilder.build(descriptor)
            assertEquals(
                expected = "object",
                actual = built.type,
                message = "Tool ${descriptor.name} should have type=object",
            )
        }
    }

    @Test
    fun anyof_renders_for_nullable_fields() {
        // create_task has many String? fields (description, projectId, …) — the
        // schema must use anyOf with [null, string] so MCP clients accept either.
        val descriptor = (createTaskTool() as SimpleTool<*>).descriptor
        val built = KoogJsonSchemaBuilder.build(descriptor)
        val properties = built.properties ?: error("properties should not be null")
        val description = properties["description"] as? JsonObject ?: error("description must be object")
        val anyOf = description["anyOf"] as? JsonArray ?: error("description should use anyOf for nullable")
        val types = anyOf.map { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content }.toSet()
        assertTrue("null" in types, "anyOf must include null type")
        assertTrue("string" in types, "anyOf must include string type")
    }

    @Test
    fun array_property_renders_items() {
        // create_task takes `tagIds: List<String>` — verify the schema marks
        // it as an array with string items, not collapses it to a bare string.
        val descriptor = (createTaskTool() as SimpleTool<*>).descriptor
        val built = KoogJsonSchemaBuilder.build(descriptor)
        val properties = built.properties ?: error("properties should not be null")
        val tagIds = properties["tagIds"] as? JsonObject ?: error("tagIds must be an object")
        assertEquals("array", tagIds["type"]?.jsonPrimitive?.content)
        val items = tagIds["items"] as? JsonObject ?: error("array must have items")
        assertEquals("string", items["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun no_property_carries_schema_key() {
        // Defensive: even if a future schema-string injection slipped past
        // the no-`schema` check, no *property* inside the schema object
        // should ever be named "$schema".
        for (tool in dataOnlyTools()) {
            val descriptor = (tool as SimpleTool<*>).descriptor
            val built = KoogJsonSchemaBuilder.build(descriptor)
            val properties = built.properties ?: continue
            assertFalse(
                actual = "\$schema" in properties.keys,
                message = "Tool ${descriptor.name} inputSchema.properties must not carry \$schema key",
            )
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun listTasksTool() = ListTasksTool(
        taskRepository = FakeTaskRepository(),
        checklistRepository = FakeChecklistRepository(),
        clock = FakeClock(),
        timeZoneProvider = UtcTimeZone,
        tasksSettingsRepository = FakeSettingsRepository(),
    )

    private fun getTaskTool() = GetTaskTool(
        taskRepository = FakeTaskRepository(),
        checklistRepository = FakeChecklistRepository(),
        clock = FakeClock(),
        timeZoneProvider = UtcTimeZone,
        tasksSettingsRepository = FakeSettingsRepository(),
    )

    private fun searchTasksTool(): SearchTasksTool {
        val tasks = FakeTaskRepository()
        val notes = FakeNotesRepository()
        val projects = FakeProjectsRepository()
        val tags = FakeTagsRepository()
        return SearchTasksTool(
            search = SearchUseCase(
                taskRepo = tasks,
                noteRepo = notes,
                projectRepo = projects,
                tagRepo = tags,
                textIndex = FakeSearchTextIndex(tasks, notes, projects, tags),
                queryResolver = DefaultSearchQueryResolver(
                    tagLookup = object : TagLookup {
                        override suspend fun findByName(userId: String, name: String): TagLookupResult? = null
                    },
                    projectLookup = object : ProjectLookup {
                        override suspend fun findByName(userId: String, name: String): ProjectLookupResult? = null
                    },
                    clock = FakeClock(),
                    timeZone = UtcTimeZone,
                ),
            ),
            currentUser = FakeProfileAwareCurrentUser(),
        )
    }

    private fun listLinkedTasksTool() = ListLinkedTasksTool(
        taskRepository = FakeTaskRepository(),
    )

    private fun createTaskTool() = CreateTaskTool(
        proposals = FakeProposalRepository(kotlin.time.Clock.System),
        currentUser = FakeProfileAwareCurrentUser(),
        clock = kotlin.time.Clock.System,
    )

    /** Tools that don't need an LLM executor at construction time. */
    private fun dataOnlyTools(): List<Any> = listOf(
        listTasksTool(),
        getTaskTool(),
        searchTasksTool(),
        listLinkedTasksTool(),
        createTaskTool(),
    )

    /** The zone the tests read dates in — UTC, so a date is a date. */
    private object UtcTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TimeZone.UTC
    }
}
