# Tasks: The data survives being unsure about it

Module prefixes: `shared/`, `desktopApp/`.

> **Where the work landed.** Part of this change's work was finished by the change that
> followed it — `agent-surface-follow-ups` — and most of the `mcp-server` module's tests
> moved to `shared/src/jvmTest` when the server's own code moved into the shared
> runtime. The module still holds the end-to-end test and the entry point's own `main`.
> The paths below are the ones the work lives at today; a checklist that names a file
> the repository does not have is a finding in its own right.

## 1. Tests first

- [x] shared/ Add the fail-safe probe tests: locked, unreadable, corrupted, empty, valid-not-ours, valid-ours — asserting content equality, not file presence. `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/StaleDbCleanerTest.kt`
- [x] shared/ Add the profile argument tests: valid id, unknown id, both selectors, legacy label for each documented value, neither. `shared/src/jvmTest/kotlin/com/singularity/todo/mcp/ProfileArgumentTest.kt`, `shared/src/jvmTest/kotlin/com/singularity/todo/mcp/ToolServerStartupTest.kt`
- [x] shared/ Add the error response tests: a quote, a newline, a control character; valid message, no path. `shared/src/jvmTest/kotlin/com/singularity/todo/mcp/JsonRpcErrorTest.kt`
- [x] desktopApp/ Add the classification completeness assertions to the existing registry consistency test. `desktopApp/src/jvmTest/kotlin/com/singularity/todo/ToolSurfaceWiringTest.kt`
- [x] shared/ Add the proposal contract test: confirm writes, decline does not. `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/ToolWriteContractTest.kt`

## 2. The probe

- [x] shared/ Rewrite the startup probe so that deletion happens only for a readable, valid database without the bookkeeping marker; every other outcome leaves the file and its companion files untouched and records the diagnostic. `shared/src/jvmMain/kotlin/com/singularity/todo/core/database/contract/StaleDbCleaner.kt`

## 3. The profile selector

- [x] shared/ Make the profile repository resolve a fixed profile id without the stored active-profile state: no state store is taken, and the repository may not switch or delete the profile it was given. `shared/src/commonMain/kotlin/com/singularity/todo/feature/profile/ProfileRepositoryImpl.kt`
- [x] shared/ Extract the profile bindings into a module and add it to the domain module **list** — never `includes()`, whose child scope is invisible to siblings. `shared/src/commonMain/kotlin/com/singularity/todo/feature/profile/ProfileDiModule.kt`, `shared/src/commonMain/kotlin/com/singularity/todo/core/di/Modules.kt`
- [x] shared/ Accept the profile id, refuse both selectors together, keep the legacy label's meaning and log its deprecation, build error responses with the serialiser, keep internal detail out of the response, and leave no half-started process on a startup failure. `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ProfileArguments.kt`, `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolServerStartup.kt`, `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolServerMain.kt`

## 4. The classification

- [x] shared/ Add the write behaviour field with no default, plus the exception reason, and classify every registered tool. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt`
- [x] shared/ Regenerate the exported tool surface. `config/docs/tool-surface.json` — published by `agent-surface-follow-ups`, and kept in step by `:shared:exportToolSurface` and `:shared:verifyToolSurface`.

## 5. Verification

- [x] Run the probe tests, the argument tests, the error tests and the classification checks. `./gw :shared:jvmTest -Ptest.population=full` — the plain command leaves the probe test out, because it is tagged `slow`.
- [x] Run the linter over the changed modules. `./gw :shared:detekt :desktopApp:detekt :mcp-server:detekt :androidApp:detekt`

## 6. What the review left open, and what closed it

The review of this change found one thing that was not fixed, and it was the reason the
verdict was a `fail`: the promise that every write asks first was verified for two of
the proposal-gated tools, and the other ten were declarations with no test.

**`agent-surface-follow-ups` closed it.** The classification is published in the exported
tool surface, the check reads the declaration against the tools' behaviour in both
directions, and every proposal-gated tool is driven through the round trip — the
aggregate check is what stops a fourteenth tool from shipping with no contract.

## 7. What this change's own scenarios still had no test for, and what they have now

Four of the scenarios in this change's spec deltas had no test that would fail if the
code broke. They have one now, and the record of what they were is here because a
scenario nobody drove is a promise nobody kept.

- [x] `REQ-DBSAFE-002` — *a locked file is kept*. The database is held by another
      connection while the probe runs; the file survives byte for byte, and the control
      at the end of the test says the very same file is deleted once the lock is gone.
- [x] `REQ-DBSAFE-002` — *a file the probe cannot read is kept*. And the record it
      leaves says it could not read the file, rather than that the file was not a
      database.
- [x] `REQ-MCPSRV-010` — *the stored profile state is never opened*. A fixed-profile
      graph with a store that cannot be built: the repository is built, so the store was
      never asked for. The control is the same graph without a fixed profile, which asks
      for the store and is refused.
- [x] `REQ-MCPSRV-011` — *an unknown profile id is refused*. The decision is a value,
      and the lookup it asks is a parameter, so the rule can be checked without a
      database.
- [x] `REQ-MCPSRV-012` — *the legacy label still works*. Every documented value of the
      label, and every label nobody knows.
