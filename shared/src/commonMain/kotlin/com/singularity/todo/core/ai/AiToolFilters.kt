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
 *
 * The constant is looked up in **two** places. The Kotlin compiler compiles a `const val`
 * in a companion object as a static field of the **containing class**, not of the
 * companion — so the class is where it is found, for a file-level class and for a nested
 * one alike. The companion is still searched first, because it costs nothing and a
 * constant can live there when the class is not Kotlin's own.
 *
 * The two lookups are separate on purpose. An earlier version chained them with `?:`,
 * which does not work: `getDeclaredField` **throws** when the class has no such field,
 * so the lookup ended before the fallback could run, the enclosing `catch` turned the
 * throw into `null`, and every tool lost its ID. A tool with no ID has no flag key, so
 * the kill switch matched nothing at all.
 */
fun Any.toolId(): String? {
    val clazz = this::class.java
    val companion = clazz.declaredClasses.find { it.simpleName == "Companion" }

    val fromCompanion = companion
        ?.let { holder -> runCatching { holder.getDeclaredField("NAME") }.getOrNull() }
        ?.let { field -> runCatching { field.apply { isAccessible = true }.get(null) as? String }.getOrNull() }
    val fromClass = runCatching { clazz.getDeclaredField("NAME") }.getOrNull()
        ?.let { field -> runCatching { field.apply { isAccessible = true }.get(null) as? String }.getOrNull() }

    return fromCompanion ?: fromClass
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
