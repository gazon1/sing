package com.singularity.todo.feature.ai

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
internal object KnownModels {
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