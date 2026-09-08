---
title: MCP server health audit — dead code, missing tests, contract hazards
date: 2026-09-08
tags: [mcp, audit, refactor, tests, dead-code]
status: accepted
---

## Context

After the `$schema` dialect bug and the `local-user` default userId bug, I audited
the MCP server module end-to-end. Three classes of problems remain that will keep
biting us if left as-is:

1. **Dead-code hazard** — the same bug (hardcoded `local-user`) exists in
   *two* more places besides the tools I already fixed. They are not currently
   wired into DI, but they will silently regress if anyone re-wires them.
2. **Two-tier error model is orphaned** — `ErrorMapper` exists with a clean
   sealed `McpToolError` (Validation / NotFound / Conflict / Unauthorized /
   Internal), but the actual execution path in `ToolRegistrar.handleToolCall`
   does **not** consult it. Every business error currently surfaces as
   `[InternalError] …` with `isError = true`, leaking stack traces to the LLM.
3. **Test coverage is zero** for the most important MCP boundaries: the
   schema builder, the three profile-aware read tools, and any tool beyond
   `listTools` at the e2e level.

---

## Findings

### 1. Dead-code duplicates the `local-user` bug

`shared/src/commonMain/.../feature/ai/tools/ToolFactories.kt:252,267,282` defines
`dataListTasksTool`, `dataSearchTasksTool`, `dataListLinkedTasksTool` — each
with the same hardcoded `UserId(args.userId.ifBlank { "local-user" })` or
`UserId(args.userId)` fallback. None of them are referenced in `AiToolsDiModule.kt`
or its jvm/android siblings, so they are dead. The fix is to either delete
them or apply the same profile-aware defaults so future re-wiring can't reintroduce
the bug.

### 2. `McpToolError` / `ErrorMapper` is orphaned

`errors/McpToolError.kt` defines the two-tier model (business errors → `isError: true`,
internal errors → thrown as JSON-RPC error -32603).
`errors/ErrorMapper.kt` has the converter. But `ToolRegistrar.handleToolCall`
catches **any** `Exception` and writes `[InternalError] ${e.message}` as a
tool result with `isError = true` — every kind of failure looks the same to the
LLM and stack-traces may leak. The LLM has no way to distinguish "I gave you a
bad userId" from "the database is down".

### 3. `pagination/CursorCodec.kt` is also orphaned

A fully-implemented base64 URL-safe cursor codec. None of the read tools
expose a `cursor` field in their input schema; none of the tool outputs include
a `nextCursor` field. Either wire it into `list_tasks` / `list_adrs` /
`list_projects`, or delete it.

### 4. Tool input schemas are tested only as JSON dump

`McpServerEndToEndTest.server_handles_initialize_and_lists_tools` asserts
that tool count > 0. There is no assertion that any specific tool actually
*works* end-to-end — read OR write. The `$schema` regression we just fixed
slipped through this test.

### 5. Missing unit tests

- `KoogJsonSchemaBuilder` — no unit test for required parameters, optional
  parameters, `AnyOf`, `List`, nested `Object`, or the no-`$schema` invariant
  that we just bet the regression test on.
- `ListTasksTool`, `ListLinkedTasksTool`, `SearchTasksTool` — no unit test
  for the profile-aware default behavior. The `local-user` regression we just
  fixed slipped through commonTest.
- `FakeProfileAwareCurrentUser` exists but is not exercised by any test for
  this code path.

---

## Decision

### Fix dead-code hazard (do this now)

Apply the same profile-aware default userId pattern to
`dataListTasksTool`, `dataSearchTasksTool`, `dataListLinkedTasksTool` in
`ToolFactories.kt`. If they're deleted in the future, the same test should
keep passing — so we test the *behavior* of the wired factories, not these
unwired helpers.

### Wire `ErrorMapper` into `ToolRegistrar` (do this now)

`ToolRegistrar.handleToolCall` should:

1. Catch `McpToolError` first and call `ErrorMapper.toResult(error)`.
2. Then catch generic `Exception` and wrap it as `McpToolError.Internal`.

This makes Validation / NotFound / Conflict / Unauthorized show up as
structured `[Validation] field: message` text the LLM can parse, and stops
raw exception messages from leaking.

### Add missing tests (do this now)

| Test | Location | Why |
|---|---|---|
| `KoogJsonSchemaBuilderTest` | `mcp-server/src/test/.../schema/` | Locks in the no-`$schema` invariant; covers required + optional + List + AnyOf + Object parameter shapes |
| `ListTasksToolTest`, `ListLinkedTasksToolTest`, `SearchTasksToolTest` | `shared/src/commonTest/.../feature/ai/tools/` | Locks in profile-aware default userId; asserts blank userId → scopedUserId.value |
| `McpToolRoundTripTest` | `mcp-server/src/test/.../` | Spawns JAR, calls `initialize` + `tools/call list_tasks` + `tools/call create_task` + `tools/call get_task`; asserts each returns data, not empty |

### Defer (not this PR)

- Wiring `CursorCodec` into the read tools — needs a separate ADR for pagination
  contract (cursor format, max page size, how the LLM should treat `nextCursor`).
- Refactoring `Main.kt` (currently 302 lines with profile bootstrap, retro-migrate,
  Koin bootstrap, DB verify, JSON-RPC error writer, and platform module factory
  all in one file). Extract into `Bootstrap.kt`, `RetroMigrate.kt`,
  `PlatformModule.kt`, `JsonRpcError.kt`. Defer until a third bootstrap concern
  is added.

---

## Rationale

- The dead-code fixes cost almost nothing (one parameter + three test cases)
  and eliminate a regression vector that's already been exploited twice.
- Wiring `ErrorMapper` is a small change in `ToolRegistrar` but materially
  improves the LLM experience: it can now distinguish "give me a different id"
  from "your environment is broken".
- Tests target the contract boundary, not the implementation, so refactors
  downstream don't churn them.

---

## Consequences

- One new unit test file in `mcp-server` (`KoogJsonSchemaBuilderTest`).
- Three new unit test files in `shared/commonTest` for the read tools.
- One new e2e test in `mcp-server` (`McpToolRoundTripTest`).
- `ToolRegistrar` catches `McpToolError` first (small diff).
- `ToolFactories.kt` gets the profile-aware default applied (small diff,
  even though the factories are currently dead — guards future re-wiring).

---

## Links

- `mcp-server/src/main/kotlin/.../schema/KoogJsonSchemaBuilder.kt`
- `mcp-server/src/main/kotlin/.../ToolRegistrar.kt`
- `mcp-server/src/main/kotlin/.../errors/ErrorMapper.kt`
- `mcp-server/src/main/kotlin/.../errors/McpToolError.kt`
- `mcp-server/src/main/kotlin/.../pagination/CursorCodec.kt` (orphan)
- `shared/src/commonMain/.../feature/ai/tools/ToolFactories.kt:248-289` (orphan duplicates)
- `shared/src/commonMain/.../feature/ai/tools/ListTasksTool.kt` (live, fixed)
- `docs/decisions/2026-09-08-mcp-schema-and-profile-userid-fixes.md` (prior ADR)
