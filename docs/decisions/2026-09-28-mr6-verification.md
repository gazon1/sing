---
title: "MR-6 verification — the DI cleanup is real but cosmetic, the facade rule has nothing to guard yet, the repo move is backwards"
date: 2026-09-28
tags: [retro, tech-debt, koin, di, konsist, verification]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Verification of MR-6 before implementation, following the pattern of MR-1 through MR-5:
read the code, because the plan was written from the ADR corpus and the corpus has been
wrong every time.

**Outcome: one item is valid but worth little, one is a forward guard for an invariant
that already holds, and one would move the codebase further from consistency rather than
closer to it.**

## 1. `AiToolsModule` `factory` → `factoryOf` — valid, cosmetic, with two hard exceptions

The counts are right: **54** bindings in `AiToolsModule.jvm.kt`, **52** in
`AiToolsModule.android.kt`. All 54 / 52 are single-line; there are no multi-line blocks.

Breakdown of the JVM module, and what each shape means for `factoryOf`:

| Shape | Count | `factoryOf(::X)` |
|---|---|---|
| `factory { X(get()) }` | 22 | works |
| `factory { X(get(), get(), …) }` | 24 | works |
| `factory { X(get<TaskRepository>()) }` | 3 | works — the explicit type argument is redundant with the constructor's |
| `factory { X() }` (zero-arg tools) | 3 | works |
| `factory { X(arg = get()) }` (named) | 2 | works only after reordering to positional |
| **`factory<Interface> { Impl(get()) }`** | **2** | **impossible** |

**The two exceptions are interface bindings**, in both files:

```kotlin
factory<ProfileAwareSecureStorage> { ProfileAwareSecureStorage(get(), get()) }
factory<GenuiTransport> { KoogGenuiTransport(get()) }
```

`factoryOf(::Impl)` binds the *concrete* type. Binding an interface to an implementation
has to stay an explicit lambda — the project's own digest already records this:
*"`single<Interface>(::Impl)` does NOT work — Koin can't resolve `Impl`'s constructor
params from DI when called through `single<T>(::Impl)`."*

So the honest count is **~50 of 54 convertible per file**, not all of them.

**Prerequisite checked and satisfied:** `AGENTS.md` warns that `singleOf` / `factoryOf`
over an existing binding of the same type throws `BeanOverrideException`. A scan for
duplicate bound types in both files returns **none**, so the conversion is safe to
attempt. The other documented failure — `singleOf` "fails for classes with function-type
constructor parameters" — does not apply: no AI tool or use case takes a function type.

**What it is worth: about 100 lines across two files, and no behaviour change.** This is
cosmetic. It is defensible as consistency with the rest of the DI surface, and it is the
cheapest item in the whole roadmap, but it fixes nothing and risks a `BeanOverrideException`
if a later binding is added carelessly. It should be the *last* MR, not the one that
moves the roadmap.

## 2. Konsist rule for the `Modules.kt` facade — nothing to fix; it is a forward guard

The plan described this as closing an architectural invariant. The invariant already
holds.

`Modules.kt` contains exactly two facade functions, `coreLoggingModule()` and
`domainModule()`, and **all six bindings in the file are inside them**:

```kotlin
fun coreLoggingModule(): Module = module {
    factory { Logger.withTag("App") }
    singleOf(::LoggerHolder)
}

fun domainModule(): List<Module> = buildList {
    add(tasksModule()); add(projectsModule()); …
    add(module {                       // ← still inside domainModule()
        single<ProfileRepository> { … }
        single { ProfileAwareCurrentUser(…) }
        factory { ProfileBootstrapper(get()) }
        viewModel { AccountSettingsViewModel(…) }
    })
    …
}
```

There are zero `single {}` / `factory {}` / `viewModel {}` declarations outside the two
facades. A Konsist test would pass on its first run.

That is not a reason to skip it — it is the reason to write it. The rule locks an
invariant that is currently only documented in
`2026-09-27-di-module-aggregator-narrative` and in the `Modules.kt` KDoc, and the
project's history is a run of rules that were inactive without anyone noticing. The
wording in the roadmap should be corrected: **not "close the invariant", but "make the
invariant executable before it is first violated"** — which is the same discipline
`2026-09-26-preflight-retro-findings` R1 established for rulesets.

The test also needs a decision the plan did not make: whether `expect fun aiToolsModule()`
counts as a facade. It is a third entry point in the same file.

## 3. Moving `ProfileRepositoryImpl` and `InternalLinkRepositoryImpl` to `data/` — backwards

`2026-09-26-konsist-architecture-tests` recorded this as debt: *"live outside a `data/`
subfolder (package-structure inconsistency)"*. That framing does not survive a count.

Current layout of repository packages across the codebase:

| Location | Features |
|---|---|
| `.data` subpackage only | `tasks`, `projects`, `tags`, `agenda`, `calendar_sync` |
| feature root only | `notes`, `profile`, `reminders`, `checklist`, `archive` |
| **both** | `search`, `tags` |

So it is not "two files in the wrong place". Five features keep their repositories at the
feature root, and two features already have repositories in *both* places. Moving
`ProfileRepositoryImpl` and `InternalLinkRepositoryImpl` would produce a **third** state
rather than converging on one.

The real debt is that **the convention has never been settled**. It is a decision —
does this project want the `domain/` `data/` `presentation/` split applied uniformly, or
not — followed by a migration of all root-only repositories. As two file moves it is
actively counterproductive: it makes the spread wider while looking like a fix.

Both files are also outside the Konsist layer rules today, which match
`feature..domain..`, `feature..presentation..` and `feature..data..`; a package
`com.singularity.todo.feature.profile` is not one of those, so moving it changes which
rules apply to it. That is worth knowing before doing it, not after.

## Revised MR-6

1. **Konsist `DiFacadeTest`** — assert no `single {}` / `factory {}` / `viewModel {}`
   outside `coreLoggingModule()` / `domainModule()` / `expect fun aiToolsModule()`.
   Passes today; guards the future. Add a positive control, since a rule that has never
   failed is a rule that has never been tested.
2. **`AiToolsModule` `factory` → `factoryOf`** — ~50 bindings per file, leaving the two
   interface bindings as explicit lambdas with a comment saying why. Run the DI graph
   test, not just compilation: the failure mode is a runtime `BeanOverrideException`.
3. **The repository-package question** — not a move. Either record the current mixed
   state and why it is acceptable, or scope a separate migration that decides the
   convention first.

## Rules

- **A Konsist rule that passes on its first run is not a wasted rule** — it is a rule
  doing its job before the mistake is made. Say that, rather than describing it as
  closing a gap.
- **"Inconsistent with the convention" needs the convention counted.** Two files were
  called inconsistent; the codebase has three states, and moving those two adds a
  fourth.
- **Count before converting.** 54 bindings is not 54 convertible ones: interface bindings
  cannot be expressed with `factoryOf` at all.

## Links

- `2026-09-27-di-module-aggregator-narrative` — the invariant this test would make
  executable
- `2026-09-26-konsist-architecture-tests` — the rule set, and the debt entry this
  verification contradicts
- `2026-09-26-preflight-retro-findings` — R1, "a rule missing any of three steps reports
  nothing and looks working"
- `2026-09-28-mr5-verification` — the preceding verification, same method
