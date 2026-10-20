package com.singularity.todo.core.ai

import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.llm.LLModel
import com.singularity.todo.core.llm.KnownModels
import com.singularity.todo.core.llm.resolveModel

/**
 * Filters [tools] based on [RemoteConfigSnapshot.mcpToolFlags].
 *
 * Flags are keyed by the tool's name — the same name the tool is registered under, the
 * same name the catalogue declares, and the same name the agent calls. A flag value of
 * `false` means the tool is disabled and is excluded from the returned list. Missing
 * flags are treated as `true` (opt-in per-tool disablement).
 *
 * ## Why the name is read from the descriptor
 *
 * The key used to be read by reflection, from the tool class's `NAME` constant, and it
 * was read *wrong*: the Kotlin compiler compiles a `const val` in a companion object as
 * a static field of the **containing class**, so the lookup raised
 * `NoSuchFieldException`, the enclosing `catch` turned it into `null`, and a tool with
 * no ID has no flag key — so **no flag ever matched a tool**, in the app and in the MCP
 * server alike (ADR 2026-10-20-the-kill-switch-was-never-applied).
 *
 * The name does not have to be guessed at all. Koog's [Tool.descriptor] already carries
 * the name the tool is registered under, and the runtime wiring test proves on both
 * platforms that it equals the catalogue's name and the class's `NAME` constant. Reading
 * a name that is already there is one fact with one owner; reading it out of the
 * bytecode is a second copy of it, and the copy was wrong.
 *
 * The LLM model is resolved through [RemoteConfigSnapshot.modelFlags]:
 * if `flags["model.gpt-4o"] == false`, [resolveModel] falls back to [KnownModels.GPT4oMini].
 */
fun filterTools(tools: List<Tool<*, *>>, flags: Map<String, Boolean>): List<Tool<*, *>> = tools.filter { tool ->
    // A flag explicitly says false → the tool is disabled.
    // Missing flag = not disabled (default true).
    flags[tool.descriptor.name] != false
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
