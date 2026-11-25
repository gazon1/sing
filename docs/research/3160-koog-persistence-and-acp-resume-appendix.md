# 3160 — Investigation appendix: Koog persistence and ACP session resume

Companion to [`3160-koog-persistence-and-acp-resume.md`](3160-koog-persistence-and-acp-resume.md).
The note carries the **decisions**; this file carries the **low-level evidence** they rest on: the
pinned sources that were read, the commands that reproduce the reading, the captured bridge frames,
and the rationale behind each proof test.

Nothing here is a requirement of the note — if the two ever disagree, the note wins and this file is
the one that is wrong.

## The chat execution path

The call chain of one user message in the built-in chat:

```
ChatScreen → ChatViewModel.send()                      chat/ChatViewModel.kt:150
  → ChatViewModel.turn()                               chat/ChatViewModel.kt:179
      history = historyRecords()                       chat/ChatViewModel.kt:236   (in-memory messages)
      → ChatTurn(agent, surface, history).run(prompt)  chat/ChatTurn.kt:41,56
          request = historyBlock() + prompt            chat/ChatTurn.kt:61
          → agent.streamChat(request)                  chat/ChatTurn.kt:72
              → UsageRecordingTextGen.streamChat       chat/UsageRecordingTextGen.kt:69
                  → KoogAgentService.streamChat        KoogAgentService.kt:170
                      → ToolCallLoop.run(prompt)       core/ai/ToolCallLoop.kt:111
                          → PromptExecutorPort.executeStreaming(prompt, model, tools)
```

The facts that decide the question:

- **`ToolCallLoop` is a hand-written loop, not Koog's graph.** Its whole API is
  `run(initialPrompt, stream)` (`core/ai/ToolCallLoop.kt:111`); the conversation lives in a local
  variable (`:116`) and is thrown away when the turn ends. The class says why (`:33-37`): Koog's
  agent runtime is written against the JVM's reflection and its own event pipeline, and this
  project runs the same code on Android and on the desktop. That decision is ADR
  `docs/decisions/2026-10-22-the-chat-can-call-tools.md`.
- **`AIAgent` is used in exactly one place**, `KoogAgentService.generate()`
  (`KoogAgentService.kt:131`), whose only production caller is the API-key "ping" in AI settings
  (`feature/ai/data/AiSettingsStore.kt:164`). The chat never touches it.
- **The conversation the model sees is prose, not messages.** `ChatTurn.historyBlock()`
  (`chat/ChatTurn.kt:114-134`) renders the last `MAX_HISTORY = 6` turns as
  `"user: …\nassistant: …"`, each side clipped to `REPLY_EXCERPT = 200` characters, and a screen
  collapses to `"(showed a screen)"`. The full `Prompt` (with tool calls and tool results) exists
  only inside the loop's local variable.
- **Nothing about a conversation is persisted today.** The UI list
  (`ChatViewModel.State.messages`, `chat/ChatViewModel.kt:57`) is ViewModel-scoped. The tool-call
  log is a file, but it is shape-only by design — no arguments, no results, no text
  (`core/observability/ToolCallLog.kt:31-40`). The usage ledger stores token counts, not content.
- **GenUI surfaces are in-memory.** `SurfaceController` holds them in a `MutableStateFlow`
  (`feature/genui/surface/SurfaceController.kt:35`), and each turn gets a fresh `SurfaceId`
  (`chat/ChatViewModel.kt:191`). A restored conversation therefore has no screen to restore unless
  the surface tree is persisted too.

## How to reproduce the findings

- Koog checkpointing **is** on the classpath, transitively:
  `./gw :shared:dependencyInsight --configuration jvmTestRuntimeClasspath --dependency agents-features-snapshot`
  — `koog-agents → koog-agents-jvm → agents-features-snapshot` (runtime scope in the POM).
- The checkpoint feature installs into graph and planner pipelines (never into a functional agent):
  `unzip -p ~/.gradle/caches/modules-2/files-2.1/ai.koog/agents-features-snapshot-jvm/1.3.0/*/agents-features-snapshot-jvm-1.3.0-sources.jar commonMain/ai/koog/agents/snapshot/feature/Persistence.kt | sed -n '120,135p'`
  — the `as AIAgentGraphStrategy<*, *>` cast; the planner-side cast is at `:177`.
- ACP resume exists in v1:
  `unzip -p ~/.gradle/caches/modules-2/files-2.1/com.agentclientprotocol/acp-model-jvm/0.34.0/*/acp-model-jvm-0.34.0-sources.jar commonMain/com/agentclientprotocol/model/Methods.kt | sed -n '200,210p'`
  and `…/acp-jvm/0.34.0/*/acp-jvm-0.34.0-sources.jar commonMain/com/agentclientprotocol/client/Client.kt | sed -n '560,580p'`.
- The bridge capabilities: `npm pack @agentclientprotocol/claude-agent-acp @agentclientprotocol/codex-acp @google/gemini-cli`,
  then read the `initialize` handler in each package's bundle.
- The bridges' `session/load` implementations:
  `npm pack @agentclientprotocol/claude-agent-acp@0.112.0 @agentclientprotocol/codex-acp@2.12.0`,
  then `grep -n "async loadSession" claude/package/dist/v1/acp-agent.js` (→ `:879`) and
  `grep -n "session/load" codex/package/dist/index.js` (→ `:5444`).
- A live handshake against a real bridge: `scripts/acp-bridge-probe.sh npx -y @agentclientprotocol/claude-agent-acp`
  (the probe prints the `initialize` frame, capabilities included; on 2026-11-25 it then failed with
  `$.status` — see the note).
- The SDK/bridge shape mismatch:
  `unzip -p ~/.gradle/caches/modules-2/files-2.1/com.agentclientprotocol/acp-model-jvm/0.34.0/*/acp-model-jvm-0.34.0-sources.jar commonMain/com/agentclientprotocol/model/Capabilities.kt | sed -n '233,238p'`
  — `status: Boolean? = null` against the bridges' `"status": {}`.

## The pinned sources that were read

### Koog 1.3.0 (`ai.koog:agents-features-snapshot-jvm:1.3.0`)

| Fact | Source |
|---|---|
| `Persistence.Feature` implements **two** feature interfaces — `AIAgentGraphFeature` and `AIAgentPlannerFeature` — so it installs into a graph pipeline and into a planner pipeline, but into no functional agent's pipeline. The graph path casts the running strategy — `val strategy = ctx.strategy as AIAgentGraphStrategy<*, *>` | `commonMain/ai/koog/agents/snapshot/feature/Persistence.kt:130` |
| The planner-side `install(config, pipeline: AIAgentPlannerPipeline)` casts `(ctx.strategy as AIAgentPlannerStrategy<*, *>).planner` | `Persistence.kt:177` |
| Restoring is an agent-graph operation: `runFromCheckpoint(agent: AIAgent<Input, Output>, input, checkpoint, …)` | `Persistence.kt:267` |
| The checkpoint is written into an `AIAgentRunSession`'s *graph* context | `Persistence.kt:290-299` |
| The feature interfaces live in one file, `commonMain/ai/koog/agents/core/feature/AIAgentFeature.kt`: `AIAgentFeature` `:17`, `AIAgentGraphFeature` `:40`, `AIAgentFunctionalFeature` `:54`, `AIAgentPlannerFeature` `:68` | `agents-core-jvm-1.3.0-sources.jar` |
| The chat's counterpart is a *functional* strategy, not a graph one: `FunctionalAIAgent` (`ai.koog.agents.core.agent.FunctionalAIAgent`) | `agents-core-jvm-1.3.0-sources.jar` |
| The checkpoint payload does contain `messageHistory: List<Message>`, `llmModel`, `tools` and the node `storage` — but it is *produced by* the graph feature while a Koog graph runs | `agents-features-snapshot-jvm-1.3.0-sources.jar` (`AgentCheckpointData`) |
| Koog's memory feature ships only an in-memory provider (`ChatMemory`, `ChatHistoryProvider`, `InMemoryChatHistoryProvider`); its own KDoc calls it "useful for testing, prototyping, and short-lived applications where persistence across process restarts is not required" | `agents-features-memory-jvm-1.3.0-sources.jar` (four source files, one provider implementation) |

### ACP SDK 0.34.0 (`com.agentclientprotocol:acp-jvm`, `acp-model-jvm`)

| Fact | Source |
|---|---|
| `Client.resumeSession(sessionId, sessionParameters, operationsFactory)` — `@UnstableApi`; the operations overload is at `:604` | `commonMain/com/agentclientprotocol/client/Client.kt:576` |
| `Client.loadSession(sessionId, sessionParameters, operationsFactory)` — **not** unstable; the operations overload is at `:475` | `Client.kt:445` |
| The SDK's own contract for resume: *"This method is only available if the agent advertises the `session.resume` capability."* | `Client.kt:564` |
| The wire method exists in protocol **v1**: `AcpMethod.AgentMethods.V1.SessionResume` → `"session/resume"`; `session/load` sits next to it | `commonMain/com/agentclientprotocol/model/Methods.kt:205-206`, `:152` |
| Capability negotiation: `SessionCapabilities.resume: SessionResumeCapabilities?` — a **marker**, presence means supported | `commonMain/com/agentclientprotocol/model/Capabilities.kt:198` |
| The v1 `session/load` gate is a separate boolean: `AgentCapabilities.loadSession` | `Capabilities.kt:266` |
| The shape mismatch: `AgentAuthCapabilities.status: Boolean? = null` (`@UnstableApi`) against the bridges' `"auth":{"logout":{},"status":{}}` | `Capabilities.kt:236` |
| The `loadSession` capability is what the protocol ties to restart-persistence: *"Only available if the agent supports the `loadSession` capability"* | `commonMain/com/agentclientprotocol/model/Requests.kt:399-408` |
| The SDK's JSON: `ignoreUnknownKeys = true`, `encodeDefaults = true`, `explicitNulls = false` — so only `auth.status` breaks decoding | `commonMain/com/agentclientprotocol/rpc/Serialization.kt:25-45` |
| The request types differ only in package: v1 `com.agentclientprotocol.model.ResumeSessionRequest`, v2 `com.agentclientprotocol.model.v2.ResumeSessionRequest` | `Methods.kt` / `Requests.kt` |

### The bridge packages (read offline from the npm cache)

| Package | What the source shows | Source |
|---|---|---|
| `@agentclientprotocol/claude-agent-acp@0.112.0` | advertises `sessionCapabilities: { …, resume: {} }` and registers a `session/resume` handler | `dist/v1/acp-agent.js:558-606`, `:9004` |
| " | implements `session/load` — `readResumedSession` → `replaySessionHistory` | `dist/v1/acp-agent.js:879-897` |
| " | has a `resumeSession` entry | `dist/v1/acp-agent.js:871` |
| `@agentclientprotocol/codex-acp@2.12.0` | advertises `sessionCapabilities: { resume: {}, … }` and maps `session_resume → "session/resume"` | `dist/index.js:53273-53285`, `:5444-5458` |
| " | implements `session/load` → `loadSessionAndReplayHistory`; its own comment calls the two the same operation: *"Shared by v1 `session/load` and v2 `session/resume` with `replayFrom: {type: "start"}`: reattach, replay retained history as ordinary `session/update`s, then answer"* | `dist/index.js:54242-54270` |
| `@google/gemini-cli@0.71.1` | answers `initialize` with `agentCapabilities: { loadSession: true, promptCapabilities, mcpCapabilities }` and **no** `sessionCapabilities` | bundle `gemini-7FHLRHNN.js:18745-18760` |
| " | its dispatcher answers `session/resume` with *method not found*, because the agent object has no `unstable_resumeSession` | `gemini-7FHLRHNN.js:15742-15748` |

## The captured bridge frames of 2026-11-25

The frames themselves are carried **verbatim as test fixtures** in
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpResumeCapabilityTest.kt`, so the
raw JSON is readable there; the probe logs are in `build/diagnostics/`.

| Probe | Package / version | What the `initialize` frame carried | What the probe then did |
|---|---|---|---|
| `build/diagnostics/acp-probe-claude.log` | claude-agent-acp 0.112.0 | `agentInfo.version` `0.112.0`, `agentCapabilities.loadSession: true`, `sessionCapabilities.resume: {}`, `auth: {"logout":{},"status":{}}` | failed the handshake with `Expected JsonPrimitive, but had JsonObject as the serialized body of boolean at path: $.status` |
| `build/diagnostics/acp-probe-codex.log` | codex-acp 2.12.0 | the same capability shape, `auth: {"logout":{},"status":{}}` | the identical failure |
| `build/diagnostics/acp-probe-gemini.log` | `@google/gemini-cli@0.71.1` | `agentInfo: {"name":"gemini-cli","title":"Gemini CLI","version":"0.71.1"}`, `agentCapabilities: {"loadSession":true,"promptCapabilities":{…},"mcpCapabilities":{…}}` — **no `auth` block, no `sessionCapabilities`** | the `initialize` frame **decoded**; the probe then failed at `session/new` with `Gemini API key is missing or not configured.` |

The Gemini failure is this host's configuration, not a protocol or SDK defect — and it is why no
session was opened against any of the three agents.

An earlier run (2026-10-25, claude-agent-acp 0.95.0) completed a handshake and returned
`"loadSession": true, "sessionCapabilities": {…, "resume": {}}` — the same shape the package source
shows for 0.112.0 (`~/.cache/pompano-tmp/acp-bridge-probe.7t7HgO.log`).

## Why the wire cannot tell v1 from v2 resume

The v1 and v2 resume payloads are **wire-identical**, so no wire-level test can tell the versions
apart — the only observable difference is which request type the method object is bound to.

- v1 sends `{sessionId, cwd, mcpServers, additionalDirectories?, _meta?}`.
- v2 sends `{sessionId, cwd, additionalDirectories, mcpServers, replayFrom?, _meta?}` — but v2's
  `additionalDirectories` is `@EncodeDefault(NEVER)` and its `replayFrom` is null, and the SDK's JSON
  omits nulls (`ACPJson`: `encodeDefaults = true`, `explicitNulls = false`).

Both therefore encode to `{sessionId, cwd, mcpServers}`. This is why the test pins the version on the
request builder's return type and reads the wire separately.

## The proof tests, and what each one is for

Two `@Tag("fast")` classes, both sitting on the production implementation rather than on a copy of
it.

### `KoogCheckpointApplicabilityTest`

`shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/KoogCheckpointApplicabilityTest.kt`

- **Contract assertions** — the feature's interfaces, the pipeline it installs into. These are what
  the applicability conclusion actually rests on: a Koog release could keep the API shape and change
  the runtime behaviour, and only the contract assertions would catch that.
- **A compatibility tripwire** — the API shape (`runFromCheckpoint` takes an `AIAgent`) says the
  dependency is still there and still shaped the same way, *not* that the feature is applicable.
- **A production-path assertion** — `KoogAgentService.streamChat` drives the app-owned `ToolCallLoop`
  (read through the repository's own `SourceScan` helper, not a second Kotlin parser).

### `AcpResumeCapabilityTest`

`shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpResumeCapabilityTest.kt`

- The pinned client exposes the resume call, and the call travels as **v1** (see above).
- The version **the application** speaks is pinned as well: the tripwire reads the declaration of
  `SUPPORTED_PROTOCOL_VERSION` in `AcpClient.kt` and checks that the handshake compares against it.
  The behavioural proof sits with the production code it tests — `AcpClientProcessTest` drives the
  real client against a fake agent, which is accepted when it answers with version 1 and refused
  when it answers with another one.
- Resume is a *marker* capability — present or absent, no payload to interpret — and the marker
  travels as exactly `{"resume":{}}`.
- The pinned client runs against a **fake transport**, and the outgoing frames are read back: one
  for `session/resume`, one for the restart-capable `session/load`. The claims are therefore checked
  on the wire, not only in the library's own method objects.
- The **inbound** path is pinned too: representative v1 `initialize` responses decode, with
  `sessionCapabilities.resume` read back as a present-or-absent marker and the separate boolean
  `loadSession`.
- The **captured** bridge frames are fixtures, so the practical no-go rests on captured wire
  evidence: the pinned SDK cannot decode the two bridge frames, it *does* decode the default Gemini
  CLI's frame, and the advertised capabilities are the ones the note's profile table lists.
- A final test pins the profiles the registry actually holds to the packages those frames came
  from, so a changed executable cannot leave the table stale while the suite stays green.

A dependency bump that changes any of those fails the tests instead of quietly invalidating the
note.
