package com.singularity.todo.mcp.introspection

import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.Koin

internal object ScreenRegistry {
    private val capabilities: List<ScreenCapabilityInfo> =
        listOf(
            ScreenCapabilityInfo(
                id = TaskDetailScreenSession.SCREEN_ID,
                description =
                    "Inspect and drive the Task Detail screen headlessly via its canonical " +
                        "introspection adapter.",
                params =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "taskId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Task id to open.")
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add(JsonPrimitive("taskId")) })
                        put("additionalProperties", false)
                    },
            ),
        )

    fun capabilities(): List<ScreenCapabilityInfo> = capabilities

    fun list(): JsonObject =
        buildJsonObject {
            put(
                "screens",
                buildJsonArray {
                    capabilities.forEach { cap ->
                        add(
                            buildJsonObject {
                                put("id", cap.id)
                                put("description", cap.description)
                                put("params", cap.params)
                            },
                        )
                    }
                },
            )
        }

    fun capability(id: String): ScreenCapabilityInfo? = capabilities.firstOrNull { it.id == id }

    fun open(
        screenId: String,
        params: JsonObject,
        koin: Koin,
    ): ScreenSession =
        when (screenId) {
            TaskDetailScreenSession.SCREEN_ID -> {
                val unknownKeys = params.keys - setOf("taskId")
                if (unknownKeys.isNotEmpty()) {
                    throw InspectorException(
                        InspectorErrorCode.INVALID_ARGUMENTS,
                        "Unknown parameter(s) for screen '$screenId': ${unknownKeys.sorted().joinToString(", ")}.",
                        details = buildJsonObject { put("screen", screenId) },
                    )
                }
                val taskIdElement =
                    params["taskId"]
                        ?: throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "Missing required parameter 'taskId' for screen '$screenId'.",
                            details = buildJsonObject { put("screen", screenId) },
                        )
                val taskIdPrimitive = taskIdElement as? kotlinx.serialization.json.JsonPrimitive
                val taskIdRaw =
                    if (taskIdPrimitive != null && taskIdPrimitive.isString) {
                        taskIdPrimitive.content
                    } else {
                        throw InspectorException(
                            InspectorErrorCode.INVALID_ARGUMENTS,
                            "Parameter 'taskId' for screen '$screenId' must be a JSON string.",
                            details = buildJsonObject { put("screen", screenId) },
                        )
                    }
                if (taskIdRaw.isBlank()) {
                    throw InspectorException(
                        InspectorErrorCode.INVALID_ARGUMENTS,
                        "Parameter 'taskId' for screen '$screenId' must be a non-empty string.",
                        details = buildJsonObject { put("screen", screenId) },
                    )
                }
                TaskDetailScreenSession.open(TaskId(taskIdRaw), params, koin)
            }

            else -> {
                throw InspectorException(
                    InspectorErrorCode.UNKNOWN_SCREEN,
                    "Unknown or unsupported screen '$screenId'.",
                    details =
                        buildJsonObject {
                            put("screen", screenId)
                            put(
                                "supportedScreens",
                                buildJsonArray {
                                    capabilities.forEach { add(JsonPrimitive(it.id)) }
                                },
                            )
                        },
                )
            }
        }
}
