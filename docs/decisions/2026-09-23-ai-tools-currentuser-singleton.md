---
title: "AI tools: ProfileAwareCurrentUser as global singleton"
status: accepted
date: 2026-09-23
deciders: 
---

# AI tools: ProfileAwareCurrentUser as global singleton

## Context

After Phase 3 (VM migration to `*ForCurrentUser` repository methods), VMs no longer
need `ProfileAwareCurrentUser` in their constructors — the repository owns the userId.
But 10 AI tools (`CreateTaskTool`, `ListTasksTool`, `SearchTasksTool`,
`ListLinkedTasksTool`, `ListProjectsTool`, `DeleteProjectTool`, `CreateNoteTool`,
`CreateProjectTool`, `CreateTagTool`, `DecomposeAndCreateTool`) still receive
`ProfileAwareCurrentUser` via constructor injection to read the current user at
mutation/read time.

The tools are constructed by Koog's `SimpleTool` factory pattern where each tool is a
fresh instance per LLM call. Constructor injection forces every tool factory in Koin
DI to declare `get<ProfileAwareCurrentUser>()` even though the singleton never
changes between calls — pure ceremony.

## Idea

Promote `ProfileAwareCurrentUser` to a **global companion-object accessor**, set
once during app boot via `ProfileAwareCurrentUser.setInstance(...)`. AI tools read
it via `ProfileAwareCurrentUser.current` / `scopedUserId` without constructor
parameters.

**Why a global and not just delegate to repos?**

Two of the 10 tools (`CreateTaskTool`, `CreateNoteTool`, `CreateProjectTool`,
`CreateTagTool`, `DecomposeAndCreateTool`) need the userId **at mutation time** to
stamp `entity.userId` on the new row. The Phase 1 plan (caller-trust) requires the
caller to provide `userId`. A factory-side injection would be cleaner architecturally,
but inserting the userId at the repo layer for these tools would shift auth-safety
decisions into the repository (per the ADR risk: `BackupRepository hardcodes
UserId.anonymous`). The global keeps auth-safety explicit at the tool boundary
without poisoning DI.

The other 5 tools (`List*Tool`, `Search*Tool`, `DeleteProjectTool`) read via the
new reactive repos (`observe*ForCurrentUser`) — Phase 5 will eventually migrate
them, but for now they call `ProfileAwareCurrentUser.current` once per execution.

## Decision

1. **Global accessor (no static class hack)**:
   - File-private `var _currentUserInstance: ProfileAwareCurrentUser? = null`
   - `ProfileAwareCurrentUser.setInstance(instance)` writes to it during DI boot
   - Companion getters `scopedUserId` / `current` throw a clear error if not set
2. **Wiring** in `domainModule()` (`Modules.kt`):
   ```kotlin
   single {
       val instance = ProfileAwareCurrentUser(get(), get(), createBackgroundScope())
       ProfileAwareCurrentUser.setInstance(instance)
       instance
   }
   ```
3. **Tool signature change**: drop the `currentUser` parameter; read userId inside
   via `ProfileAwareCurrentUser.current`.
4. **Test fixture**: tools tests must call `ProfileAwareCurrentUser.setInstance(fake)`
   in an `init {}` block before any `tool.execute(args)` call.
5. **mcp-server is unaffected** — it never instantiates Koog tools directly (goes
   through Koin DI root scope which already calls `setInstance`).

## Rationale

- **Eliminates 10 constructor params + 10 DI factory arity changes** for pure
  ceremony (the singleton never differs per-call).
- **Companion-object stays one-time-initialized** (file-private `_instance`); no
  thread-race in practice (Koin `single {}` runs once at boot, before any
  `tool.execute`).
- **Error path is loud**: `error("setInstance() not called")` if DI is misconfigured,
  preventing silent wrong-user reads.
- **Test-first ergonomic**: `setInstance(fake)` then `setInstance(otherFake)` between
  tests is one line, no `factory { Tool(currentUser: get()) }` boilerplate.

## Consequences

- **Tool APIs lose their `currentUser: ProfileAwareCurrentUser` parameter** — any
  custom consumer (the MCP server's `ToolRegistrar`) must call `setInstance` before
  resolving tools. The single-MCP-server process does this via Koin DI startup.
- **Unit tests gain an `init { ProfileAwareCurrentUser.setInstance(fake) }` setup
  step** in tool test classes — standardized across `WriteToolsTest`,
  `ReadToolsProfileAwareTest`.
- **No new auth-safety risk**: each tool still stamps the user-provided
  `userId` from MCP argument, falling back to `ProfileAwareCurrentUser.current` when
  blank. The leak path now goes through a clearly-named global rather than DI
  plumbing — easier to grep, easier to audit.

## Risks

| Risk | Mitigation |
|---|---|
| `setInstance` called twice (DI hot-reload, test fixture leak) | Mutable `var` accepts overwrite; if a tool captures `scopedUserId.value` before swap, it keeps the stale value. Acceptable for tools (short-lived). |
| MCP server forgets to call `setInstance` | Companion `scopedUserId` throws with file:line reference to `setInstance`. |
| Test isolation (one test pollutes next via global) | Each test class does `init { setInstance(fake) }` in declaration order — works for sequential JUnit execution. |

## Known follow-ups (out of scope)

- **Phase 5.1**: Drop all-old-API signatures (`watchTask(id)`, `getById(id)`,
  `watchNote(id)`, `watchProject(id)`, `watchTags(userId)`, etc.) from repos. AI
  tools (`GetTaskTool`, `UpdateTaskTool`) still use them; leak surface exists
  though not exploitable from current callers.
- **Phase 5.4**: `SavedAgendaViewsRepository` migration to `UserId` + `ForCurrentUser`.
  Currently uses `String userId`.

## Links

- `ProfileAwareCurrentUser.kt:55-75` — companion singleton pattern
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/Modules.kt:46-52` — wiring
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/di/AiToolsModule.jvm.kt:137-169` — factories post-migration
- Skill `singularity-todo-ai-tool` — Koog `SimpleTool` lifecycle
- ADR `2026-09-21-user-scoped-repository.md` — Phase 1 caller-trust pattern
