---
name: singularity-todo-koog-test-workarounds
description: Workarounds for Koog 1.1.1 quirks in this project: `KnownModels` to avoid `OpenAIModels.<clinit>` NPE in JVM-test classpath, and the Koin generic-type resolution gotcha (use `get<ConcreteClass>()` instead of bare `get()` when the constructor parameter is `SimpleTool<T>`). Use when a unit test touching AI fails with `ExceptionInInitializerError` from `OpenAIModels$Chat`, or when a `factory { XxxUseCase(get()) }` line throws `NoDefinitionFoundException: SimpleTool`.
---

# Singularity TODO — Koog Test Workarounds

Two specific Koog 1.1.1 + Koin 4.x quirks kept biting this codebase. Documenting them here so the next person doesn't re-discover them.

## Quirk 1: `OpenAIModels.<clinit>` NPEs in the JVM-test classpath

**Symptom:**

```
java.lang.NoClassDefFoundError: Could not initialize class
    ai.koog.prompt.executor.clients.openai.OpenAIModels$Chat
```

at the moment any code reads `OpenAIModels.Chat.GPT4oMini` (or any sibling constant) under `:shared:jvmTest`.

**Root cause:** `OpenAIModels$Chat.<clinit>` triggers ServiceLoader / native resolution that succeeds on the Android classpath (real Android runtime) and on the JVM production runtime (with full Compose dependencies loaded), but throws NPE in the slimmer test classpath. The exact mechanism is Koog-internal — Robolectric vs JBR differences — and not worth chasing.

**Fix:** don't reference `OpenAIModels.*` anywhere in production code. Use `KnownModels`:

```. Runkotlin
// shared/src/commonMain/.../feature/ai/KnownModels.kt
internal object KnownModels {
    val GPT4o: LLModel = LLModel(OpenAILLMProvider, "gpt-4o")
    val GPT4oMini: LLModel = LLModel(OpenAILLMProvider, "gpt-4o-mini")
    val GPT4_1: LLModel = LLModel(OpenAILLMProvider, "gpt-4.1")
    // ... one per supported model ...
}
```

`LLModel` is a public `data class` with a public constructor (`prompt-executor-model-jvm` sources confirm). Building via constructor avoids the static initializer entirely.

**What `KnownModels` does NOT replace:** the per-model capabilities (`LLMCapability.Tools`, `LLMCapability.Schema`, etc.). We don't set capabilities because none of our AI tools need schema-based responses — only tool calling, which `OpenAILLMClient` configures at the client level. If you add a feature that requires capabilities, read `OpenAIModels.kt` first to copy the missing fields.

**Where `KnownModels` is used:**

| Use | Before fix |
| |
| `single<LLModel> { ... }` in `AiToolsModule.{jvm,android}` | `KnownModels.GPT4oMini` |
| `resolveModel(modelId)` in `KoogAgentService` | `KnownModels.<id>`` |
| `model: LLModel = ...` default in `KoogGenuiTransport` | `KnownModels.GPT4oMini` |

If you find yourself wanting to add another `OpenAIModels.*` reference, **stop** and add it to `KnownModels` first. There's no production path that needs the rich capability set; the static init is the only reason you'd think you needed it.

**Verification:**

```bash
grep -rn "OpenAIModels" shared/src/commonMain shared/src/jvmMain shared/src/androidMain --include="*.kt"
```

Only matches in `KnownModels.kt` comments should appear. Anything else is a regression.

## Quirk 2: Koin 4.x generic-type resolution

**Symptom:**

```
org.koin.core.error.NoDefinitionFoundException:
    No definition found for type 'ai.koog.agents.core.tools.SimpleTool'
```

at first `koin.get<ImproveNoteUseCase>()` in a test.

**Root cause:** Koin 4.x resolves `factory { Foo(get()) }` via reflection on the constructor parameter type. When `Foo` is `ImproveNoteUseCase(tool: SimpleTool<ImproveNoteInput>)`, reflection sees the **declared** type — `SimpleTool<ImproveNoteInput>` — and asks for a binding of that exact type. But our DI module only registers `ImproveNoteTool` (the concrete class), so the lookup fails.

**Fix:** explicit type argument:

```. Runkotlin
// Wrong — Koin looks for SimpleTool<ImproveNoteInput>.
factory { com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase(get()) }

// Right — Koin finds ImproveNoteTool.
factory {
    com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase(
        tool = get<com.singularity.todo.feature.ai.tools.ImproveNoteTool>(),
    )
}
```

This is **not** limited to `SimpleTool<T>` — it bites anywhere a use case takes an interface or generic type and there's no matching binding. Koin 4.x does not do implicit upcast from concrete class to interface in `get()`.

**When this is fine:** when the parameter type **is** the concrete class. Most other use cases in this codebase (`RefineTaskUseCase(tool: RefineTaskTool)`, etc.) work with bare `get()` because their parameter type matches the binding exactly. Only the odd `SimpleTool<T>` case — and any other interface-typed parameter — needs the explicit argument.

**Detection:** add a smoke test (`JvmAiDiGraphTest`) that calls `koin.get<ImproveNoteUseCase>()` and watch it fail with `NoDefinitionFoundException`.

## Bonus: DI-graph test as safety belt

`JvmAiDiGraphTest` overrides `single<LLModel>` with a fixture and walks every binding in the AI graph. Even after switching `KnownModels` into production, this test **keeps the override** as a safety belt:

> If someone re-introduces an `OpenAIModels.*` reference in the production graph, this test will fail at graph-build time rather than at first use.

The override is cheap (one fixture) and self-documenting. Keep it.

## Anti-patterns

- **`@Ignore` on the DI-graph test "until we figure out the NPE"** — masks regressions. Use `KnownModels` instead.
- **Calling `OpenAIModels.Chat.GPT4oMini` even once for "convenience"** — every reference loads the static class. One is enough to NPE in tests.
- **Mocking Koog with MockK/Mockito to bypass the static init** — the project uses fakes (`FakeTextGen`, `FakeLLModel`). Don't introduce mocks for this.

## Related skills

- `singularity-todo-koog-both-platforms` — overall Koog wiring across JVM + Android.
- `singularity-todo-koin-di` — Koin conventions.
- `singularity-todo-koin-suspend-bridge` — the `koinBridge { ... }` helper.