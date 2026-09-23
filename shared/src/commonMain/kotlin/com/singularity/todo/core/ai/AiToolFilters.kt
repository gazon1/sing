package com.singularity.todo.core.ai

import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.llm.LLModel
import com.singularity.todo.core.llm.KnownModels
import com.singularity.todo.core.llm.resolveModel

/**
 * Returns the tool ID (the `NAME` constant) for any Koog [Tool].
 *
 * Each tool class defines `NAME` as a `const val` in its companion object.
 * This function uses reflection to read it — a single place to centralise the mapping.
 */
fun Any.toolId(): String? = try {
    val clazz = this::class.java
    // Try companion object first (Kotlin)
    val companion = clazz.declaredClasses.find { it.simpleName == "Companion" }
    companion?.getDeclaredField("NAME")?.apply { isAccessible = true }?.get(null) as? String
        // Fall back to static field (Java/Kotlin interop)
        ?: clazz.getDeclaredField("NAME").apply { isAccessible = true }.get(null) as? String
} catch (e: Throwable) {
    null
}

/**
 * Filters [tools] based on [RemoteConfigSnapshot.mcpToolFlags].
 *
 * Flags are keyed by tool ID (the `NAME` constant, e.g. `"refine_task"`).
 * A flag value of `false` means the tool is disabled and is excluded from the returned list.
 * Missing flags are treated as `true` (opt-in per-tool disablement).
 *
 * The LLM model is resolved through [RemoteConfigSnapshot.modelFlags]:
 * if `flags["model.gpt-4o"] == false`, [resolveModel] falls back to [KnownModels.GPT4oMini].
 */
fun filterTools(tools: List<Tool<*, *>>, flags: Map<String, Boolean>): List<Tool<*, *>> = tools.filter { tool ->
    val id = tool.toolId()
    // If a flag explicitly says false, disable the tool.
    // Missing flag = not disabled (default true).
    id == null || flags[id] != false
}

/**
 * Resolves [modelId] through [flags], falling back to [KnownModels.GPT4oMini]
 * when the flag explicitly disables the requested model.
 */
fun resolveModelWithFlags(modelId: String, flags: Map<String, Boolean>): LLModel {
    val modelKey = "model.$modelId"
    return if (flags[modelKey] == false) {
        KnownModels.GPT4oMini
    } else {
        resolveModel(modelId)
    }
}
