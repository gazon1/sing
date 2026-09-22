---
title: "Tier 1 interface cleanup — remove single-implementation contracts"
date: 2026-09-22
tags: []
---

## Context

Eight interfaces in the codebase have exactly one production implementation (or zero, in one case). They are likely forward-looking seams created during early scaffolding, but YAGNI applies: the cost of an unused abstraction is paid now (extra file, extra DI binding, extra reading cost) while the benefit (future swap) is speculative. After analysis, these were categorized as Tier 1 — no production benefit beyond what the concrete class provides, no test fakes to refactor.

## Idea

1. **Keep all interfaces** — preserves future swap flexibility, but accumulates dead abstractions.
2. **Inline all 7 single-impl interfaces, delete 1 marker interface** — reduces surface area, follows Effective Kotlin item "Avoid simple boundary classes". Trade-off: future 2nd implementation requires re-introducing the interface.
3. **Inline + add `open` to production class** — enables Fake extension via inheritance. Violates Kotlin final-by-default convention.

We chose option 2.

## Decision

Inline 7 single-impl interfaces by making the concrete class canonical. For `BackupFileNamer`, convert `object` to `class` with a lambda strategy parameter (composition over inheritance). Delete `SyncableRepository` marker interface (zero impls, dead code).

## Rationale

- **YAGNI** (Extreme Programming): single-impl interface is a premature abstraction.
- **Rule of Three** (Refactoring, Fowler): no removed interface had ≥3 production implementations or ≥3 features needing it.
- **Avoid simple boundary classes** (Effective Kotlin): the removed marker interfaces add no behaviour, only indirection.
- **Composition over inheritance** (Effective Kotlin item 36): `DefaultBackupFileNamer(timestampToName: (Long) -> String)` allows test substitution without `open class`.
- **Final by default** (Kotlin Coding Conventions): chose lambda strategy over `open class`. No `open` modifier added anywhere.
- **Koin `singleOf`/`factoryOf`** (`singularity-todo-koin-di` skill): constructor-reference DSL is shorter than explicit `single<X> { X(get()) }` for simple constructors.
- **Stub prefix kept** (Code Complete, "honest naming"): explicit no-op marker documents current state vs future Supabase Phase 11/12 implementation.

## Consequences

- Single-impl interface with no test fake is YAGNI — inline the concrete class as canonical.
- When converting a strategy class (`BackupFileNamer`-like), prefer `class(c: (T) -> R)` lambda strategy over `open class`. Composition beats inheritance for testability.
- Keep `Stub` prefix for honest no-op documentation; drop only when the real implementation arrives.
- DI bindings for canonical types: `singleOf(::Class)` for simple ctors (≤3 args, singleton scope), `factoryOf(::Class)` for per-injection scope. No `bind<Interface>()`.
- Before using `singleOf`/`factoryOf`, deduplicate existing `single<X> { ... }` bindings for the same type — Koin throws `BeanOverrideException` on duplicates.
- `single<Interface>(::Impl)` does NOT work — Koin can't resolve `Impl`'s constructor params from DI when called through `single<T>(::Impl)`. Use `single { Impl(get(), ...) }` for interface bindings.
- `singleOf` fails for classes with function-type constructor parameters (Koin tries to resolve `Function1` from DI) — use explicit lambda in those cases.
- If a Tier 1 interface gains a 2nd implementation, restore the interface — never compromise final-by-default by adding `open` to the existing concrete class.
- Stale KDoc references `[OldInterface]` are dangling after inlining — always grep the whole repo and replace with `[CanonicalType]`.

## Links

- Files: 24 production + 3 test files (full list in commit message)
- Tests: DiGraphTest, BackupFileNamerTest (renamed DefaultBackupFileNamerTest), BackupViewModelTest
- Related: AGENTS.md, `singularity-todo-koin-di`, `singularity-todo-kotlin-idioms`, `singularity-todo-decisions-workflow`
