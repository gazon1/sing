---
title: "Avoid OpenAIModels.Chat.* — use KnownModels; explicit get<>() for SimpleTool<T>"
date: 2026-09-05
tags: [koog, koin, testing]
---

## Context

Two separate JVM-test classpath issues blocked Koog-based tests:

1. `OpenAIModels$Chat.<clinit>` threw `NoClassDefFoundError: Could not initialize class` on first read in `jvmTest`. The classpath differs from Android and from production JVM — ServiceLoader / native resolution paths succeed there but not in tests.

2. `factory { Foo(get()) }` where `Foo`'s constructor parameter is `SimpleTool<T>` (e.g. `ImproveNoteUseCase(tool: SimpleTool<ImproveNoteInput>)`) failed with `NoDefinitionFoundException: SimpleTool`. Koin 4.x does not auto-upcast from concrete class to interface.

## Idea

- For #1: `@Ignore` the failing tests until Koog fixes their static init.
- For #1: introduce a thin `KnownModels` object that builds `LLModel` via the public constructor.
- For #2: live with the error, document it in code.

## Decision

**For #1:** `KnownModels` is the only place in production code that references model constants. It's `internal object` exposing `GPT4o`, `GPT4oMini`, etc. via `LLModel(OpenAILLMProvider, "<id>")`. `resolveModel(modelId)` consults it. No `OpenAIModels` import anywhere outside `KnownModels.kt` (and even there only in comments).

**For #2:** Factory sites that bind a use case whose parameter is a `SimpleTool<T>`-typed value must use `get<ConcreteTool>()` explicitly, not bare `get()`.

## Rationale

- `KnownModels` is built once via `LLModel(...)` constructor at object-init time. No static initialiser from Koog is touched. Tests never see `OpenAIModels` in their classpath.
- Most use cases take their concrete tool (e.g. `RefineTaskUseCase(tool: RefineTaskTool)`); bare `get()` works for them. The two odd cases (`ImproveNoteUseCase`, `ProjectReviewUseCase`) need explicit types.
- We don't set `LLMCapability.*` on `KnownModels` because none of our AI tools use schema-based responses — only tool calling. If a future feature needs capabilities, copy them from `OpenAIModels.kt`.

## Consequences

- `grep -rn "OpenAIModels" shared/src/commonMain shared/src/jvmMain shared/src/androidMain --include="*.kt"` must return only comments in `KnownModels.kt`. Anything else is a regression.
- When adding a new AI tool, add its use case with **explicit `get<ConcreteTool>()`** if the use case's parameter is `SimpleTool<T>`:
  ```kotlin
  factory {
      com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase(
          tool = get<com.singularity.todo.feature.ai.tools.ImproveNoteTool>(),
      )
  }
  ```
  Bare `get()` will fail at first use with `NoDefinitionFoundException`.
- `JvmAiDiGraphTest` keeps its `LLModel` override as a safety belt — if someone reintroduces `OpenAIModels.*`, this test fails at graph-build time.
- Don't add capabilities to `KnownModels` unless a feature needs them — the simple form avoids the static init entirely.

## Links

- `shared/src/commonMain/.../feature/ai/KnownModels.kt`
- `shared/src/commonMain/.../feature/ai/KoogAgentService.kt` (uses `KnownModels.*`)
- `shared/src/jvmMain/.../core/di/AiToolsModule.jvm.kt` (binds `KnownModels.GPT4oMini`)
- `shared/src/androidMain/.../core/di/AiToolsModule.android.kt` (same)
- `shared/src/jvmTest/.../core/di/JvmAiDiGraphTest.kt`
- Commits `097f505`, `e7640e1`