---
title: "Wire Koog AI agent for both JVM desktop and Android"
date: 2026-09-05
tags: [koog, kmp, ai]
status: accepted
---

## Context

The project targeted Android + JVM desktop. AI features (chat, AI actions on tasks/notes/projects, GenUI streaming) were wired with JetBrains Koog 1.1.1 but only worked on the JVM side. Android was stubbed: `createKoogPromptExecutor()` returned a `StubPromptExecutorPort` that threw on `execute()`. Every AI button in the Android UI silently failed.

The stub was historical — an early assumption that Koog's `MultiLLMPromptExecutor` was JVM-only. It isn't: the OkHttp HTTP backend works on both platforms.

## Idea

- Keep the JVM stub, focus on documenting what doesn't work on Android.
- Port the AI stack to Android using the same OkHttp backend as JVM.
- Find a way to make AI work everywhere by treating the executor as a portable abstraction.

## Decision

We ported Koog to Android. `aiToolsModule` is now real on both platforms, sharing the same `aiToolsCoreModule()` (common bindings) plus platform-specific actuals that build the executor the same way (`OpenAILLMClient` + `OkHttpKoogHttpClient.Factory()`).

A unified `KoogPromptExecutorPort` (commonMain) replaces the old `JvmPromptExecutorPort` / `AndroidPromptExecutorPort` pair.

## Rationale

OkHttp works on Android natively — the only reason it wasn't wired was the assumption, not the technology. Both platforms share `ai.koog:http-client-okhttp` and the multiplatform `prompt-executor-openai-client` artifact (which has both `android` and `jvm` source sets).

Avoiding the duplication in `buildExecutor(...)` would require sharing `MultiLLMPromptExecutor` across source sets, but it lives in platform-specific code in Koog 1.1.1. We accept two ~10-line copies.

## Consequences

- **Always** declare `ai.koog:http-client-okhttp` in **both** `androidMain.dependencies` and `jvmMain.dependencies`.
- The unified `KoogPromptExecutorPort` lives in `commonMain` and exposes `val executor: PromptExecutor` publicly for the platform `single<PromptExecutor>` binding.
- **Always** bind both `single<PromptExecutorPort>` and `single<PromptExecutor>`; `PromptExecutorPort` is for the streaming executor inside `KoogAgentService`, `PromptExecutor` is for the AI tool factories.
- The old `JvmPromptExecutorPort` and `AndroidPromptExecutorPort` files are deleted.
- A passing `:androidApp:assembleDebug` is the cross-platform smoke test (it would have failed under the old stub).

## Links

- `shared/src/commonMain/.../feature/ai/KoogAgentService.kt`
- `shared/src/commonMain/.../core/di/KoogPromptExecutorPort.kt`
- `shared/src/jvmMain/.../core/di/JvmKoogFactory.kt` (now `buildExecutor`)
- `shared/src/androidMain/.../core/di/AndroidPromptExecutorPort.kt` (now `buildExecutor`)
- `shared/src/jvmMain/.../core/di/AiToolsModule.jvm.kt`
- `shared/src/androidMain/.../core/di/AiToolsModule.android.kt`
- `shared/src/commonMain/.../core/di/Modules.kt` — `aiToolsCoreModule()`
- Commit `5db5821`
