---
title: DI: `Modules.kt` is a facade, not the source of truth
date: 2026-09-27
status: accepted
tags: [koin, di, architecture, documentation]
---

# DI: `Modules.kt` is a facade, not the source of truth

## Context

Multiple skills and ADRs still claimed that `core/di/Modules.kt` (`domainModule`) is the
single source of truth for Koin bindings, listing "13 модулей" inside it. The actual
structure is different: bindings are produced by per-domain `*Module()` factory functions
living in dedicated files, and `Modules.kt` only aggregates them into a list.

Current layout:

```
core/di/
  Modules.kt              — coreLoggingModule() + domainModule(): List<Module> + expect aiToolsModule()
  CoreDiModule.kt         — coreModule(): ai, analytics, coroutines, log, settings, error, security
  CalendarDiModule.kt     — calendarModule()
  NotesDiModule.kt        — notesModule()
  ProjectsDiModule.kt     — projectsModule()
  TagsDiModule.kt         — tagsModule()
  TasksDiModule.kt        — tasksModule()
  PlatformModule.kt / PlatformModule.{jvm,android}.kt — platform bindings
  KoinBridge.kt           — suspend factory bridge
  KoogPromptExecutorPort.kt / PromptExecutorPort.kt / KoogPromptExecutorFactory.kt

feature/agenda/AgendaDiModule.kt                  — agendaModule()
feature/calendar_sync/di/CalendarSyncDiModule.kt  — calendarSyncModule()
feature/whatsnew/di/WhatsNewDiModule.kt           — whatsNewModule()
```

`domainModule()` returns a **list**, and `includes()` is deliberately avoided for those
modules: `includes()` creates a child scope whose bindings are not visible to sibling
modules at the parent level (Koin 4 scope isolation). Each module is passed directly to
`modules()` at the root Koin scope.

The one intentional exception is the profile block, which is inlined in `domainModule()` as
direct `single {}` / `viewModel {}` calls rather than routed through a `profileModule()`
function — for the same root-scope reason.

## Idea

Keep the facade (a single call site to register everything) but stop describing it as the
place where bindings live, and document the per-domain layout as the actual convention.

## Decision

- `core/di/Modules.kt` = **aggregator/facade**: `coreLoggingModule()`,
  `domainModule(): List<Module>`, and the `expect fun aiToolsModule()`.
- **New domain bindings go into the matching per-domain `*DiModule.kt`** (a `*Module()`
  factory function), not inline into `Modules.kt`.
- Platform-specific bindings go into `PlatformModule.{jvm,android}.kt`.
- Feature-scoped bindings (agenda, calendar_sync, whatsnew) live in the feature's own
  `*DiModule.kt`.
- `domainModule()` returns a list rather than nesting via `includes()` (Koin 4 scope
  isolation). Profile bindings are the documented exception — inlined at root scope.
- **Modules that need a runtime argument are registered by the app entry point, not by
  `domainModule()`.** `gateModule(playStoreUrl)` is the example: each platform passes its
  own store URL (`PLAY_STORE_URI` in `androidApp/SingularityApp.kt`, `RELEASES_URL` in
  `desktopApp/main.kt`). Do not add such modules to `domainModule()`.

## Rationale

The stale "Modules.kt is the source of truth" narrative caused repeated confusion when
agents looked for a binding that lived three files away. Splitting by domain keeps module
files under a readable size and puts bindings next to the feature they serve.

Returning a list is the cost of avoiding `includes()`. It is deliberate and documented in
`Modules.kt`'s own KDoc — the previous narrative ("13 модулей" via `domainModule`) no longer
described the code.

## Consequences

- `Modules.kt` stays small and stable; adding a feature no longer means editing it.
- `AGENTS.md` and `ARCHITECTURE.md` describe the facade + per-domain layout.
- Adding a binding to the wrong module is still possible, so a new Konsist test could
  assert that `Modules.kt` contains no `single {}` / `factory {}` calls outside the two
  facade functions. Deferred, tracked in
  `2026-09-27-doc-and-skills-sprint-findings.md`.

## Links

- `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md` — `viewModelOf` vs `viewModel {}`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/Modules.kt`
- skill `singularity-todo-koin-di`
