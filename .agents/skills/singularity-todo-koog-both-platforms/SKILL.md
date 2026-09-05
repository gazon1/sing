---
name: singularity-todo-koog-both-platforms
description: How JetBrains Koog 1.1.1 is wired across JVM and Android in this project — OkHttp HTTP backend, `MultiLLMPromptExecutor`, expect/actual `createKoogPromptExecutor`, common `KoogPromptExecutorPort` wrapper, shared `aiToolsCoreModule`. Use when adding new AI features, debugging cross-platform AI failures, or extending the existing wiring to a new platform.
---

# Singularity TODO — Koog on Both Platforms

Earlier versions of this codebase assumed Koog was JVM-only and stubbed the Android side. That assumption is wrong: the OkHttp backend works fine on Android, the `MultiLLMPromptExecutor` is platform-agnostic, and the only Koog type that leaks into commonMain is `PromptExecutor` (which itself wraps `Prompt`/`LLModel`/`ToolDescriptor` — all available on both targets).

This skill documents the actual working wiring.

## Architecture

```
commonMain ─────────────────────────────────────────────────────────────
  TextGenPort                     ← UI talks to this
    ↑
  KoogAgentService(secureStorage, settings, promptExecutor, streaming, tools)
    ↑           ↑
    │           └── PromptExecutor (raw Koog API) ──┐
    │                                              ↓
    └──── streamingExecutor.executeStreaming(p, model, emptyList())
                                                   ↑
                                       PromptExecutorPort  ← platform-agnostic interface
                                                   ↑
                                            KoogPromptExecutorPort(executor)
                                                   ↑
                                            platform-specific buildExecutor(cfg)

jvmMain / androidMain ──────────────────────────────────────────────────
  createKoogPromptExecutor(secureStorage, settings): PromptExecutorPort
    1. reads OpenAiConfig (resolve suspend → koinBridge)
    2. buildExecutor(cfg) — local helper, same shape on both platforms
    3. wraps in KoogPromptExecutorPort(executor)
```

Key facts:

- `KoogPromptExecutorPort` lives in `commonMain`. It is the **single** adapter between Koog's raw `PromptExecutor` and the project-agnostic `PromptExecutorPort`. The JVM and Android actuals both wrap their executor in this class.
- `buildExecutor(cfg: OpenAiConfig)` is duplicated in jvmMain and androidMain. **Both look identical** but live in each source set because `MultiLLMPromptExecutor` itself is in platform-specific source sets. Don't try to share this — the duplication is enforced by the SDK layout.
- `aiToolsCoreModule()` (commonMain) registers the common bindings — 17 `SimpleTool<T>` factories, 9 use case factories, `GenuiEngine`, `SurfaceController`, `A2uiParser`, `KoogAgentService` as `single<TextGenPort>`, `ChatViewModel`, `TasksViewModel`, `ProjectsViewModel`. Each `aiToolsModule` actual then adds platform-specific bindings.

## The expect/actual seam

**commonMain:**

```. Runkotlin
expect suspend fun createKoogPromptExecutor(
    secureStorage: SecureStoragePort,
    settings: SettingsRepository,
): PromptExecutorPort
```

**jvmMain / androidMain** — essentially the same implementation:

```. Runkotlin
actual suspend fun createKoogPromptExecutor(
    secureStorage: SecureStoragePort,
    settings: SettingsRepository,
): PromptExecutorPort {
    val cfg = OpenAiConfig.resolve(secureStorage, settings)
    val executor = buildExecutor(cfg)
    return KoogPromptExecutorPort(executor)
}

internal fun buildExecutor(cfg: OpenAiConfig) = MultiLLMPromptExecutor(
    mapOf(
        OpenAILLMProvider to OpenAILLMClient(
            apiKey = cfg.apiKey.value,
            settings = OpenAIClientSettings(baseUrl = cfg.baseUrl),
            httpClientFactory = OkHttpKoogHttpClient.Factory(),
            clock = KoogClock.System,
        ),
    ),
)
```

Note `suspend` — `createKoogPromptExecutor` reads Flow-backed settings. The DI bridge (`koinBridge { ... }`) handles the synchronous wrap. See `singularity-todo-koin-suspend-bridge`.

## Module composition

```. Runkotlin
// commonMain (already in aiToolsCoreModule)
single<TextGenPort> { KoogAgentService(get(), get(), get(), get(), get()) }
single<List<Tool<*, *>>> { listOf(get<RefineTaskTool>(), ...) }

// jvmMain / androidMain actual
actual fun aiToolsModule() = module {
    includes(aiToolsCoreModule())

    single<LLModel> { KnownModels.GPT4oMini }  // see singularity-todo-koog-test-workarounds

    single<PromptExecutorPort> {
        koinBridge {
            createKoogPromptExecutor(get<SecureStoragePort>(), get<SettingsRepository>())
        }
    }

    single<PromptExecutor> {
        (get<PromptExecutorPort>() as KoogPromptExecutorPort).executor
    }
}
```

The downcast `as KoogPromptExecutorPort` is necessary to expose the raw Koog executor to AI tool factories. It happens in the platform source set, never in commonMain — commonMain doesn't know about either `JvmPromptExecutorPort` or `AndroidPromptExecutorPort` (both removed in favour of the unified `KoogPromptExecutorPort`).

## Why the `PromptExecutor` binding

AI tools (`RefineTaskTool`, `GetTaskTool`, etc.) need to call `promptExecutor.execute(p, model, emptyList())` directly — that's Koog's raw API. `PromptExecutorPort` is suspend-only and wrapped, while `PromptExecutor` is the real Koog type the tools were designed against. We bind both:

- `PromptExecutorPort` for `KoogAgentService` (streaming) and `PromptExecutorPort.executeStreaming(...)`;
- `PromptExecutor` for the 17 tool factories in `aiToolsCoreModule`.

If you add a new AI tool, it gets `PromptExecutor` and `LLModel` via constructor injection — same as the existing ones.

## The `LLModel` binding is **optional**

This was a recent change. `single<LLModel> { KnownModels.GPT4oMini }` is convenient for tool factories (avoids passing model twice) but is **not** required for the AI agent to work — `KoogAgentService.createAgent()` reads the model from settings via `resolveModel(settings.aiModel.first())` on every call. If you're tempted to add `single<LLModel> { OpenAIModels.Chat.GPT4oMini }`, see `singularity-todo-koog-test-workarounds` first.

## Cross-platform build matrix

| Target | Koog HTTP backend | OpenAI client | PromptExecutor binding |
|---|---|---|---|
| commonMain | (none — types only) | (none) | `single<PromptExecutorPort>` via expect/actual |
| jvmMain | `ai.koog:http-client-okhttp` | `prompt-executor-openai-client` (multiplatform) | real `MultiLLMPromptExecutor` |
| androidMain | `ai.koog:http-client-okhttp` | `prompt-executor-openai-client` (multiplatform) | real `MultiLLMPromptExecutor` |

The OkHttp dependency is needed in **both** `jvmMain` and `androidMain` source sets:

```. Runkotlin
// shared/build.gradle.kts
androidMain.dependencies {
    implementation(libs.koog.http.client.okhttp)
    // ...
}
jvmMain.dependencies {
    implementation(libs.koog.http.client.okhttp)
    // ...
}
```

There is **no** `-jvm` / `-android` variant of `prompt-executor-openai-client` — the multiplatform artifact works on both.

## Adding a new AI feature

1. **Define the use case** in `shared/src/commonMain/.../feature/ai/use_cases/MyUseCase.kt`. Constructor: `(tool: MyTool)`.
2. **Define the tool** in `shared/src/commonMain/.../feature/ai/tools/MyTool.kt`. Constructor: `(promptExecutor, model)`. Extend `SimpleTool<MyInput>` with `@Serializable` input/output data classes.
3. **Register in `aiToolsCoreModule`** — add `factory { MyTool(get(), get()) }` and `factory { MyUseCase(get()) }`. Add `get<MyTool>()` to the `single<List<Tool<*, *>>>`.
4. **If the feature is wired into a VM** — add a use case parameter to the VM constructor and a branch in the existing `runAiAction` (or equivalent). Nullable for tests.
5. **Test** — `commonTest/.../feature/ai/MyUseCaseTest.kt` for pure logic; `commonTest/.../feature/ai/MyToolTest.kt` (if pure) or skip if the tool is hard to test without Koog. `jvmTest/.../feature/<feature>/MyViewModelTest.kt` with `FakeTextGen` — see `singularity-todo-ai-provider-settings`.

## Common failures and fixes

| Symptom | Cause | Fix |
|---|---|---|
| `OpenAIModels$Chat.<clinit>` NPE in jvmTest | Static init hits an unloaded resource. | Use `KnownModels` — see `singularity-todo-koog-test-workarounds`. |
| `NoDefinitionFoundException: SimpleTool` in DI | `factory { Foo(get()) }` where Foo's param is `SimpleTool<T>`. | `factory { Foo(tool = get<ConcreteTool>()) }` — see the same skill. |
| `okhttp` not found on Android | Forgot `libs.koog.http.client.okhttp` in `androidMain.dependencies`. | Add it; both platforms share the same Koog HTTP adapter. |
| `IllegalArgumentException: baseUrl` from `OpenAILLMClient` | Passed `baseUrl` to the client constructor instead of through `OpenAIClientSettings(baseUrl = ...)`. | Use the `settings` parameter. |
| `AIAgent` ignores user-selected model | Builder didn't pass `model` / `llmModel`. | `AIAgent.builder().llmModel(resolveModel(modelId))` (Koog 1.1.1 uses `llmModel`, not `model`). |

## Related skills

- `singularity-todo-koog-agent` — the agent pattern (SimpleTool, TextGenPort, AIAgent).
- `singularity-todo-koog-test-workarounds` — JVM-test classpath quirks.
- `singularity-todo-koin-suspend-bridge` — the `koinBridge { ... }` helper used to call the suspend factory from Koin.
- `singularity-todo-ai-provider-settings` — user-facing configuration screen.
- `singularity-todo-koin-di` — Koin conventions in this project.