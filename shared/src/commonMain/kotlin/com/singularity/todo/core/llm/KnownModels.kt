package com.singularity.todo.core.llm

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider

/**
 * Known OpenAI chat models — built with the public [LLModel] constructor
 * to avoid triggering `OpenAIModels$Chat.<clinit>` from Koog, which NPEs
 * in the JVM-test classpath.
 *
 * Equivalent to `OpenAIModels.Chat.*` for the cases we use. Add new
 * models here when Koog releases them; the agent's [resolveModel] just
 * maps a user-facing id to one of these constants.
 */
object KnownModels {
    val GPT4o: LLModel = LLModel(OpenAILLMProvider, "gpt-4o")
    val GPT4oMini: LLModel = LLModel(OpenAILLMProvider, "gpt-4o-mini")
    val GPT4_1: LLModel = LLModel(OpenAILLMProvider, "gpt-4.1")
    val GPT4_1Nano: LLModel = LLModel(OpenAILLMProvider, "gpt-4.1-nano")
    val GPT4_1Mini: LLModel = LLModel(OpenAILLMProvider, "gpt-4.1-mini")
    val O1: LLModel = LLModel(OpenAILLMProvider, "o1")
    val O3: LLModel = LLModel(OpenAILLMProvider, "o3")
    val O3Mini: LLModel = LLModel(OpenAILLMProvider, "o3-mini")
    val O4Mini: LLModel = LLModel(OpenAILLMProvider, "o4-mini")
    val GPT5: LLModel = LLModel(OpenAILLMProvider, "gpt-5")
    val GPT5Mini: LLModel = LLModel(OpenAILLMProvider, "gpt-5-mini")
}

/** Maps a user-supplied model identifier to a Koog [LLModel] constant. */
fun resolveModel(modelId: String): LLModel = when (modelId) {
    "gpt-4o" -> KnownModels.GPT4o
    "gpt-4o-mini" -> KnownModels.GPT4oMini
    "gpt-4.1" -> KnownModels.GPT4_1
    "gpt-4.1-nano" -> KnownModels.GPT4_1Nano
    "gpt-4.1-mini" -> KnownModels.GPT4_1Mini
    "o1" -> KnownModels.O1
    "o3" -> KnownModels.O3
    "o3-mini" -> KnownModels.O3Mini
    "o4-mini" -> KnownModels.O4Mini
    "gpt-5" -> KnownModels.GPT5
    "gpt-5-mini" -> KnownModels.GPT5Mini
    else -> KnownModels.GPT4oMini
}
