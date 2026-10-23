# Phase 0 Research: External Agent (ACP Client) and Agent Activity Visibility

**Feature**: `specs/014-acp-client-agent-activity`
**Date**: 2026-10-23
**Method**: the ACP SDK was downloaded from Maven Central and its 0.34.0 sources read directly; every claim about the project is taken from the working tree, not from the brief.

## Verified SDK surface (basis for every SDK decision below)

`com.agentclientprotocol:acp:0.34.0` and `com.agentclientprotocol:acp-model:0.34.0`, published 2026-10-21 (`lastUpdated 20261021122402`). Sources extracted to `/tmp/acp-probe`. The package `com.agentclientprotocol.model.v2` exists alongside the v1 `model` package — **this feature uses the v1 packages only**; v2 is draft and every relevant v2 type is annotated `@UnstableApi`.

Facts that constrain the design:

| Fact | Consequence |
|---|---|
| `ClientSessionOperations : FileSystemOperations, TerminalOperations, ElicitationOperations` with **no default** for `requestPermissions` and `notify` | Exactly two methods must be written. |
| `FileSystemOperations`, `TerminalOperations`, `ElicitationOperations` **all have default bodies** that `throw NotImplementedError("Must be implemented by client when advertising … capability")` | We implement none of them. See D-02. |
| `ClientSession.prompt(content: List<ContentBlock>): Flow<Event>`; `Event` is sealed with exactly `SessionUpdateEvent(update)` and `PromptResponseEvent(response)` | A turn's success is an **event**, not the end of the flow. See D-05. |
| `PromptResponse(stopReason: StopReason, userMessageId, usage)`; `StopReason` = `END_TURN, MAX_TOKENS, MAX_TURN_REQUESTS, REFUSAL, CANCELLED` | Five protocol reasons collapse into three app outcomes. See D-06. |
| `SessionUpdate` variants include `ToolCall`, `ToolCallUpdate`, `AgentMessageChunk`, `AgentThoughtChunk`, `UserMessageChunk`, `PlanUpdate`, `PlanUpdateV2`, `PlanRemoved`, `AvailableCommandsUpdate`, `CurrentModeUpdate`, `ConfigOptionUpdate`, `SessionInfoUpdate`, `UsageUpdate`, `Unstructured`, `UnknownSessionUpdate` | Most variants have no place in a four-event vocabulary. See D-07. |
| `ToolCallStatus` = `PENDING, IN_PROGRESS, COMPLETED, FAILED` (four) | Collapses into the app's three statuses. See D-08. |
| `PlanEntry(content, priority, status)`; `PlanEntryStatus` = `PENDING, IN_PROGRESS, COMPLETED` | Maps one-to-one onto the app's plan step statuses. |
| `SessionCreationParameters(cwd, mcpServers: List<McpServer>, additionalDirectories, _meta)` — `mcpServers` is **non-nullable** | "No tool access" is expressed as an empty list, never `null`. |
| `McpServer.Stdio(name, command, args, env)`, KDoc: *"All Agents MUST support this transport"*; `Http`/`Sse` need `agentCapabilities.mcpCapabilities.http/sse`, which default to `false` | stdio is the only universally supported form. See D-03. |
| `StdioTransport(parentScope, ioDispatcher, input: Flow<String>, output: suspend (String) -> Unit)` — it **does not spawn anything** | Process creation stays in our own port. See D-04. |
| The `Source`/`Sink` `StdioTransport` constructor is `@Deprecated(level = WARNING)` — *"pins a dispatcher thread per read"* | Use the Flow-based constructor. |
| `Protocol(parentScope, transport, options)`; `Protocol.start()` | Transport must be started explicitly. |
| `ClientSession.close()` is `@UnstableApi` and *"Only available if the agent advertises the `session.close` capability"* | Closing cannot rely on it. See D-09. |
| `Client.initialize(clientInfo)` throws `UnsupportedProtocolVersionException` and **closes the connection** when the agent's version is unacceptable | A distinct, diagnosable connect failure. See D-10. |
| `ClientInfo.protocolVersion` must equal `max(supportedProtocolVersions)` (enforced in `init`) | Constructing `ClientInfo` incorrectly throws at construction time. |
| `AgentInfo(…, authMethods, …)` and `Client.authenticate(methodId)` | An agent may demand authentication. See D-11. |
| `ClientCapabilities(fs = null, terminal = false, …)` | We advertise neither. |
| `SessionUpdate.UsageUpdate`, `Cost`, `PromptResponse.usage` exist (`usage` is `@UnstableApi`) | The protocol *does* carry the external agent's cost. See D-12. |

## Project facts verified in the working tree

| Fact | Location |
|---|---|
| The database probe deletes the file on **any** exception, not only "table missing" | `shared/src/jvmMain/.../database/contract/StaleDbCleaner.kt:36-44` |
| Error responses are built by string interpolation: `"""{"jsonrpc":"2.0",…,"message":"$message"}"""` | `mcp-server/src/main/.../mcp/Main.kt:380-383` |
| `--profile` is the only profile argument; anything but the agent label is silently ignored | `Main.kt:387` (`parseProfileArg`), `Main.kt:155-206` (`bootstrapProfiles`) |
| `TextGenPort.streamChat(message): Flow<String>`; `generate()` is separate | `shared/src/commonMain/.../core/llm/TextGenPort.kt:18-24` |
| `ToolCallLoop.run(...) : Flow<String>` and its KDoc states tool calls are *not* emitted | `shared/src/commonMain/.../core/ai/ToolCallLoop.kt:58-62, 92-116` |
| `Subprocess` has **only** `runQuietly` and `runCapturing` — there is no duplex spawn | `shared/src/jvmMain/.../core/process/Subprocess.kt:54-122` |
| `SubprocessConfinedTest` allows exactly one spawning file (`core/process/Subprocess.kt`) and walks every source set, not just commonMain | `shared/src/jvmTest/.../arch/SubprocessConfinedTest.kt` |
| Profile bindings are **inlined** in `domainModule()` with a comment that a `profileModule()` was tried and produced a child scope | `shared/src/commonMain/.../core/di/Modules.kt:44-52, 66-80` |
| Catalog and Koin list already agree exactly: 43 tool classes, zero difference either way | `McpToolCatalog.kt` vs `AiToolsModule.jvm.kt:222-268` |
| The agreement is already enforced by an existing test | `ToolSurfaceWiringTest` (`desktopApp/src/jvmTest`, `shared/src/androidHostTest`) |
| `config/docs/tool-surface.json` is **generated** and gated | `./gw :shared:exportToolSurface` |
| Navigation entries are already split per platform | `shared/src/jvmMain/.../feature/nav/JvmNavEntries.kt`, `shared/src/androidMain/.../AndroidNavEntries.kt` |
| The unwired-surface ratchet treats a `*Screen` composable with no call site as a **blocking** finding, counted against a floor | `scripts/find-unwired-surfaces.py`, `config/docs/unwired-surfaces-floor.txt` |
| Packaging is jpackage, Deb/Rpm/Msi/Dmg, `includeAllModules = false`, and a `-Dsingularity.version=` system property is already passed | `desktopApp/build.gradle.kts:120-141` |
| The tool server is its own `application` with `mainClass = com.singularity.todo.mcp.MainKt` and the same `singularity.version` | `mcp-server/build.gradle.kts:32-46` |
| JVM toolchain 21; Android `jvmTarget = JVM_11` | `shared/build.gradle.kts:38, 48` |

---

## D-01 — Pin the first-party SDK at exactly `0.34.0`, v1 packages only

**Decision**: depend on `com.agentclientprotocol:acp:0.34.0` and `acp-model:0.34.0` with exact versions; import only `com.agentclientprotocol.{client,common,transport,model}`.

**Rationale**: the SDK is 0.x and moving fast (ten releases in the version list inspected). An exact pin plus a single touch-point file is what makes an upgrade a reviewable diff. v1 is the stable surface; every v2 type this feature would want is `@UnstableApi`.

**Alternatives considered**: the Koog ACP bridge (`ai.koog:agents-features-acp`) — rejected as the *client* dependency because it is the agent-side half and pins `acp:0.13.1`; it stays a Stage-2 (agent role) concern. Using v2 — rejected, draft.

## D-02 — Implement two methods, not a full capability surface

**Decision**: `AcpClientSessionOperations` implements `requestPermissions` and `notify` only. `fsReadTextFile`, `fsWriteTextFile`, all five `terminal*` methods, `createElicitation` and `completeElicitation` are **not** overridden; `ClientCapabilities(fs = null, terminal = false)` is sent.

**Rationale**: the brief's §7.5 assumed every method of the operation interfaces would need an explicit "not supported" body. The sources show the opposite — those interfaces have default bodies that throw `NotImplementedError` with a message naming the capability that was advertised. Since we advertise none of them, the SDK's own refusal is the correct semantics, and writing eight overrides would duplicate it.

**Consequence**: the FR-034 guarantee ("refuse file-system and terminal access") is satisfied by *not advertising*, and the throw is the SDK's. The test asserts we advertise neither capability and that a hypothetical call surfaces the SDK error rather than succeeding.

**Alternatives considered**: overriding every method with a refusal body — rejected as redundant code that would need updating when the SDK adds methods.

## D-03 — stdio for the tool server reference

**Decision**: hand the agent `McpServer.Stdio(name, command, args, env)`.

**Rationale**: the SDK's own KDoc says *"All Agents MUST support this transport"*, while `Http` and `Sse` are conditional on `agentCapabilities.mcpCapabilities.{http,sse}`, which default to `false`. Every ACP client (Zed, JetBrains, `gemini --acp`) uses this form. An HTTP tool server would also need in-process serving, which is explicitly out of scope.

**Consequences**: `env` is a non-nullable `List<EnvVariable>` and is part of the command contract, not an afterthought — the launcher command and its environment are what the app must compute (§D-13). An empty `mcpServers` list is the representation of "no tool access".

## D-04 — The app spawns; the SDK only frames

**Decision**: spawn the agent through our own process port, then adapt its streams into `StdioTransport`'s Flow-based constructor.

**Rationale**: `StdioTransport` takes `Flow<String>` and `suspend (String) -> Unit` and never touches `ProcessBuilder` — which keeps process creation inside the one file `SubprocessConfinedTest` allows, and keeps the SIGPIPE lesson in one place. The blocking `Source`/`Sink` constructor is deprecated for pinning a dispatcher thread per read; we use the Flow one.

**Alternatives considered**: letting the SDK spawn (it cannot); reading the deprecated `Source`/`Sink` path for simplicity (rejected: deprecated, and it forces a close-handler).

## D-05 — Turn completion is `PromptResponseEvent`, never the end of the flow

**Decision**: `AgentEvent.TurnCompleted` is emitted when a `PromptResponseEvent` arrives. Flow completion *without* that event is a **failure**, not a success.

**Rationale**: this is the concrete mechanism behind spec FR-022 and FR-023, and it is why those two requirements are separate. A flow that ends because the process died, the transport errored, or the collector was cancelled produces no `PromptResponseEvent` — treating "the stream stopped" as success is precisely the failure mode the review round 2 rejected.

**Consequence**: the terminal-outcome guarantee is enforced in the collector's `finally`, keyed on whether the completion event was seen. `StopReason` decides *which* successful outcome, per D-06.

**Alternatives considered**: emitting `TurnCompleted` on flow completion (rejected: makes a crash indistinguishable from a finished answer).

## D-06 — Stop-reason mapping

| ACP `StopReason` | App outcome | Reason |
|---|---|---|
| `END_TURN` | `Completed` | The agent finished the turn normally. |
| `MAX_TOKENS` | `Completed` (with a truncation note) | The agent answered; it was cut short by a limit. The answer exists, so reporting failure would be a lie. |
| `MAX_TURN_REQUESTS` | `Completed` (with a truncation note) | Same shape: the agent stopped, having answered. |
| `REFUSAL` | `Failed` | The agent declined the task. The user asked for something and did not get it. |
| `CANCELLED` | `Cancelled` | The turn was cancelled. |

**Rationale**: spec FR-022 fixes three outcomes and FR-023 forbids dressing a failure up as success. The two limit cases are successes with a caveat; refusal is a failure to deliver; cancellation is its own outcome.

**Consequence**: the app's turn state carries an optional truncation note so "completed but truncated" is visible without becoming a fourth outcome.

## D-07 — Session-update mapping into the four-event vocabulary

| ACP session update | App event |
|---|---|
| `AgentMessageChunk` | `TextDelta` |
| `ToolCall` | `ToolCallUpdated` (status mapped per D-08) |
| `ToolCallUpdate` | `ToolCallUpdated` |
| `PlanUpdate` | `PlanUpdated` |
| `UserMessageChunk` | *ignored* — it echoes our own message back |
| `AgentThoughtChunk` | *ignored* — private reasoning is not in the vocabulary and not something the user asked to see |
| `PlanUpdateV2`, `PlanRemoved`, `AvailableCommandsUpdate`, `CurrentModeUpdate`, `ConfigOptionUpdate`, `SessionInfoUpdate`, `UsageUpdate`, `Unstructured`, `UnknownSessionUpdate` | *ignored* — outside this release's vocabulary |

**Rationale**: the vocabulary is deliberately small (FR-017). An adapter that maps the three productive variants and drops the rest is smaller, testable by enumeration, and cannot leak an unstable field into the UI.

**Consequence**: because `PlanUpdateV2` is dropped, we advertise no `planCapabilities`, so an agent that supports v1 plans sends `PlanUpdate`. Ignoring a variant is a decision that must be *written down* — otherwise a future reader assumes it was overlooked.

## D-08 — Tool-call status mapping

| ACP `ToolCallStatus` | App `ToolCallStatus` |
|---|---|
| `PENDING` | `RUNNING` |
| `IN_PROGRESS` | `RUNNING` |
| `COMPLETED` | `COMPLETED` |
| `FAILED` | `FAILED` |

**Rationale**: the app's three statuses answer the only question a reader has — is it still going, did it work, did it break. Protocol granularity is not user value.

**Consequence**: `ToolCallUpdate` events that arrive without a prior `ToolCall` must not create a duplicate card; the adapter keeps one card per `toolCallId` and updates it in place.

## D-09 — Process-tree ownership is the primary close mechanism

**Decision**: `ProcessTree` owns the spawned process and, on close, collects `ProcessHandle.descendants()` deepest-first, destroys them, waits a grace period, then force-kills survivors. ACP's `session.close()` is attempted first but never relied upon.

**Rationale**: `close()` is `@UnstableApi` and conditional on a capability the agent may not advertise. Relying on it would leave a tool-server grandchild running for agents that do not implement it. The review round 2 identified exactly this: agent termination is not MCP-process termination.

**Alternatives considered**: `session.close()` alone (rejected — unstable, conditional); `destroyForcibly()` alone (rejected — no chance for the agent to clean up); tracking only the root pid (rejected — the tool server is a grandchild and survives).

## D-10 — Version negotiation is a first-class connect outcome

**Decision**: `initialize` failure, including `UnsupportedProtocolVersionException`, produces a distinct diagnosable UI state.

**Rationale**: the SDK closes the connection before throwing, so the failure is not transient — a retry against the same agent fails identically. The message must name the offered version.

## D-11 — Agent-side authentication is a connect state, not a blocker

**Decision**: if `AgentInfo.authMethods` is non-empty and the session needs authentication, the UI says the external agent requires its own sign-in; the app does not hold or ask for the agent's credentials.

**Rationale**: consistent with the spec assumption that the external agent brings its own authorization. Recording it as an explicit state prevents it from surfacing later as an unexplained connect failure.

## D-12 — Cost data is available but out of scope

**Decision**: do not surface external-agent cost. Do record that the protocol carries it.

**Rationale**: the brief listed "расходы внешнего агента не видны" as an accepted limitation (P6). The SDK shows the data exists (`SessionUpdate.UsageUpdate`, `Cost`, `PromptResponse.usage`), so the limitation is a scope decision, not a protocol constraint — which is exactly the kind of thing that gets re-litigated in six months without a note.

## D-13 — The tool-server delivery mechanism is chosen by a probe, and the probe is a repository artifact

**Decision**: before any refactoring, add a reproducible script that builds a packaged desktop distribution and reports: what `java.class.path` contains at runtime, whether the tool server's main class starts from it, and where the application jar lives. Then implement **exactly one** mechanism based on the result.

**Rationale**: the brief's hypothesis ("`java.class.path` holds the whole runtime") is unverified. `nativeDistributions` sets `includeAllModules = false`, so the runtime is trimmed, and jpackage launchers historically put the application on the classpath but the runtime modules in the runtime image — which would mean the classpath cannot start a *separate* main class that needs classes outside the trimmed set. Guessing wrong here means either a broken launch in packaged builds or an unnecessary refactor of the tool-server module.

**Decision on the JRE**: the launch command uses the JRE already shipped with the desktop application (`java.home`), never a system `java`. Rationale: packaged users have no system Java, and a version mismatch with the shipped runtime is a class of bug that only appears after packaging.

**Decision on location**: the tool server is found relative to the installation root, with a documented fallback to the directory beside the executable.

**Decision on version**: the app compares its expected `singularity.version` against the value the tool server reports in its handshake; a mismatch is a warning, not a failure. Rationale: the property already flows into both artifacts from the same `desktopAppVersion`.

## D-14 — `profileModule()` must be list-composed, never `includes()`

**Decision**: extract the profile bindings into `fun profileModule(): Module`, and have `domainModule()` add it with `add(profileModule())` inside its `buildList`. The tool server composes its own list in which the fixed-profile module replaces it.

**Rationale**: `Modules.kt` carries a comment stating that an earlier `profileModule()` wrapped its bindings in a child scope and that the bindings were inlined for exactly that reason. The extraction is still the right shape — but it must be added to the list, not `includes()`d, or the original bug returns.

**Consequence**: Koin bindings are lazy, so if the tool server never resolves `DataStore<Preferences>`, the file lock is never taken. That is what makes `--profile-id` safe from a second process, and it must be asserted by a test, not by reasoning.

## D-15 — The write-classification check extends existing machinery

**Decision**: add `writeBehavior` (and `exceptionReason`) to the catalog entry, and extend the **existing** catalog-versus-Koin consistency test rather than writing a new one.

**Rationale**: the catalog and the Koin list already agree exactly (43 classes, verified by diff), and `ToolSurfaceWiringTest` already enforces it in two places. The completeness requirement is therefore an extension of a working check, not new infrastructure.

**Consequence**: because `config/docs/tool-surface.json` is generated and gated, adding the field requires `./gw :shared:exportToolSurface` in the same change — otherwise the gate reports a stale artifact rather than the real problem.

## D-16 — Activity events live in `core/llm`, not in `feature/ai`

**Decision**: the shared event vocabulary is declared in `commonMain` under `core/llm`, beside the port it replaces.

**Rationale**: `core/ai` already depends on `core/llm` in one direction only; declaring the vocabulary in `feature/ai` and using it from the port would invert that. The ACP adapter lives in `jvmMain` and the built-in loop in `commonMain`, and both must speak the same vocabulary.

## D-17 — The activity vocabulary is exactly four kinds

**Decision**: `TextDelta`, `ToolCallUpdated`, `PlanUpdated`, and a successful-completion marker. Transport failure is a separate UI state, not an event.

**Rationale**: the review round 2 rejected a vocabulary that mixed layers. Four kinds is the smallest set that answers "what is it doing right now", and it is enumerable, so the adapter's mapping can be tested by covering all four plus the ignore-list.

**Consequence**: `TextDelta` is an *addition*, never the whole message — a naive concatenation bug is the first thing the tests must catch.