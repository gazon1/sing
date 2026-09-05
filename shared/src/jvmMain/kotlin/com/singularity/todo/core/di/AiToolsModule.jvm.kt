package com.singularity.todo.core.di

import ai.koog.prompt.llm.LLModel
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.ai.KnownModels
import org.koin.dsl.module

/**
 * JVM actual for [aiToolsModule].
 *
 * Shares common bindings (use cases, tools, AI service, GenUI, ChatViewModel,
 * TasksViewModel, ProjectsViewModel) via [aiToolsCoreModule], then adds the
 * platform-specific bits: real [PromptExecutorPort] built by
 * [createKoogPromptExecutor], the default [LLModel], and the raw
 * [ai.koog.prompt.executor.model.PromptExecutor] for AI tools.
 *
 * `createKoogPromptExecutor` is suspend (it reads from DataStore via Flow);
 * the bridge to non-suspend Koin factories is [koinBridge] here, scoped
 * to the single one-shot call when this binding is first resolved.
 */
actual fun aiToolsModule() = module {
    includes(aiToolsCoreModule())

    // Default LLM. Built via the public LLModel constructor rather than
    // OpenAIModels.Chat.GPT4oMini to avoid triggering OpenAIModels.<clinit>
    // in the JVM-test classpath.
    single<LLModel> { KnownModels.GPT4oMini }

    // Real Koog executor on JVM
    single<PromptExecutorPort> {
        koinBridge {
            createKoogPromptExecutor(get<SecureStoragePort>(), get<SettingsRepository>())
        }
    }

    // Raw Koog executor for AI tools
    single<ai.koog.prompt.executor.model.PromptExecutor> {
        (get<PromptExecutorPort>() as KoogPromptExecutorPort).executor
    }
}
