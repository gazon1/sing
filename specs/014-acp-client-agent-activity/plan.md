# Implementation Plan: External Agent in the App (ACP Client) and Agent Activity Visibility

**Branch**: `014-acp-client-agent-activity` | **Date**: 2026-10-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/014-acp-client-agent-activity/spec.md`

## Summary

Three independently mergeable parts. **A — Data safety**: make the startup database probe fail-safe, give external processes a profile mode that never touches the app's stored active profile, and declare how every agent-facing tool writes. **B — Activity visibility**: replace the text-only turn stream with a four-event activity vocabulary, give every turn exactly one terminal outcome, and render activity cards in the built-in chat. **C — External agent**: determine by a reproducible probe how the tool server is launched inside a packaged desktop build, then implement exactly one delivery mechanism, an ACP client over a process we own end-to-end, and a screen where the user connects an agent, watches it work, and answers its permission requests.

The technical spine is one shared idea: **the conversation travels on ACP, the tools travel on the app's existing MCP tool server**. The app gains no second copy of its tooling — it hands the agent a launchable command for the tool server it already runs, so domain access, the kill switch, the tool-call log and the proposal pipeline stay in one place.

Delivery order is A → B → C. A and B depend on nothing in this feature and are valuable alone; C depends on both.

## Technical Context

**Language/Version**: Kotlin 2.4 (Multiplatform), JVM toolchain 21; Android target bytecode JVM_11 — which is why every ACP SDK type stays in `jvmMain`.

**Primary Dependencies**: `com.agentclientprotocol:acp:0.34.0` + `acp-model:0.34.0` (exact pin, v1 packages only) · Compose Multiplatform · Koin 4.x · Room · Nav3 · Koog (tool execution only) · kotlinx.serialization · kotlinx.coroutines

**Storage**: Room over SQLite, WAL + `busy_timeout=5000`, deliberately shared by two processes (app + tool server).

**Testing**: `:shared:jvmTest` (commonTest runs inside it) and `:desktopApp:test`; fakes, never mocks; every test class tagged `fast`/`slow` with a sub-tag from the closed dictionary; ViewModel tests through the `testVm` harness with virtual time.

**Target Platform**: Desktop JVM (the packaged application) for Part C; the activity vocabulary in Part B is shared with Android.

**Project Type**: KMP desktop + Android application with a bundled tool-server executable.

**Performance Goals**: no turn may leave the UI in a loading state; a permission request that receives no answer is resolved within 5 minutes (fail-closed); a closed session leaves no process of its tree behind; median time from "agent selected" to "first visible agent response" under 15 s.

**Constraints**: the mobile build must not acquire any dependency on this feature · process spawning stays in the single file an architecture test allows · no pass-through use cases · no `stateIn` / `viewModelScope` / `runBlocking` in production · the tool server must not leak internal diagnostics in protocol responses · no auto-retry of a failed prompt or tool call.

**Scale/Scope**: 43 agent-facing tools, each to be classified; one new screen; one new port shape; 50 functional requirements.

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after Phase 1 — see the second table below.*

| Principle | Requirement | Status | Evidence |
|---|---|---|---|
| I. KMP Architecture — Android + JVM Desktop only | The external-agent half exists only on Desktop | PASS | All ACP code sits in `shared/src/jvmMain` and `desktopApp`; FR-046 forbids the mobile dependency; no `expect`/`actual` is added. |
| II. ADR Before Code | Decisions spanning more than a week of work need an ADR | PASS with an obligation | One ADR is planned: client role, first-party SDK over the Koog bridge, MCP stays the tool source, the delivery mechanism **after** the probe, the `--profile`/`--profile-id` contract, the trust boundary, process-tree ownership, and the reversal of "tool calls are not the reader's business". |
| III. Validate Early and Often | `singularity-adr validate` after ADR changes | PASS | Runs after the ADR is written, before tasks are closed. |
| IV. Immutable Historical Record | Accepted ADRs are superseded, never edited | PASS | `docs/decisions/2026-09-18-profile-subsystem.md` already intends `--profile=<id>` routing; this work realises it and links rather than edits. |
| V. CI Gates | ADR + openspec validation before merge | PASS | Both run; the branch is pushed without `--no-verify`. |
| VI. MVI ViewModels | `StateFlow<SealedUiState>` + sealed Intent + injected scope; no `stateIn`, `viewModelScope`, `runBlocking` | PASS | The external-agent screen's ViewModel owns the session and the process tree through an injected `AutoCloseableCoroutineScope` registered with `addCloseable`. |
| VII. Test Fakes | Fakes over mocks; virtual time | PASS | A fake ACP agent process and a fake agent that spawns a child replace every real agent in tests; no real clock or `delay(N)`. |
| VIII. OpenSpec Workflow | Behavioural changes go through proposal → specs → adr → tasks | PASS with an obligation | Three openspec changes, one per part, each with an issue-registry record created immediately after the change exists. |

**Pre-Phase-0 verdict: PASS.** No unjustified violations, so `Complexity Tracking` stays empty.

### Post-design re-check

| Question | Answer |
|---|---|
| Did design introduce a new cross-cutting concern? | Yes — a process-tree owner. It is confined to `jvmMain/core/process` and adds no port other code must implement. |
| Did design change a contract between layers? | Yes — the turn response shape. Mitigated by a single shared vocabulary in `core/llm` (D-16) and by three implementations with one call site (D-17). |
| Does any part need an exception to a principle? | No. |
| **Post-design verdict** | **PASS** — unchanged. |

## Project Structure

### Documentation (this feature)

```text
specs/014-acp-client-agent-activity/
├── spec.md              # the specification (/speckit-specify output)
├── plan.md              # this file (/speckit-plan output)
├── research.md          # Phase 0 output — 17 verified decisions
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── activity-events.md          # the shared turn vocabulary
│   ├── tool-write-classification.md # how every tool declares how it writes
│   └── tool-server-cli.md          # --profile / --profile-id, launch command
└── checklists/
    └── requirements.md # spec quality checklist (/speckit-specify output)
```

### Source Code (repository root)

```text
shared/src/commonMain/kotlin/com/singularity/todo/
├── core/llm/
│   ├── TextGenPort.kt                 # CHANGED: streamChat returns the activity flow
│   └── AgentEvent.kt                  # NEW: the four-kind activity vocabulary (D-17)
├── core/ai/
│   └── ToolCallLoop.kt                # CHANGED: emits activity, KDoc rewritten
├── core/di/
│   └── Modules.kt                     # CHANGED: profileModule() extracted, list-composed (D-14)
├── feature/ai/
│   ├── McpToolCatalog.kt              # CHANGED: writeBehavior + exceptionReason (D-15)
│   ├── KoogAgentService.kt            # CHANGED: maps Koog frames onto the vocabulary
│   ├── chat/UsageRecordingTextGen.kt  # CHANGED: passes activity through, keeps usage accounting
│   ├── chat/ChatViewModel.kt          # CHANGED: turn state, terminal outcome, activity list
│   ├── chat/ChatScreen.kt             # CHANGED: renders activity cards
│   └── ui/AgentActivityCard.kt        # NEW: one shared presentation for any agent surface

shared/src/jvmMain/kotlin/com/singularity/todo/
├── core/process/
│   ├── Subprocess.kt                  # CHANGED: adds spawnDuplex — it does not exist yet (D-04)
│   └── ProcessTree.kt                 # NEW: descendants → root, grace → force, idempotent (D-09)
├── core/database/contract/
│   └── StaleDbCleaner.kt              # CHANGED: fail-safe probe (FR-001–FR-005)
├── core/mcp/
│   └── McpServerCommandProvider.kt    # NEW: verified command, or a diagnosable error
├── feature/profile/
│   └── FixedProfileRepository.kt      # NEW: profile fixed by argument, never DataStore
├── feature/ai/acp/
│   ├── AcpClient.kt                   # NEW: the only file that touches the SDK
│   ├── AcpClientSessionOperations.kt  # NEW: two methods — requestPermissions, notify (D-02)
│   ├── AcpAgentProfile.kt             # NEW: registry of external agents (data)
│   ├── AcpEventAdapter.kt             # NEW: session updates → activity vocabulary (D-07, D-08)
│   └── di/AcpDiModule.jvm.kt          # NEW: bindings, list-composed
└── feature/ai/external/
    ├── AcpExternalAgentViewModel.kt   # NEW: MVI, owns session + process tree
    └── AcpExternalAgentScreen.kt      # NEW: the screen (must be wired — FR-045)

shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/
└── JvmNavEntries.kt                   # CHANGED: real call site for the new screen (D-19)

mcp-server/src/main/kotlin/com/singularity/todo/mcp/
└── Main.kt                            # CHANGED: --profile-id, serializer-built errors (FR-006–FR-016)

desktopApp/src/main/kotlin/com/singularity/todo/
└── main.kt                            # CHANGED: add acpModule() via list composition

scripts/
└── probe-packaged-tool-server.sh      # NEW: the reproducible delivery probe (D-13)
```

**Structure decision**: the existing KMP layout is kept unchanged. The one structural rule this feature introduces is that **the ACP SDK is touched in exactly one file** (`AcpClient.kt`), so an SDK upgrade is a single-file diff; every adapter concern (event mapping, permissions, process ownership) sits in files that do not import the SDK. Part A touches existing files only — it adds no new surface.

## Part sequencing and what each part proves

| Part | Merged when | Proves |
|---|---|---|
| A | FR-001–FR-016 green; probe absent | The user's database survives an ambiguous startup probe; a second process cannot hijack the active profile; every tool says how it writes. |
| B | FR-017–FR-027 green | The user sees every tool call and every terminal outcome; no turn ends spinning. |
| C | FR-028–FR-050 green, plus one real agent on a packaged build | A third-party agent works on the user's tasks, is supervised, and leaves nothing behind. |

## Carry-forward from research

- The probe is **Part C's first task**, not a parallel activity; Parts A and B must not wait on it.
- `Subprocess.spawnDuplex` **does not exist** — `Subprocess` currently has only `runQuietly` and `runCapturing`. Its KDoc documents the SIGPIPE/141 failure that shipped twice; the duplex path must drain stderr to a sink *without* folding it into the protocol stream, and must not close a read end early.
- The tool-server delivery mechanism is chosen by the probe's result, and only one mechanism is implemented.
- Adding `writeBehavior` to the catalog requires regenerating `config/docs/tool-surface.json` in the same change, or the gate reports a stale artifact instead of the real problem.
- `session.close()` is unstable and conditional — process-tree termination is the mechanism, not a nicety.

## Complexity Tracking

> No constitution violations to justify — the table stays empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| _(none)_ | — | — |