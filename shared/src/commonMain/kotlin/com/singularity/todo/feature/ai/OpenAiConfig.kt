// Re-exported from core/llm/ for backward compatibility.
// New code should import directly from core/llm/.
@file:Suppress("RedundantSuppression", "unused")

package com.singularity.todo.feature.ai

import com.singularity.todo.core.llm.ApiKey
import com.singularity.todo.core.llm.OpenAiConfig as CoreOpenAiConfig
import com.singularity.todo.core.llm.SettingsReader

typealias OpenAiConfig = CoreOpenAiConfig
