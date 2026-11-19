package com.singularity.todo.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("fast")
class ScreenIntrospectionTest {
    @Test
    fun `an unknown action is refused with the supported actions`() {
        val screen =
            ScreenIntrospection(
                "screen.test",
                listOf(
                    action(
                        "set_priority",
                        required = listOf("priority"),
                        properties =
                            mapOf("priority" to stringProperty()),
                    ),
                ),
            )
        val result = screen.validate(ScreenState(status = "loaded"), "delete_task", JsonObject(emptyMap()))

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Unknown action 'delete_task'") == true,
            "the refusal names the unknown action: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `an action the screen cannot act on is refused`() {
        val screen =
            ScreenIntrospection(
                "screen.test",
                listOf(
                    ScreenAction(
                        name = "set_priority",
                        summary = "Set the task's priority.",
                        requirement = "the screen has to be showing a task",
                        inputSchema =
                            buildJsonObject {
                                put("type", "object")
                                put("required", JsonArray(listOf(JsonPrimitive("priority"))))
                                put("properties", buildJsonObject { put("priority", stringProperty()) })
                                put("additionalProperties", false)
                            },
                        isAvailable = { false },
                    ),
                ),
            )
        val result = screen.validate(ScreenState(status = "loading"), "set_priority", JsonObject(emptyMap()))

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("not available") == true,
            "the refusal names the availability problem: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `a missing required property is refused`() {
        val screen =
            ScreenIntrospection(
                "screen.test",
                listOf(
                    action(
                        "set_priority",
                        required = listOf("priority"),
                        properties =
                            mapOf("priority" to stringProperty()),
                    ),
                ),
            )
        val result = screen.validate(ScreenState(status = "loaded"), "set_priority", JsonObject(emptyMap()))

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("priority") == true,
            "the refusal names the missing field: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `an input with a property the schema does not define is refused`() {
        val screen =
            ScreenIntrospection(
                "screen.test",
                listOf(
                    action(
                        "set_priority",
                        required = listOf("priority"),
                        properties =
                            mapOf("priority" to stringProperty()),
                    ),
                ),
            )
        val result =
            screen.validate(
                ScreenState(status = "loaded"),
                "set_priority",
                buildJsonObject {
                    put("priority", "high")
                    put("force", true)
                },
            )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("force") == true,
            "the refusal names the unexpected property: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `an optional property the agent sends with a wrong type is refused`() {
        val screen =
            ScreenIntrospection(
                "screen.test",
                listOf(
                    action(
                        "set_priority",
                        properties =
                            mapOf("priority" to stringProperty()),
                    ),
                ),
            )
        val result =
            screen.validate(
                ScreenState(status = "loaded"),
                "set_priority",
                buildJsonObject {
                    put("priority", 42)
                },
            )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("priority") == true,
            "the refusal names the field: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `a valid input is accepted`() {
        val screen =
            ScreenIntrospection(
                "screen.test",
                listOf(
                    action(
                        "set_priority",
                        required = listOf("priority"),
                        properties =
                            mapOf("priority" to stringProperty(listOf("none", "low", "high"))),
                    ),
                ),
            )
        val result =
            screen.validate(
                ScreenState(status = "loaded"),
                "set_priority",
                buildJsonObject {
                    put("priority", "high")
                },
            )

        assertTrue(result.isSuccess, "the valid input is accepted: $result")
    }

    @Test
    fun `an enum value outside the published set is refused`() {
        val screen =
            ScreenIntrospection(
                "screen.test",
                listOf(
                    action(
                        "set_priority",
                        required = listOf("priority"),
                        properties =
                            mapOf("priority" to stringProperty(listOf("none", "low", "high"))),
                    ),
                ),
            )
        val result =
            screen.validate(
                ScreenState(status = "loaded"),
                "set_priority",
                buildJsonObject {
                    put("priority", "extreme")
                },
            )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("high") == true,
            "the refusal lists the accepted values: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `an action whose schema sets a valid string enum is accepted`() {
        val screen =
            ScreenIntrospection(
                "screen.test",
                listOf(
                    action(
                        "set_priority",
                        required = listOf("priority"),
                        properties =
                            mapOf("priority" to stringProperty(listOf("none", "low", "high"))),
                    ),
                ),
            )
        val result =
            screen.validate(
                ScreenState(status = "loaded"),
                "set_priority",
                buildJsonObject {
                    put("priority", "low")
                },
            )

        assertTrue(result.isSuccess, "the valid enum input is accepted: $result")
    }

    @Test
    fun `a blank title is refused by the published limits`() {
        val screen =
            ScreenIntrospection(
                "screen.test",
                listOf(
                    action(
                        "add_checklist_item",
                        required = listOf("title"),
                        properties =
                            mapOf("title" to stringProperty(minLength = 1, pattern = "\\S")),
                    ),
                ),
            )
        val result =
            screen.validate(
                ScreenState(status = "loaded"),
                "add_checklist_item",
                buildJsonObject {
                    put("title", "   ")
                },
            )

        assertTrue(result.isFailure)
    }

    @Test
    fun `an action whose schema uses an unsupported keyword is refused`() {
        val problem =
            runCatching {
                ScreenAction(
                    name = "set_priority",
                    summary = "Set the task's priority.",
                    requirement = "the screen has to be showing a task",
                    inputSchema =
                        buildJsonObject {
                            put("type", "object")
                            put("required", JsonArray(listOf(JsonPrimitive("priority"))))
                            put("properties", buildJsonObject { put("priority", stringProperty()) })
                            put("maxLength", 10)
                            put("additionalProperties", false)
                        },
                )
            }.exceptionOrNull()?.message

        assertTrue(problem?.contains("maxLength") == true, "the refusal names the keyword: $problem")
    }

    @Test
    fun `an action whose schema uses an unsupported property type is refused`() {
        val problem =
            runCatching {
                ScreenAction(
                    name = "add_checklist_item",
                    summary = "Add a checklist item.",
                    requirement = "the screen has to be showing a task",
                    inputSchema =
                        buildJsonObject {
                            put("type", "object")
                            put("required", JsonArray(listOf(JsonPrimitive("count"))))
                            put(
                                "properties",
                                buildJsonObject { put("count", buildJsonObject { put("type", "integer") }) },
                            )
                            put("additionalProperties", false)
                        },
                )
            }.exceptionOrNull()?.message

        assertTrue(problem?.contains("count") == true, "the refusal names the field: $problem")
    }

    @Test
    fun `an action whose schema sets a malformed pattern is refused`() {
        val problem =
            runCatching {
                ScreenAction(
                    name = "add_checklist_item",
                    summary = "Add a checklist item.",
                    requirement = "the screen has to be showing a task",
                    inputSchema =
                        buildJsonObject {
                            put("type", "object")
                            put("required", JsonArray(listOf(JsonPrimitive("title"))))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "title",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("pattern", "[")
                                        },
                                    )
                                },
                            )
                            put("additionalProperties", false)
                        },
                )
            }.exceptionOrNull()?.message

        assertTrue(problem?.contains("pattern") == true, "the refusal names the pattern: $problem")
    }

    @Test
    fun `an action whose schema sets minLength as a string is refused`() {
        val problem =
            runCatching {
                ScreenAction(
                    name = "add_checklist_item",
                    summary = "Add a checklist item.",
                    requirement = "the screen has to be showing a task",
                    inputSchema =
                        buildJsonObject {
                            put("type", "object")
                            put("required", JsonArray(listOf(JsonPrimitive("title"))))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "title",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("minLength", "3")
                                        },
                                    )
                                },
                            )
                            put("additionalProperties", false)
                        },
                )
            }.exceptionOrNull()?.message

        assertTrue(problem?.contains("minLength") == true, "the refusal names minLength: $problem")
    }

    @Test
    fun `an action whose schema sets pattern as a non-string is refused`() {
        val problem =
            runCatching {
                ScreenAction(
                    name = "add_checklist_item",
                    summary = "Add a checklist item.",
                    requirement = "the screen has to be showing a task",
                    inputSchema =
                        buildJsonObject {
                            put("type", "object")
                            put("required", JsonArray(listOf(JsonPrimitive("title"))))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "title",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("pattern", 42)
                                        },
                                    )
                                },
                            )
                            put("additionalProperties", false)
                        },
                )
            }.exceptionOrNull()?.message

        assertTrue(problem?.contains("pattern") == true, "the refusal names the pattern: $problem")
    }

    @Test
    fun `an action whose schema sets an enum with a non-string is refused`() {
        val problem =
            runCatching {
                ScreenAction(
                    name = "set_priority",
                    summary = "Set the task's priority.",
                    requirement = "the screen has to be showing a task",
                    inputSchema =
                        buildJsonObject {
                            put("type", "object")
                            put("required", JsonArray(listOf(JsonPrimitive("priority"))))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "priority",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("enum", JsonArray(listOf(JsonPrimitive("high"), JsonPrimitive(42))))
                                        },
                                    )
                                },
                            )
                            put("additionalProperties", false)
                        },
                )
            }.exceptionOrNull()?.message

        assertTrue(problem?.contains("enum") == true, "the refusal names the enum: $problem")
    }

    @Test
    fun `an action whose required property is not declared in the properties is refused`() {
        val problem =
            runCatching {
                action("set_priority", required = listOf("ghost"))
            }.exceptionOrNull()?.message

        assertTrue(problem?.contains("ghost") == true, "the refusal names the required property: $problem")
    }

    @Test
    fun `a screen that declares two actions with the same name is refused`() {
        val problem =
            runCatching {
                ScreenIntrospection("screen.test", listOf(action("dup"), action("dup")))
            }.exceptionOrNull()?.message

        assertTrue(problem?.contains("dup") == true, "the refusal names the action: $problem")
    }

    @Test
    fun `mutating the source action list after creation does not bypass uniqueness`() {
        val actions = mutableListOf(action("a"), action("b"))
        val screen = ScreenIntrospection("screen.test", actions)
        actions.add(action("a"))

        val described = screen.describe(ScreenState(status = "loaded"))
        val names =
            (described["actions"] as JsonArray).map { element ->
                ((element as JsonObject)["name"] as JsonPrimitive).content
            }
        assertTrue(names == listOf("a", "b"), "the contract keeps its own action list: $names")
    }

    private fun action(
        name: String,
        required: List<String> = emptyList(),
        properties: Map<String, JsonObject> = emptyMap(),
    ): ScreenAction =
        ScreenAction(
            name = name,
            summary = "Action $name.",
            requirement = "the screen has to be showing a task",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    put("required", JsonArray(required.map { JsonPrimitive(it) }))
                    put("properties", JsonObject(properties))
                    put("additionalProperties", false)
                },
        )

    private fun stringProperty(
        enumValues: List<String>? = null,
        minLength: Int? = null,
        pattern: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("type", "string")
            enumValues?.let { put("enum", JsonArray(enumValues.map { JsonPrimitive(it) })) }
            minLength?.let { put("minLength", it) }
            pattern?.let { put("pattern", it) }
        }
}
