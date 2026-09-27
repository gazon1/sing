---
title: "Konsist architecture tests — the first hard CI gate for layer boundaries"
date: 2026-09-26
tags: [architecture, testing, konsist, ci]
status: accepted
---

# Konsist architecture tests — the first hard CI gate for layer boundaries

> **Correction (2026-09-27):** finding 13 below claims the `NoFactoryViewModel` detekt rule
> was added and caught `CalendarSyncViewModel`. The rule file existed but its provider was
> never listed in `META-INF/services/dev.detekt.api.RuleSetProvider`, so it never ran — the
> finding must have come from a manual review, not from the rule. The provider is now
> registered and the rule is active with 0 violations across `shared`.

## Context

Layer boundaries (`presentation → domain ← data`, Koog-import confinement, `java.io.File`
ban, `*RepositoryImpl` visible only to DI) were enforced by two soft mechanisms: custom
detekt rules running with `ignoreFailures = true` (report-only) and a manual grep audit
(`singularity-todo-clean-architecture-audit` skill) that never ran in CI. Nothing failed
a build on a boundary violation. A pre-scan found 4 real violations already on `main`.

Adopting Konsist (from Tim Malseed's "Gradle Modularisation" article thesis: enforce
boundaries with tests over modules) was chosen over modularising the KMP `shared` module —
packages + automated tests give the same protection without 20+ KMP modules.

## Idea

1. **Konsist tests** in `:shared:jvmTest` — structural assertions over `commonMain`
   sources; runs in check.sh and CI automatically.
2. **Custom detekt rules** in `:detekt-rules` — same checks as import-based rules; faster
   feedback (`just lint`), but clunkier for package-boundary logic.
3. **ArchUnit** — bytecode-based, Java-centric, weak for Kotlin-specific structure.

## Decision

Konsist 0.17.3 wired into `shared/src/jvmTest` as `ArchitectureTest`
(`shared/src/jvmTest/kotlin/com/singularity/todo/arch/ArchitectureTest.kt`), one scope in
`companion object` over `Konsist.scopeFromExternalDirectory(<commonMain absolute path>)`
(injected as the `commonMain.root` system property by the jvmTest task config — do not use
`scopeFromDirectory`, it resolves relative to Konsist's detected project root and doubles
the prefix).

Rules (all hard-failing):

1. `feature..domain..` must not import `feature..presentation..` / `..data..`
2. `feature..presentation..` must not import `feature..data..`
3. `feature..data..` must not import `feature..presentation..`
4. `ai.koog..` imports only in `feature.ai`, `feature.genui`, `core.di`, `core.llm`, `core.ai`
5. No `import java.io.File` in commonMain (import-form only — FQN usage is not caught)
6. `*RepositoryImpl` imported only from `core.di`
7. `*Repository` interfaces declare no `*Blocking` methods

Violations fixed as part of this ADR: `CalendarAppInfo` → `domain/model`,
`CalendarAppQueries` → `domain/port` (presentation no longer imports data),
`SyncDiffMerge` switched from the Room entity to the domain read model
`SyncedEventRef` (mapping via `CalendarSyncTaskMapMappers.toSyncedEventRef()`).

## Rationale

- Konsist over detekt rules: the user explicitly chose it; DSL reads like specs; source
  level (works for KMP `commonMain` where bytecode doesn't exist). Detekt keeps the
  per-file smell rules (VM patterns etc.) — the two are complementary.
- Known risk, resolved by experiment: the root `resolutionStrategy` forces every
  `org.jetbrains.kotlin` artifact (incl. Konsist's `kotlin-compiler-embeddable`) to
  2.3.21, while Konsist 0.17.3 was built on 2.1.0. Parsing the full commonMain works —
  no PSI incompatibility found. If a future Kotlin release breaks parsing, the fallback
  is reimplementing rules 1–7 as detekt rules.
- `TaskDraft`/`DueDateOption` stay in presentation (allowlisted): they carry Compose
  `@Immutable` for stability; moving them to domain would violate the "pure domain has
  no Compose imports" convention.

## Consequences

- **Always** extend `LAYER_ALLOWLIST` in `ArchitectureTest` together with a debt entry
  in this ADR — an undocumented allowlist entry is a regression.
- **Never** import `*RepositoryImpl` outside `core/di` — DI composition root only.
- **Never** add `*Blocking` methods to `*Repository` interfaces.
- **Always** keep new Koog imports inside the five sanctioned packages.
- Boundary checks are now the first **hard** CI gate; detekt remains report-only until
  the separate hardening PR (`just detekt-fix` → re-baseline → flip `ignoreFailures`).
- Rule "core must not import feature" is **not gated** — 19 files violate it today
  (`core/database`, `core/settings`, `core/tree/Cascade.kt`, `core/sync`, `core/ui`,
  `core/backup`, `core/auth`, `core/repository`, `core/draft`, `core/security`).
  Fixing requires decoupling core from feature domain models — separate epic.
- Debt: `feature/profile/ProfileRepositoryImpl.kt` and `feature/search/InternalLinkRepositoryImpl.kt`
  live outside a `data/` subfolder (package-structure inconsistency).
- Debt: `calendar_sync` package name contains an underscore — every new file there adds
  `PackageName` detekt findings (report-only, pre-existing pattern).

## Findings (phase-by-phase review, 2026-09-26)

Found while implementing (ritual: critical → fixed immediately, rest → recorded here):

1. **CI was broken at two levels.** (a) `gradle-wrapper.jar` was effectively gitignored —
   the `!gradle/wrapper/gradle-wrapper.jar` negation sat above the blanket `*.jar` rule
   (last match wins), so every fresh checkout died with exit 1 in ~100 ms. Fixed in MR-0
   (negation moved below `*.jar`, jar committed, CI `setup-java` temurin → zulu to match
   `toolchainVendor=AZUL` in `gradle-daemon-jvm.properties`). (b) Even with a working
   wrapper, `:shared:jvmTest` is red on `main` HEAD: 58 failing tests in 15 classes.
2. **58 failing tests on main HEAD** — ~40 are VM tests with
   `UncompletedCoroutinesError` (active child jobs, 60 s timeout each — the failing suite
   also makes CI take ~8× longer). This belongs to the in-flight MVI migration
   (MR-6d, 19/21 VMs; uncommitted fixes exist in the main checkout). Owner: the
   migration epic, not this PR.
3. **`PlatformPragmasTest` had a deterministic test bug**: `List.contains("journal_mode")`
   checks element equality, not substring — could never pass. Fixed (→ `any { it.contains(...) }`).
4. **`BackupOptionsTest` depends on the build environment** (`expected: 0.0.11, was:
   0.0.0-dev`) — version must come from a deterministic source (generated file or
   explicit default) for clean checkouts/CI.
5. **`OAuthTokenRefreshTest` is wall-clock flaky** (millisecond timestamp comparison).
6. **`AutoSyncTest` fails with "Only a single call to `runTest` can be performed"**
   (nested `runTest` — test bug or coroutine-test version behaviour change).
7. **`SyncRepositoryCoalescingTest`** is concurrency-flaky (`expected: <1> but was: <2>`).
8. **`just setup-hooks` is broken for linked worktrees**: for a worktree it computes
   `HOOKS_SOURCE = <main>/.git/.githooks` (double dirname of `.git/worktrees/<name>`),
   and `.githooks/` is not tracked in git at all — hooks silently do not exist anywhere,
   `setup-hooks` exits 2 on its final `ls`. Needs the path fix + committing `.githooks/`.
9. **CI log aggregation shows every step as `UNKNOWN STEP`** — failed-run diagnosis is
   unnecessarily hard (worth switching to explicit step ids / fixing the workflow grouping).
10. **7 of 10 custom detekt rule sets were dead code.** Their `RuleSetProvider`s were
    never registered in `META-INF/services/dev.detekt.api.RuleSetProvider`
    (NoStateIn, NoCombineSideEffect, NoGlobalScopeLaunch, NoStaticProfileAwareCurrentUser,
    PassThroughUseCase) and their config sections were missing from `detekt.yml`
    (incl. `no-runblocking`) — a detekt rule without a config section is inactive, so
    these rules silently never ran. Fixed: all providers registered, all sections
    restored (active: true). Additionally, `visitCallExpression` overrides must call
    `super` first or the tree traversal stops at the first call expression —
    `NoRunBlockingRule` had this bug too.
11. **`NoRunBlocking` now surfaces 7 pre-existing violations** (report-only, no build
    break): `FileLogWriter.kt:56,63` (shutdown flush), `KoinBridge.kt:17` (sanctioned
    bridge), `SettingsDataStoreMigration.kt:89` (migration helper),
    `PlatformModule.android.kt:148,149`, `PlatformModule.jvm.kt:91`. Before promoting
    detekt to error mode, each needs either a refactor or an explicit
    `@Suppress("NoRunBlocking")` with a justification comment.
12. **`CalendarSyncViewModel` was registered via `factory<CalendarSyncViewModel>`** —
    exactly the memory-leak pattern the rules ban (a factory VM's injected
    `AutoCloseableCoroutineScope` is never closed). Caught by the new
    `NoFactoryViewModel` rule on its first real run; fixed to `viewModel<...>`.
13. **`NoFactoryViewModel` detekt rule added** (`no-factory-viewmodel` ruleset,
    warning-level like the rest): bans `factory { ... *ViewModel(...) }` and
    `factoryOf(::*ViewModel)`; 5 unit tests. `viewModel { }` / `viewModelOf(::...)`
    are not flagged.

## Links

- Commits: MR-0 `fix(ci): commit gradle-wrapper.jar and align CI JDK with daemon criteria`
- Files: `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ArchitectureTest.kt`,
  `shared/build.gradle.kts` (konsist dep + `commonMain.root` property),
  `gradle/libs.versions.toml`, `feature/calendar_sync/**` (boundary fixes)
- Related: `2026-09-17-orgmode-architectural-lessons` (pure-domain conventions),
  `2026-09-25-test-suite-tag-defaults` (test double policy)
