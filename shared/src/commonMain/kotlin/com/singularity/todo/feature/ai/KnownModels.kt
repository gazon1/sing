// Re-exported from core/llm/ for backward compatibility.
// New code should import directly from core/llm/.
@file:Suppress("RedundantSuppression", "unused")

package com.singularity.todo.feature.ai

import ai.koog.prompt.llm.LLModel
import com.singularity.todo.core.llm.KnownModels as CoreKnownModels
import com.singularity.todo.core.llm.resolveModel as coreResolveModel

typealias KnownModels = CoreKnownModels

// Re-export resolveModel at package level for backward compatibility.
@Suppress("unused")
fun resolveModel(modelId: String): LLModel = coreResolveModel(modelId)
