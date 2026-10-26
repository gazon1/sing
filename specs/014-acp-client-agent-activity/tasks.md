---
description: "Task list for External Agent (ACP Client) and Agent Activity Visibility"
---

# Tasks: External Agent in the App (ACP Client) and Agent Activity Visibility

**Input**: Design documents from `/specs/014-acp-client-agent-activity/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md
**Branch**: `014-acp-client-agent-activity`

**Tests**: included. The specification and quickstart require them, and the project's own rules require them — every test class carries a JUnit tag from the closed dictionary (`fast`, `slow`, with sub-tags `slow:konsist`, `slow:migration`, `slow:compose`, `slow:data`, `slow:platform`), ViewModel tests run through the `testVm` harness with virtual time, and fakes are used instead of mocks.

**Organization**: tasks are grouped by user story so each is independently implementable, testable and mergeable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: the user story this task belongs to
- Every task names exact file paths

## Story ↔ part mapping

The specification orders stories by user value; the plan orders parts by build dependency. They are not the same order, and this list follows the specification.

| Story | Priority | Part | Independent of |
|---|---|---|---|
| US1 — see what the agent is doing | P1 | B — activity visibility | everything else |
| US2 — my data stays mine | P2 | A — data safety | everything else |
| US3 — bring my own agent | P3 | C — external agent | US1 (shared vocabulary + renderer), US2 (profile isolation) |

**Recommended merge order is A → B → C**, because US3 needs both and A is the smaller, lower-risk increment. That is an ordering recommendation, not a dependency: US1 and US2 have no task depending on each other.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: build wiring and workflow registration. No scaffolding is needed — the project, the module layout and the DI conventions already exist.

- [X] T001 [P] Add `acp` and `acp-model` pinned to exactly `0.34.0` in `gradle/libs.versions.toml`, with a comment recording that this is an exact pin on a 0.x SDK and that v1 packages are used
- [X] T002 Declare both artifacts in the `jvmMain.dependencies` block of `shared/build.gradle.kts` only — **no `commonMain` and no `androidMain` entry**; the mobile build must not acquire this dependency (FR-046)
- [X] T003 [P] Create `scripts/probe-packaged-tool-server.sh`: builds a packaged desktop distribution and reports (a) what the runtime classpath contains at run time, (b) whether the tool-server main class starts from it, (c) where the application jar lives. It is a reproducible repository artifact, not a manual command, and its output is the input to the delivery decision in T033
- [X] T004 [P] Create the openspec change for Part A (data safety) under `openspec/changes/` **and its issue-registry record** at `.specify/issues/` in the same step — the registry gate fails on a change with no record
- [X] T005 [P] Create the openspec change for Part B (activity visibility) under `openspec/changes/` with its `.specify/issues/` record
- [X] T006 [P] Create the openspec change for Part C (external agent) under `openspec/changes/` with its `.specify/issues/` record

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the contract every agent surface speaks, plus the architecture decision. US1 and US3 both consume it; putting it here is what keeps US3 from depending on US1's implementation.

- [X] T007 [P] Write `docs/decisions/2026-10-23-acp-client-and-agent-activity.md` recording: client role (agent role deferred to Stage 2), first-party SDK instead of the Koog bridge, MCP stays the single tool source, the trust boundary and the write-classification registry, process-tree ownership, the `--profile` / `--profile-id` contract, and the reversal of "tool calls are not the reader's business". Leave it **open** — the delivery-mechanism section is resolved by the probe in T033 and filled in at T052
- [X] T008 [P] Create `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/AgentEvent.kt` with exactly four kinds (`TextDelta`, `ToolCallUpdated`, `PlanUpdated`, `TurnCompleted`), `ToolCallStatus { RUNNING, COMPLETED, FAILED }` and `PlanStepStatus { PENDING, IN_PROGRESS, COMPLETED }` — per `contracts/activity-events.md`
- [X] T009 Change `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/TextGenPort.kt`: `streamChat(message): Flow<AgentEvent>` replaces `Flow<String>`; `generate()` and `listModels()` are unchanged (FR-027)
- [X] T010 Update `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeTextGen.kt` to the new port shape so the module compiles; it must be able to emit an arbitrary event sequence, including a sequence that ends without a completion event
- [X] T011 [P] Create `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/ui/AgentActivityCard.kt`: presentation only — tool-call card with status, expandable bounded summary, plan card. Raw payload hidden by default, never rendered as markup, no business logic
- [X] T012 [P] Create `shared/src/commonTest/kotlin/com/singularity/todo/core/llm/AgentActivityTest.kt` asserting the vocabulary invariants: deltas concatenate to the answer rather than replacing it, a tool-call update for a known id updates in place instead of duplicating, a plan update replaces the previous snapshot, and a summary is length-bounded

**Checkpoint**: the shared contract exists and compiles on both targets; US1 and US2 can now proceed independently.

---

## Phase 3: User Story 1 — See what the agent is doing to my data (Priority: P1) 🎯 MVP

**Goal**: every tool call the built-in agent makes is visible with an outcome, and every turn reaches exactly one terminal outcome.

**Independent Test**: drive the chat with a prompt that triggers several calls, one of which fails; assert a card per call with name and outcome, the failure visible, and a second prompt that is cancelled ending in a state that is not loading. Mergeable and revertable without touching US2 or US3.

### Tests for User Story 1

- [X] T013 [P] [US1] Create `shared/src/commonTest/kotlin/com/singularity/todo/core/ai/ToolCallActivityTest.kt` (`@Tag("fast")`): assert `ToolCallLoop` emits RUNNING before a call, COMPLETED with a summary after it, FAILED with a summary when the tool throws, and `TurnCompleted` at normal end — tagged `fast`
- [X] T014 [P] [US1] Create `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/chat/ChatTurnOutcomeTest.kt` (`@Tag("fast")`) using the `testVm` harness from `shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/RunVmTest.kt`: assert exactly one terminal outcome for success, for a stream that ends without completion (failed, never completed), for cancellation, and that a late event after the outcome is ignored

### Implementation for User Story 1

- [X] T015 [US1] Rewrite `shared/src/commonMain/kotlin/com/singularity/todo/core/ai/ToolCallLoop.kt`: change `run(...)` to return `Flow<AgentEvent>`; emit `ToolCallUpdated(RUNNING)` before executing a call, COMPLETED or FAILED after, and `TurnCompleted` at normal end. **Rewrite the "What is emitted" KDoc section** (currently lines 58-62) to state the reversed principle and why
- [X] T016 [US1] Update `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/KoogAgentService.kt` so `streamChat` returns the loop's activity flow and `generate()` keeps returning text
- [X] T017 [US1] Update `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/UsageRecordingTextGen.kt` to pass events through untouched while preserving the usage accounting it performs today — assert the accounting is unchanged in T014's coverage
- [X] T018 [US1] Update `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatViewModel.kt`: add turn state (activity, outcome, truncation note) to `State`, accumulate deltas into message text, keep `isLoading` false on every terminal path, resolve the turn in `finally` so cancellation cannot leave it set
- [X] T019 [US1] Update `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatScreen.kt` to render one `AgentActivityCard` per tool-call id and one plan card, using the shared renderer — no second presentation
- [X] T020 [P] [US1] Add automation tags to `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` for the activity card, its status and its expand affordance, then regenerate `Maestro/TAGS.md` — a new constant is unusable until the catalog is rewritten (`./gw :shared:jvmTest -PupdateGoldens=true`, then re-run without the flag)

**Checkpoint**: US1 is fully functional on its own — the chat shows the work, and every turn ends.

---

## Phase 4: User Story 2 — My data stays mine (Priority: P2)

**Goal**: the startup probe cannot delete a database it merely failed to understand; a second process cannot change who the user is; every tool declares how it writes.

**Independent Test**: point the probe at locked, unreadable, damaged and empty files and assert each is byte-identical afterwards, while a valid non-app database is still cleaned up; start a tool-server process against a named profile and assert the stored active profile is unchanged.

### Tests for User Story 2

- [X] T021 [P] [US2] Create `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/StaleDbCleanerTest.kt` (`@Tag("slow")`, `@Tag("slow:data")`): one case each for locked, unreadable, corrupted, empty, valid-non-app and valid-app, asserting **content equality** (bytes, not presence) after the probe, and that companion files survive whenever the primary file does
- [X] T022 [P] [US2] Create `shared/src/jvmTest/kotlin/com/singularity/todo/mcp/ProfileArgumentTest.kt` (`@Tag("fast")`): `--profile-id` with a valid id, with an unknown id, with both selectors, `--profile=ai-agent`, `--profile=personal`, and neither — covering the table in `contracts/tool-server-cli.md`
- [X] T023 [P] [US2] Create `shared/src/jvmTest/kotlin/com/singularity/todo/mcp/JsonRpcErrorTest.kt` (`@Tag("fast")`): an error text containing a quote, a newline and a control character still parses as a protocol message and contains no path
- [X] T024 [P] [US2] Add the classification assertions to `desktopApp/src/jvmTest/kotlin/com/singularity/todo/ToolSurfaceWiringTest.kt`: fail when a tool carries no write classification, and when a direct-write exception carries no reason. Write them here so they fail before T030 — the assertions themselves are specified in T031
- [X] T025 [P] [US2] Create `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/ToolWriteContractTest.kt` (`@Tag("fast")`): for a proposal-gated tool, calling it creates a proposal, confirming it writes, and **declining leaves the data unchanged** — the negative case is the one that matters

### Implementation for User Story 2

- [X] T026 [US2] Rewrite `shared/src/jvmMain/kotlin/com/singularity/todo/core/database/contract/StaleDbCleaner.kt`: delete only when the file is readable, structurally valid and lacks the app's bookkeeping marker; on any ambiguity leave the file and its companion files untouched and record the diagnostic. Update the KDoc, which currently claims the current behaviour is safe
- [X] T027 [US2] The fixed-profile resolution: `ProfileRepositoryImpl` takes a fixed profile id and a null state store, so a process told which profile to work as never constructs the preferences store and never takes its file lock — wired by `profileModule(fixedProfileId)` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/profile/ProfileDiModule.kt`. **Amended after the convergence pass**: this task named a fixed-profile repository class that was never created; the behaviour shipped inside the existing repository, which is where it belongs
- [X] T028 [US2] Extract `profileModule()` in `shared/src/commonMain/kotlin/com/singularity/todo/core/di/Modules.kt` and add it with `add(profileModule())` inside the existing `buildList` — **never `includes()`**, whose child scope is the bug the inlined bindings were there to avoid. Have the tool server compose its own list with the fixed-profile module in place of the default
- [X] T029 [US2] Update `mcp-server/src/main/kotlin/com/singularity/todo/mcp/Main.kt`: add `--profile-id`, make both selectors a startup error, keep `--profile` semantics unchanged while logging deprecation, replace the hand-built `writeJsonRpcError` string interpolation with the existing serialiser so responses are always valid and carry no internal diagnostics, and make startup failure leave no live container, open database or half-started process
- [X] T030 [US2] Add `writeBehavior` (no default) and `exceptionReason` to `ToolEntry` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt` and classify all 43 entries — 13 `PROPOSAL_WRITE`, 3 `DIRECT_WRITE_EXCEPTION` with reasons (create tag, write decision record, update decision record), the rest `READ_ONLY` (later reclassified: `create tag` is a `PROPOSAL_WRITE` now, so the exceptions are two — see `docs/decisions/2026-10-23-the-agent-surface-says-what-it-does.md`)
- [X] T031 [US2] Extend `desktopApp/src/jvmTest/kotlin/com/singularity/todo/ToolSurfaceWiringTest.kt` and `shared/src/androidHostTest/kotlin/com/singularity/todo/mcp/ToolSurfaceWiringTest.kt` with the classification checks from `contracts/tool-write-classification.md` — the assertions T024 wrote first
- [X] T032 [US2] Regenerate `config/docs/tool-surface.json` via `./gw :shared:exportToolSurface` in the same change as T030 — otherwise the gate reports a stale artifact instead of the real problem

**Checkpoint**: US1 and US2 both work independently and neither depends on the other.

---

## Phase 5: User Story 3 — I can bring my own agent (Priority: P3)

**Goal**: a third-party agent connects inside the desktop app, works on the user's tasks through the app's own tool server, is supervised, and leaves nothing behind.

**Independent Test**: run a packaged build, connect a fake agent process, send a read and a write, observe the proposal, crash the agent mid-turn, close the session and assert no process of its tree remains.

### Ordering constraint

T033 comes first and **blocks T042**. The delivery mechanism follows the probe's result; implementing it beforehand is the failure mode this phase exists to avoid.

- [X] T033 [US3] Run `scripts/probe-packaged-tool-server.sh` against a real packaged distribution and record its result in the ADR `docs/decisions/2026-10-23-acp-client-and-agent-activity.md` (the "delivery-mechanism" section). Exactly one delivery mechanism is implemented on the strength of it. **Note the two probes**: this is the *packaging* probe, and the *bridge* probe (`AcpBridgeProbe.kt`) is a different thing — see T075
- [X] T034 [P] [US3] Create `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpEventAdapterTest.kt` (`@Tag("fast")`): exhaustively cover the mapping table in research.md D-07, including that the ignored session-update variants produce no event, that pending and in-progress both read as running, and that the five stop reasons collapse to the right outcomes
- [X] T035 [P] [US3] Create `shared/src/jvmTest/kotlin/com/singularity/todo/core/process/SubprocessDuplexTest.kt` (`@Tag("slow")`, `@Tag("slow:platform")`): real child processes over the duplex path — a child that writes more than a pipe buffer holds, a child that writes to stderr, clean EOF, and a missing binary
- [X] T036 [P] [US3] Create `shared/src/jvmTest/kotlin/com/singularity/todo/core/process/ProcessTreeTest.kt` (`@Tag("slow")`, `@Tag("slow:platform")`): a fake agent that itself starts a child; after close, **no** process from that tree remains, and a second close is safe
- [X] T037 [P] [US3] Create `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpClientProcessTest.kt` (`@Tag("slow")`, `@Tag("slow:platform")`): a fake ACP process for connect, stream, cancel, mid-turn crash, hung process, agent-not-installed, and protocol-version rejection
- [X] T038 [P] [US3] Create `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModelTest.kt` (`@Tag("fast")`) with the `testVm` harness: permission accept, decline, timeout → declined, cancel with a request outstanding, second send refused while a turn runs, and the no-tool-access warning surviving the first reply
- [X] T039 [P] [US3] Create `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentScreenTest.kt` (`@Tag("slow")`, `@Tag("slow:compose")`): the screen in isolation — agent selection, text streaming, activity cards, the permission sheet, cancel, and the warning visible before the first message

### Implementation for User Story 3

- [X] T040 [P] [US3] Add `spawnDuplex` to `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/Subprocess.kt`: bidirectional streams and a handle to the process. **stderr must be drained to its own sink and never folded into the protocol stream**, and no read end may be closed early — this file's KDoc documents the SIGPIPE/141 defect that shipped twice. No architecture-test change is needed: this file is already the only one allowed to spawn
- [X] T041 [P] [US3] Create `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/ProcessTree.kt`: owns the spawned process; on close collects descendants deepest-first, destroys them, waits a grace period, force-kills survivors, and is idempotent
- [X] T042 [US3] Create `shared/src/jvmMain/kotlin/com/singularity/todo/core/mcp/McpServerCommandProvider.kt`: returns a verified launch command — using the JRE shipped with the application, never a system `java` — or a diagnosable failure naming the command and where it was expected. The mechanism comes from T033's result
- [X] T043 [P] [US3] Create `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentProfile.kt`: the registry of external agents as data, with exactly one entry marked default
- [X] T044 [US3] Create `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClient.kt`: **the only file that imports the SDK**. Initialize (advertising neither file-system nor terminal capability), new session with the tool-server reference, prompt, cancel; map the flow per research.md D-05 and treat completion strictly as the completion event. File-level `@OptIn(UnstableApi::class)` rather than a project-wide opt-in, so the waiver is visible in review. **Amended after the convergence pass**: the file also holds the protocol's client operations and the translation out of the protocol's vocabulary — see T068 and the ADR `2026-10-25-the-sdk-lives-in-one-file.md`
- [X] T045 [US3] The client's session operations — exactly `requestPermissions` and `notify`, and **not** the file-system, terminal or elicitation methods, whose SDK defaults are the refusal (research.md D-02). **Amended after the convergence pass**: the file this task named no longer exists — the protocol's client operations live inside `AcpClient.kt`, built from the application's own port `AcpSessionPermissions`
- [X] T046 [US3] Create `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpEventAdapter.kt`: pure mapping into the activity vocabulary per research.md D-07 and D-08, including the ignore list. **Amended after the convergence pass**: the mapping's input is now the application's `AcpSessionUpdate`, not the protocol's `SessionUpdate` — the protocol's translation happens at the door, so this table holds no protocol type
- [X] T047 [US3] Create `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModel.kt`: MVI state and sealed intents; owns the session and the process tree through an injected `AutoCloseableCoroutineScope` registered with `addCloseable`. No `viewModelScope`, no `stateIn`, no `runBlocking`. Permission requests suspend and resolve to a decision; an unanswered request is declined after 5 minutes; a failed turn is never retried
- [X] T048 [US3] Create `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentScreen.kt`: agent selection, connect/disconnect, input, streaming response, activity cards reusing `AgentActivityCard`, the permission sheet, cancel, and the persistent no-tool-access warning
- [X] T049 [US3] Create `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/di/AcpDiModule.jvm.kt` and compose it into `desktopApp/src/main/kotlin/com/singularity/todo/main.kt` with list composition — no `toTypedArray()`, no `includes()`
- [X] T050 [US3] Add a real call site for the new screen in `shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt`. A `*Screen` composable with no call site is a blocking finding of the unwired-surfaces ratchet (FR-045)
- [X] T051 [US3] Deliver the tool server chosen in T033 in the packaged distribution **by the mechanism the ADR settled on**: the packaged image carries no `java` binary, so nothing is copied and no `nativeDistributions.packageContents` block exists — the tool server *is* the application, and the agent is handed the application's own executable with `--mcp-server --profile-id=…`. The ADR `2026-10-23-acp-client-and-agent-activity` is the record of that choice (this task's earlier wording named a mechanism the accepted ADR superseded)
- [X] T052 [US3] Fill the delivery-mechanism section of the ADR from T033, mark it accepted, run `singularity-adr validate`, and rebuild the digest with `./scripts/refresh-decisions-digest.sh`

**Checkpoint**: all three stories are independently functional; US3 works against a packaged build.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T053 [P] Write `docs/architecture/acp-client.md`: the client role, the SDK pin, the process lifecycle, the trust boundary, and the known limitation that the external agent's own cost is not surfaced (research.md D-12)
- [X] T054 Update `docs/architecture/mcp-server.md`: the `--profile-id` contract, serialised error responses, and the chosen delivery mechanism; update `docs/PLATFORM-REFERENCE.md` for the new JVM-only surface
- [X] T055 [P] Update `.agents/skills/singularity-todo-mcp-init/SKILL.md` and `.agents/skills/singularity-todo-multi-profile/SKILL.md` — both currently dictate `--profile=` in distributed manifests, and the legacy argument's meaning is now explicitly deprecated
- [X] T056 [P] Run the manual pass from `quickstart.md` C6: one real agent, one packaged build, one read, one confirmed write, one cancelled turn, one close
- [X] T057 Run the full gate set: `./gw :shared:detekt`, then `./gw :desktopApp:detekt`, `./gw :shared:jvmTest -Ptest.tags=fast,slow`, `scripts/find-unwired-surfaces.py`, `bash scripts/ci/static-gates.sh`, and `:koverVerify`

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup; blocks US1 and US3, and US2 only insofar as the ADR and the workflow registration must exist
- **US1 (Phase 3)** and **US2 (Phase 4)**: independent of each other; both depend on Foundational
- **US3 (Phase 5)**: depends on US1 (shared vocabulary and renderer) and US2 (profile isolation), and internally on T033 before T042
- **Polish (Phase 6)**: depends on all three stories

### User story dependencies

- **US1 (P1)**: no dependency on other stories
- **US2 (P2)**: no dependency on other stories
- **US3 (P3)**: consumes US1's vocabulary and renderer and US2's profile mode — the one real cross-story dependency in the feature

### Within each user story

- Tests are written alongside the type they cover and must fail before the implementation makes them pass
- Models and contracts before services, services before the surface that uses them
- The single SDK touch-point file is written before the adapter that depends on it, so an SDK mismatch surfaces once rather than in every file

### Parallel opportunities

- T001, T003, T004, T005, T006 are independent of one another
- T008, T011, T012 are independent of one another
- T021–T025 are independent of one another
- T034–T039 are independent of one another
- T040, T041, T043 are independent of one another
- T053, T055, T056 are independent of one another

---

## Parallel Example: User Story 2

```bash
# Launch the five test tasks together — different files, no shared state:
Task: "Create StaleDbCleanerTest.kt — probe fail-safe cases"
Task: "Create ProfileArgumentTest.kt — argument contract"
Task: "Create JsonRpcErrorTest.kt — valid, non-leaking errors"
Task: "Extend the catalog consistency test with classification"
Task: "Create ToolWriteContractTest.kt — confirm writes, decline does not"

# Then the implementation, which is largely independent per file:
Task: "StaleDbCleaner.kt fail-safe probe"
Task: "FixedProfileRepository.kt"
Task: "McpToolCatalog.kt classification of all 43 entries"
```

---

## Implementation Strategy

### MVP first (Foundational + US1)

1. Phase 1 Setup
2. Phase 2 Foundational
3. Phase 3 US1
4. **Stop and validate**: the chat shows every call, every outcome, and every turn ends
5. Merge — this is a complete, valuable increment that touches nothing in Parts A or C

### Incremental delivery

1. Setup + Foundational → contract in place
2. US1 → the user can see the agent work → **merge**
3. US2 → the data is safe and every tool is classified → **merge**
4. US3 → the user can bring their own agent → **merge**
5. Each merge stands alone; none of them requires the next

### Parallel team strategy

With more than one implementer:

1. Setup + Foundational together
2. Then: one implementer on US1, one on US2 — genuinely disjoint file sets
3. US3 starts once both have landed, because it consumes both

---

## Notes

- [P] = different files, no dependency on an incomplete task
- Every new test class carries a JUnit tag from the closed dictionary; `slow` means the test crosses a process boundary (a real subprocess, a real database, a Compose harness), not "it takes a while"
- ViewModel tests use `testVm` from `shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/RunVmTest.kt`; a hand-rolled scope fails the harness convention gate
- No pass-through use cases: where this feature looks like it wants one, inject the repository or port directly
- Never pipe a command into `head`/`tail` — redirect to a file and read the file, or the command dies on SIGPIPE with a misleading exit code
- Commit after each task or logical group; push without `--no-verify`

---

## Phase 7: Convergence

A convergence pass over the code as it stands, requirement by requirement, found the work
below still missing or partial. The order is CRITICAL first, then HIGH, then MEDIUM, then
LOW. Each item names the requirement it traces to and the kind of gap it closes.

- [X] T058 CRITICAL: route the built-in chat's production turn through the activity stream, so a tool call is visible in the app — the shipped path is the GenUI branch, which calls the model with no tools and emits no `AgentEvent`, so no activity card is ever drawn — and prove it with a test that drives the production wiring per US1/AC1 (partial) — **done**: the built-in turn goes through `TextGenPort` in one call; the GenUI transport and the second usage ledger are deleted, and `ChatTurnOutcomeTest` drives the screen wiring
- [X] T059 Record a terminal outcome when the GenUI path is cancelled: a `CancellationException` from `session.respond` escapes `getOrElse`, so the turn ends with no outcome at all per FR-022 (partial) — **done**: the turn records `Cancelled` before the cancellation is re-thrown (`ChatTurnOutcomeTest.aStoppedTurnIsCancelledAndKeepsWhatItSaid`)
- [X] T060 Keep internal diagnostics out of the tool server's responses: the registrar puts the raw exception message into `McpToolError.Internal`, whose text becomes the JSON-RPC error message the caller reads per FR-015 (partial) — **done**: `McpToolError.Internal` carries a **code** (`tool_call_failed`), the detail goes to the log, and `InternalErrorTest` holds the rule
- [X] T061 Decide and record what the tool-server version comparison is to be: nothing compares an expected version with a running one, and the app cannot see the version of a server the agent launches per FR-043 (missing) — **done**: the ADR `2026-10-25-the-version-we-can-compare` re-scopes FR-043 to the **protocol** version, which the client now compares and a mismatch refuses
- [ ] T062 Make the card summary's field policy explicit: a short note body is quoted verbatim, because the rule is length-based and not field-based per FR-020 (partial) — **open**: the rule is now *stated* (FR-020 names the bound, the field count and the value rule) and the residual gap is recorded in the specification's assumptions; making the policy field-based is a behaviour change and has not been made
- [X] T063 Report a provider with no API key as a failure outcome rather than as a successful completion per FR-023 (partial) — **done**: the built-in service no longer emits a completion when it has no key, so the turn ends as a failure
- [X] T064 Give the built-in chat a reachable way to stop a running turn: the cancellation mechanics exist, but no control reaches them per FR-024 (partial) — **done**: the input bar carries a stop control that cancels the running turn
- [X] T065 Cover the terminal-outcome cases the checked boxes claim — cancellation, a late event after the outcome, and a thrown transport failure — and point T012 and T014 at the files that hold the invariants per US1/AC2 (partial) — **done**: `ChatTurnOutcomeTest` names all three cases, and T012's fourth invariant (a length-bounded summary) is asserted in `AgentActivityTest`
- [X] T066 Write the five unlogged session lifecycle events — connection, disconnection, session identity, permission-request identity and process-tree termination — to the log per FR-044 (partial) — **done**: `AcpSessionEvent` is the typed vocabulary, `AcpLifecycleSink` is the port, and the log tag is `acp-session`
- [ ] T067 Bound a hung turn and offer a reconnect after a mid-turn crash: a hung agent leaves the screen running, and a crashed one keeps the connection connected per FR-040 (partial) — **open**: the lifecycle events now say when the session died, but the turn itself is still unbounded and the connection state is not corrected on a crash. FR-040 now names the 60-second hung threshold, so the work has a criterion
- [X] T068 Amend the plan's structural rule and T044's wording: the ACP SDK is touched in three production files, not one, and T045 and T046 require two of them per plan: Project Structure (contradicts) — **done the other way round**: the rule was right and the code was wrong, so the code was inverted — the SDK now lives in `AcpClient.kt` alone (`2026-10-25-the-sdk-lives-in-one-file`), and the plan and T044–T046 are amended to match
- [X] T069 Carry the tool-access state on the handshake-failure path, so a failed handshake does not leave the access state granted per FR-032 (partial) — **done**: the state starts *unknown* and a failed connection leaves it *unavailable*, with the reason
- [X] T070 Do not lose a pending permission request to a second one: the later request overwrites the earlier, which then waits out the timeout per FR-036 (partial) — **done**: the broker is a queue, the oldest request is the one on screen, and answering takes it off the screen at once
- [X] T071 Make the second-turn refusal reachable from the screen: the input is disabled while a turn runs, so the refusal is met at the ViewModel level only per FR-042 (partial) — **done**: the input stays enabled while a turn runs, so a second message reaches the ViewModel and is refused there with a message shown through the notification host; the Stop control beside the field ends the turn. See `openspec/changes/the-refusal-is-reachable-from-the-screen`
- [X] T072 Record the permission answer in the state, so that "the answer is visible afterwards" is visible and not only indirect per US3/AC4 (partial) — **done**: the answer stays in the state and the screen shows it
- [X] T073 Reconcile the task list with the tree and with the accepted ADR: T010, T012, T022 and T051 name paths that do not exist, and T051 describes work the ADR superseded per T051 (partial) — **done**: T012, T022 and T023 name the files that exist, T051 names the ADR's delivery mechanism, and T033's record home is the ADR
- [ ] T074 Record the built-in agent's plan surface, or its absence: `PlanUpdated` has no built-in producer, so the plan card is unreachable for the built-in agent per US1/AC3 (partial) — **open**: the plan card renders, but the built-in agent emits no plan
- [X] T075 Record the real-bridge probe in the feature's artifacts: it is work the specification and the tasks never asked for, and it is a bridge probe rather than the packaging probe T033 names (unrequested) — **done**: the two probes are named apart (**packaging probe** = `scripts/probe-packaged-tool-server.sh`, **bridge probe** = `AcpBridgeProbe.kt`), the bridge probe has a home in the artifacts and in the ADR, and its `runBlocking` is the feature's one recorded exception to Constitution VI

---

## Notes

- **A task closes only when its acceptance scenario runs on the production path**
  (constitution IX). This feature is where the rule came from: US1 was closed as
  delivered while the production wiring took the GenUI branch, which emits no
  `AgentEvent` — so no activity card was ever drawn, and the story's scenario was
  reachable only through a test double. The scenarios that closed the tasks below
  now drive the wiring the application uses.
- **Still open after the convergence pass**, each with its criterion written into
  the specification: T062 (the summary's field policy is length-based, not
  field-based), T067 (a hung turn is unbounded and a crashed agent keeps the
  connection connected), T074 (the built-in agent emits no plan).
