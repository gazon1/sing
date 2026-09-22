---
title: "ProfileAwareCurrentUser — pure DI, no static singleton"
date: 2026-09-24
tags: [profile, di, koin, ai-tools]
status: accepted
---

## Context

`ProfileAwareCurrentUser` previously had a static companion object with `instance`/`setInstance` — an escape hatch designed during early profile migration when AI tools were not yet integrated into the DI graph. After PR12b, all AI tools are fully injected via Koin, making the escape hatch dead code that violates the Compose Multiplatform guideline **"no globals in KMP shared code"**.

## Decision

1. **Remove companion static accessors** — `ProfileAwareCurrentUser.scopedUserId`, `ProfileAwareCurrentUser.current`, `ProfileAwareCurrentUser.instance`, and `setInstance()` are deleted. The class now has only instance members.

2. **AI tools receive `ProfileAwareCurrentUser` via constructor injection** — five tools were updated:
   - `CreateTaskTool(taskRepository, clock, currentUser)`
   - `CreateTagTool(tagsRepository, clock, currentUser)`
   - `CreateProjectTool(projectsRepository, clock, currentUser)`
   - `CreateNoteTool(notesRepository, clock, currentUser)`
   - `DecomposeAndCreateTool(taskRepository, clock, promptExecutor, model, currentUser)`

3. **DI bindings updated** on both platforms (android + jvm):
   ```kotlin
   factory { CreateTaskTool(get(), get(), get()) }  // + currentUser = get()
   ```

4. **`Modules.kt` simplified** — the `setInstance()` call is removed; Koin wires the singleton directly:
   ```kotlin
   single { ProfileAwareCurrentUser(get(), get(), createBackgroundScope()) }
   ```

5. **Test updates** — `WriteToolsTest` passes `FakeProfileAwareCurrentUser` directly to tool constructors. `ReadToolsProfileAwareTest` passes `profileAwareUser` to `FakeTaskRepository(explicitCurrentUser = currentUser)`.

## Rationale

- **No globals** — static mutable state in shared KMP code breaks multiplatform invariants.
- **Explicit over implicit** — all dependencies are visible in constructors and module bindings.
- **Testability** — tools are fully testable without `setInstance()` ceremony.
- **The escape hatch was a one-time migration tool** — it served its purpose and is now removed.

## Detekt rule

A custom rule `NoStaticProfileAwareCurrentUser` in `detekt-rules/` enforces that no code references `ProfileAwareCurrentUser` through static companion accessors.

## Consequences

- Any future code that needs `ProfileAwareCurrentUser` must receive it via constructor injection.
- The `FakeProfileAwareCurrentUser()` factory function in tests remains — it creates a real `ProfileAwareCurrentUser` instance using `FakeAuthRepository` + `FakeProfileRepository`.
- `FakeTaskRepository` retains a backward-compat `FakeProfileAwareCurrentUser()` fallback for its `currentUser` property when no explicit user is provided, so existing tests continue to compile.
