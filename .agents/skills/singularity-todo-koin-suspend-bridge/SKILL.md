---
name: singularity-todo-koin-suspend-bridge
description: Bridge suspend code into Koin's non-suspend factory DSL via `koinBridge { ... }`. Use when a Koin `single` / `factory` / `also` block needs to call a suspend function (e.g. reading from a Flow-backed SettingsRepository or SecureStoragePort). Triggers on any Koin DI change that mixes suspending reads with synchronous bindings, or on `runBlocking { ... }` lines appearing inside `module { ... }` blocks.
---

# Singularity TODO — Koin Suspend Bridge

Koin's factory DSL is synchronous. Our domain code is not — repositories expose suspend reads from Flow-backed DataStore, the SecureStoragePort has suspend `read`/`write`, and the AI executor factory needs to resolve settings before it can build a client.

This skill documents the canonical way to bridge the two.

## The helper

`shared/src/commonMain/kotlin/com/singularity/todo/core/di/KoinBridge.kt`:

```. Runkotlin
internal inline fun <T> koinBridge(crossinline block: suspend () -> T): T =
    runBlocking { block() }
```

Use it inside any Koin factory that needs to call suspend code:

```. Runkotlin
single<PromptExecutorPort> {
    koinBridge {
        createKoogPromptExecutor(get<SecureStoragePort>(), get<SettingsRepository>())
    }
}

single<DataStore<Preferences>> {
    PreferenceDataStoreFactory.create { /* ... */ }
        .also { ds ->
            koinBridge { AiApiKeyMigration.run(ds, get<SecureStoragePort>()) }
        }
}
```

## Why a helper, not raw `runBlocking`

A grep for `runBlocking { ... }` inside the codebase should match **only** `KoinBridge.kt`. Every other site uses `koinBridge { ... }`. This makes the bridge:

- **greppable** — one regex finds all suspension points in DI;
- **replaceable** — when Koin coroutine-aware factories land, one file changes;
- **self-documenting** — `koinBridge` reads as intent ("we're crossing a sync boundary"), `runBlocking` reads as smell.

## When this bridge is appropriate

The bridge is for **one-shot, lazy, fire-and-forget** reads at binding construction. Typical candidates:

- Reading a Flow-backed setting once at first `get<T>()`;
- Running a one-shot migration when a `DataStore` is first constructed;
- Resolving a `SecureStoragePort` value once for an API client factory.

Do **not** use `koinBridge` for:

- Hot-path code that runs repeatedly — it pays the `runBlocking` cost each time;
- Anything that holds coroutine resources (channels, scopes);
- Long-running operations (network calls, file IO > 100ms) — surface these as suspend functions instead.

## How to migrate to Koin coroutine-aware factories (when available)

The single one-shot call site per binding makes future migration trivial:

1. Replace `koinBridge { ... }` with whatever Koin's coroutine-aware factory syntax is (e.g. `factoryOf(::Foo).createdAtStart()` or similar).
2. The `expect/actual` declarations of `createKoogPromptExecutor` etc. stay suspend — no API churn.

## Known call sites (as of last refactor)

| File | Use |
|---|---|
| `shared/src/jvmMain/.../core/di/AiToolsModule.jvm.kt` | `single<PromptExecutorPort>` |
| `shared/src/androidMain/.../core/di/AiToolsModule.android.kt` | `single<PromptExecutorPort>` |
| `shared/src/androidMain/.../core/di/PlatformModule.android.kt` | `single<DataStore<Preferences>>.also { ... }` (migration) |

When you add a new binding that needs suspend code, add a row to this table.

## Anti-patterns

- **`runBlocking` outside `koinBridge`** — never. `git grep "runBlocking"` should land only inside `KoinBridge.kt`. If you genuinely need a different bridge (e.g. with a timeout), wrap it: `withTimeout(...) { runBlocking { ... } }` inside `koinBridge` so the named helper still applies.
- **`GlobalScope.launch { ... }`** inside factories — non-deterministic, can outlive the process. The bridge is synchronous on purpose.
- **`runBlocking` in tests** — fine, that's a different concern (see `singularity-todo-koin-di`).

## Related skills

- `singularity-todo-koin-di` — Koin DI conventions in this project.
- `singularity-todo-secret-migration` — the migration pattern that uses this bridge.