# 3160 — Verify Koog persistence and ACP session resume before implementation

Status: research recorded 2026-11-25 (workspace `gazon1/research-ai-verify-koog-persistence-and-acp-sess`).
Outcome: **Koog's checkpointing does not apply to the built-in chat path; the pinned ACP client can
issue `session/resume`, but no configured profile makes it worth building yet — and with the pinned
SDK the current bridges cannot even complete a handshake. The smallest viable design is a
project-owned transcript table in Room — text only, no live session restore.**

Everything below was checked against the pinned versions in this checkout: Koog `1.3.0`
(`gradle/libs.versions.toml:122`), the Kotlin ACP SDK `0.34.0` (`gradle/libs.versions.toml:133`),
Room `3.0.3` (`gradle/libs.versions.toml:35`). The SDK claims below are read out of the pinned
jars in the Gradle cache, not out of upstream documentation.

## Question

Before adding durable AI conversations: which of the two existing mechanisms — Koog's checkpointing
or ACP's session resume — can actually carry a conversation across process death, and what is the
smallest thing that should be built?

## The two mechanisms, and the two things they are not

They are easy to confuse, so the note keeps them apart:

| | Mechanism | What it restores | What it does **not** restore |
|---|---|---|---|
| Koog | Agent-graph **checkpointing** (`agents-features-snapshot`) | The position of a Koog agent *graph* mid-run, plus the message history of that run | Anything outside a Koog agent graph; the built-in chat never enters one |
| ACP | `session/resume` | A *live* agent-side session, in the agent's own process | The conversation's text, unless the agent replays it; nothing the agent no longer holds |

Neither is "a chat log". A chat log has to be written by this application, in this application's
storage, or it does not exist.

## What the built-in chat is today

The chat screen does **not** run on Koog's agent runtime. The call chain of one user message:

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

Facts that decide the question:

- **`ToolCallLoop` is a hand-written loop, not Koog's graph.** Its whole API is
  `run(initialPrompt, stream)` (`core/ai/ToolCallLoop.kt:111`); the conversation lives in a local
  variable (`:116`) and is thrown away when the turn ends. The class says why
  (`:33-37`): Koog's agent runtime is written against the JVM's reflection and its own event
  pipeline, and this project runs the same code on Android and on the desktop. That decision is
  ADR `docs/decisions/2026-10-22-the-chat-can-call-tools.md`.
- **`AIAgent` is used in exactly one place**, `KoogAgentService.generate()` (`KoogAgentService.kt:131`),
  whose only production caller is the API-key "ping" in AI settings
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

## 1. Koog persistence: **not applicable** to the chat path

Koog 1.3.0 does ship checkpointing, and **it is on this project's classpath** — transitively, which
is easy to miss when reading the dependency declarations:
`ai.koog:koog-agents:1.3.0` declares
`ai.koog:agents-features-snapshot:1.3.0` and `ai.koog:agents-features-memory:1.3.0` as **runtime**
dependencies, so both arrive transitively through `libs.koog.agents`
(`gradle/libs.versions.toml:371-376`; confirmed by `./gw :shared:dependencyInsight --configuration
jvmTestRuntimeClasspath --dependency agents-features-snapshot`, which shows
`koog-agents → koog-agents-jvm → agents-features-snapshot`). Availability is therefore *not* the
reason to reject it. The reason is what the feature can checkpoint:

- Checkpointing is an **agent-pipeline feature**. `Persistence.Feature` implements
  `AIAgentGraphFeature` / `AIAgentPlannerFeature` and installs itself by casting the running
  strategy: `val strategy = ctx.strategy as AIAgentGraphStrategy<*, *>`
  (`agents-features-snapshot: Persistence.kt:130`). The built-in chat's strategy is a
  `FunctionalAIAgent`, so this cast is the wall, not a detail.
- Restoring is an **agent-graph operation**: `runFromCheckpoint(agent: AIAgent<Input, Output>, input,
  checkpoint, …)` (`Persistence.kt:267`) writes the checkpoint into an `AIAgentRunSession`'s
  *graph* context (`Persistence.kt:292-300`). There is no `AIAgent` and no graph session in the
  chat path to restore into.
- The checkpoint payload (`AgentCheckpointData`) does contain `messageHistory: List<Message>`,
  `llmModel`, `tools` and the node `storage` — but that payload is *produced by* the graph feature
  while a Koog graph runs. The chat path never creates one, so there is nothing to read back.

So the two candidate readings of "Koog persistence" both fail for the chat:

- **Graph checkpointing** — not applicable: the chat is not a Koog graph. Adopting it means moving
  the chat onto `AIAgent.run()`, which is exactly the move ADR `2026-10-22` rejected, and it would
  cost the streaming and multiplatform properties the loop was written for.
- **Transcript/history persistence** — `agents-features-memory` does ship (`ChatMemory`,
  `ChatHistoryProvider`, `InMemoryChatHistoryProvider`), but 1.3.0's only provider is in-memory:
  the artifact contains four source files and one provider implementation, whose own KDoc says it is
  "useful for testing, prototyping, and short-lived applications where persistence across process
  restarts is not required". Using it would mean writing a durable provider — a new persistence
  format for something this project can write to Room in one table.

**Verdict: do not adopt.** The tripwire is `KoogCheckpointApplicabilityTest`
(`shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/KoogCheckpointApplicabilityTest.kt`): it
pins the two facts the decision rests on — the checkpoint API is defined in terms of a Koog
`AIAgent`, and the chat loop holds no Koog agent at all.

## 2. ACP resume: the SDK can do it; the profiles and the lifecycle do not yet justify it

### What the pinned client offers

Verified in `acp-jvm-0.34.0-sources.jar` and `acp-model-jvm-0.34.0-sources.jar`:

- `Client.resumeSession(sessionId, sessionParameters, operationsFactory)` —
  `commonMain/com/agentclientprotocol/client/Client.kt:576`, and the operations overload at `:604`.
  Both are `@UnstableApi`.
- The wire method exists in protocol **v1**, not only in the draft v2:
  `AcpMethod.AgentMethods.V1.SessionResume` → `"session/resume"`
  (`commonMain/com/agentclientprotocol/model/Methods.kt:205`). The app speaks v1
  (`SUPPORTED_PROTOCOL_VERSION = 1`, `AcpClient.kt:638`), and `session/load` sits next to it at
  `Methods.kt:152`.
- Capability negotiation: `SessionCapabilities.resume: SessionResumeCapabilities?` — a **marker**,
  presence means supported (`Capabilities.kt:198`). The v1 `session/load` gate is a separate
  boolean, `AgentCapabilities.loadSession` (`Capabilities.kt:266`).
- The SDK's own contract for `resumeSession` says exactly what the app would have to honour:
  *"This method is only available if the agent advertises the `session.resume` capability."*
  (`Client.kt:564`). It is also documented as unstable and not part of the spec yet.

So the client side is not the blocker: **no SDK bump is needed to issue `session/resume`.**

The claim is pinned by `AcpResumeCapabilityTest`
(`shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpResumeCapabilityTest.kt`): it
asserts that the pinned client exposes the resume call, that resume is a *marker* capability
(`sessionCapabilities.resume` is present or absent — no payload to interpret), and that the marker
travels as exactly `{"resume":{}}`. A dependency bump that changes any of those fails the test
instead of quietly invalidating this note.

### What the application does today

- **It never reads the agent's capabilities.** `AgentInfo` is used for the name, the version and one
  comparison — `agentInfo.protocolVersion != SUPPORTED_PROTOCOL_VERSION` (`AcpClient.kt:314-319`).
  `AgentCapabilities.loadSession` and `AgentCapabilities.sessionCapabilities.resume` are unread.
- **It never persists a session id.** The id is read once, at handshake time
  (`AcpClient.kt:334`), wrapped in `AcpSessionEvent.SessionOpened` and written to the log
  (`AcpSessionEvent.kt:26,80-83`). There is no Room row, no file, no settings entry.
- **The session methods it exercises are exactly seven**: `initialize`, `session/new`,
  `session/prompt`, `session/cancel`, `session/close` (best-effort, five-second deadline),
  `session/request_permission`, `session/update`. `session/load`, `session/resume`,
  `session/list`, `session/fork` and `session/delete` are never called.

### What the configured profiles actually support

The registry is hard-coded Kotlin, not a config file
(`feature/ai/acp/AcpAgentProfile.kt:74-114`):

| id | command | source checked | advertises |
|---|---|---|---|
| `gemini-cli` (default) | `gemini --experimental-acp` | `@google/gemini-cli` 0.71.1 | `loadSession: true`, **no `sessionCapabilities`** → no resume |
| `claude-code` | `npx @agentclientprotocol/claude-agent-acp` | 0.112.0 | `loadSession: true`, `sessionCapabilities.resume: {}` |
| `codex` | `npx @agentclientprotocol/codex-acp` | 2.12.0 | `loadSession: true`, `sessionCapabilities.resume: {}` |

- **Gemini CLI** answers `initialize` with `agentCapabilities: { loadSession: true, promptCapabilities,
  mcpCapabilities }` and no `sessionCapabilities`
  (`@google/gemini-cli@0.71.1`, bundle `gemini-7FHLRHNN.js:18745-18760`). Its dispatcher answers
  `session/resume` with *method not found*, because the agent object has no
  `unstable_resumeSession` (`:15742-15748`). So the **default profile cannot resume**.
- **The Claude bridge** advertises `sessionCapabilities: { …, resume: {} }` and registers a handler
  for `session/resume` (`@agentclientprotocol/claude-agent-acp@0.112.0`,
  `dist/v1/acp-agent.js:558-606, 9004`).
- **The Codex bridge** advertises `sessionCapabilities: { resume: {}, … }` and maps
  `session_resume → "session/resume"` (`@agentclientprotocol/codex-acp@2.12.0`,
  `dist/index.js:53273-53285, 5444-5458`).
- A **live handshake against the real Claude bridge** (the repository's own probe,
  `scripts/acp-bridge-probe.sh`, run on 2026-10-25 against 0.95.0) returned
  `"loadSession": true, "sessionCapabilities": {…, "resume": {}}` — the same shape the package
  source shows for 0.112.0.

### The live probes of 2026-11-25: the pinned SDK cannot even decode the bridges' `initialize`

The repository's own probe (`scripts/acp-bridge-probe.sh`) was run against the current Claude and
Codex bridges. Both captured a well-formed `initialize` **response frame** — and both then failed
to complete the handshake against the pinned SDK, with the identical error:

```
failure: Expected JsonPrimitive, but had JsonObject as the serialized body of boolean at path: $.status
```

Root cause, confirmed from the pinned SDK sources: `AgentAuthCapabilities.status` is declared
`val status: Boolean? = null` (`acp-model-jvm-0.34.0-sources.jar`,
`commonMain/com/agentclientprotocol/model/Capabilities.kt:236`, marked `@UnstableApi`), while both
bridges answer with `"auth":{"logout":{},"status":{}}` — an **object** where the SDK expects a
boolean. The frames are in `build/diagnostics/acp-probe-claude.log` (claude-agent-acp 0.112.0) and
`build/diagnostics/acp-probe-codex.log` (codex-acp 2.12.0).

This matters for this issue in two separate ways:

1. **The connection fails before resume is reachable.** With the pinned SDK 0.34.0, the resume
   capability these bridges advertise is currently unusable in practice — not because resume is
   wrong, but because the session cannot even be opened. Any future ACP-resume work starts by
   fixing this shape mismatch (or by moving to an SDK version whose `AgentAuthCapabilities.status`
   accepts the object form).
2. **It confirms the table above is a source-level statement, not a runtime one.** The capability
   advertisement was read from the wire frame, so the *advertisement* is real; what is not real is
   any end-to-end session with these bridges under the pinned SDK.

Three caveats that a reader of the table above must not miss:

1. **The bridges are not version-pinned.** The profiles launch `npx @agentclientprotocol/…`, so the
   package resolves to *latest* at launch time. What a profile supports is a property of the
   installed bridge, not of this repository — the table is true as of the versions checked, and the
   capability must be read at runtime, not assumed.
2. **Capability is not the same as usefulness.** `session/resume` resumes a session the *agent* still
   holds. This application owns the agent's process tree and kills it when the conversation ends
   (`AcpClient.close()`/`terminate()`, `AcpClient.kt:485-601`). A session whose process is gone
   cannot be resumed by anybody, including the SDK.
3. **The pinned SDK cannot open these sessions at all** (see above): `auth.status` is a boolean in
   SDK 0.34.0 and an object in the bridges. Whatever the resume design ends up being, it starts
   after that mismatch is resolved.

### Verdict: **no-go** for building ACP resume now

Not because the SDK lacks it — the pinned client has it, in v1, with a documented capability gate —
but because everything a resume needs is missing and none of it is cheap:

1. the app never reads capabilities, so it cannot know whether resume is on the table;
2. the app never persists a session id, so it has nothing to resume;
3. the app kills the agent's process on close, so the session it would resume is gone with it;
4. and, as the live probe shows, the pinned SDK 0.34.0 cannot decode the bridges' `initialize`
   response at all (`$.status`), so there is no working session to resume in the first place.

The one thing that *is* cheap and worth doing later: read
`agentInfo.capabilities.sessionCapabilities?.resume` right after `initialize`, and surface it in
`AcpAgentInfo` (`AcpAgentSession.kt:55-60`). That is a two-line change with no persistence
attached, and it turns "we do not know" into "we know" — the precondition for every later
decision. It is deliberately **not** in this issue, which asks for no production change.

## 3. Room conventions a conversation transcript must follow

The Room layer is a **separate Gradle module**, `:core-database` (`AGENTS.md` still points at
`shared/…/core/database/AppDatabase.kt` — that path is stale). Current head: `SCHEMA_VERSION = 55`
(`core-database/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt:51`),
exports in `core-database/schemas/com.singularity.todo.core.database.AppDatabase/`.

A new transcript table follows the house style exactly. Paths below are shortened as
`core-database/…/` = `core-database/src/commonMain/kotlin/com/singularity/todo/`
and `core/ids/X.kt` = `core-domain/src/commonMain/kotlin/com/singularity/todo/core/ids/X.kt`.

| Concern | Convention | Source |
|---|---|---|
| Table name | `snake_case`; an AI table already exists as `ai_proposal`, `llm_usage` | `core-database/…/feature/proposals/data/ProposalDao.kt:24-30` |
| Primary key | `@PrimaryKey val id: String`, UUID string, **no** `autoGenerate` | `core-database/…/core/database/Entities.kt:25-90` |
| Id type | `@Serializable @JvmInline value class XId(val value: String)` in `:core-domain`, with `generate()` / `fromString()` | `core/ids/ProposalId.kt`, `core/ids/IdGen.kt` |
| Timestamps | `Long` epoch millis in `created_at` / `updated_at` | `core-database/…/core/database/Entities.kt:25-90` |
| Ownership | `@ColumnInfo("user_id") val userId: String` + `Index("user_id")`; or scope through the parent (the AI precedent, `llm_usage`, scopes by `profile_id`) | `core-database/…/feature/proposals/data/ProposalDao.kt:24-41`, `core-database/…/core/database/Daos.kt:2157` |
| DAO | `@Dao interface`, `suspend` for writes, `Flow` for reads, `@Upsert`, and **every** `UPDATE`/`DELETE` carries a user predicate | `core-database/…/feature/proposals/data/ProposalDao.kt:99-170` |
| Mapper | `fun XEntity.toX()` covering **every** column — `@Upsert` is a full-row replace | `core-database/…/core/database/Mappers.kt`, `EntityMapperCompletenessTest` |
| Registration | `abstract fun xDao()` on `AppDatabase` + `single { get<AppDatabase>().xDao() }` in **both** `PlatformModule.android.kt` and `PlatformModule.jvm.kt` | `core-database/…/core/database/AppDatabase.kt:155-220` |
| Migration | bump to 56, add the entity, `Migration55To56 : AutoMigrationSpec` (empty body — a new table needs no manual SQL), register the `AutoMigration` in `@Database(autoMigrations = …)` | `core-database/…/core/database/AppDatabase.kt:95-150`, `core-database/…/core/database/Migration53To54.kt:17` |
| Export + gate | build `:core-database` so Room writes `56.json`, then `python3 scripts/check-room-schema-integrity.py --write` | `core-database/build.gradle.kts:61-63`, `scripts/check-room-schema-integrity.py:95-100` (the `--write` flag itself is at `:1309`) |
| Migration test | hand-written `@Tag("slow")` test using `DatabaseTestFixture.onDisk()` + `applyExportedSchema(55, connection)`; the round-trip test is generated, never hand-edited | `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/MigrationRoundTripSupport.kt:101` (`schemaFile`), `:120-143` (`applyExportedSchema`) |

Two gates will bite a new table: `ScopedWriteQueryIsolationTest` /
`ScopedReadQueryIsolationTest` reject an unscoped `UPDATE`/`DELETE`, and
`EntityMapperCompletenessTest` rejects a mapper that omits a column.

**Required state for a usable restore.** A restored conversation needs, at minimum: the ordered
turns (`role`, `content`), the turn's tool calls and their outcomes *if* the conversation is to be
replayed to the model rather than merely displayed, and the turn's screen reference *if* screens
are to be restored. The last one is the expensive one: `SurfaceId` is minted per turn
(`chat/ChatViewModel.kt:191`) and the surface tree lives only in `SurfaceController`'s in-memory
`MutableStateFlow` (`feature/genui/surface/SurfaceController.kt:35`). Persisting it means
persisting a rendered UI tree — a second format, a second table, and a schema of its own.

## 4. Lifecycle semantics: what may be restored, and when

| Situation | Transcript (text) | A live model/agent execution | An ACP session |
|---|---|---|---|
| Process death, app restarted | **Yes** — if the transcript is in Room | No — the model call is over; the next message starts a new call | No — the agent's process was killed with the app |
| Turn cancelled by the reader | **Yes** — the partial answer is a row | No | No — `session/cancel` stops the turn; the session survives only while the process does |
| App killed, agent killed | **Yes** | No | No — the id is meaningless without the agent that minted it |
| Agent process died, app alive | **Yes** | No | No — `AgentUnavailable` (`AcpClient.kt:399`), and the id is dead |
| App restarted, agent daemon still alive | **Yes** | No | **Only here** — `session/load` or `session/resume`, if the agent advertises it |

The rule the table states: **transcript restoration and execution resumption are different
features.** The transcript is the application's own data and survives anything. Resuming an
execution is a property of *someone else's* process, and this application does not keep that
process alive — it kills it by design (`ProcessTree`, `AcpClient.kt:485-601`).

A second rule follows from the current chat shape: **restoring a transcript does not restore the
model's memory of it.** Today the model is re-told the last six turns as 200-character prose
(`ChatTurn.kt:114-134`). If the transcript becomes durable, the same lossy block is still what the
model sees — and a durable transcript invites the question "why does the model not remember what I
can see?", whose honest answer is a separate decision (feed the real turns, and pay for the tokens).

## Recommendation: the smallest viable design

**Build one thing: a project-owned conversation transcript in Room, text first, for the built-in
chat.**

1. A `chat_conversation` row and a `chat_message` row (or one table with a conversation id) in
   `:core-database`, following the conventions in §3: `String` UUID primary keys, `created_at`,
   `user_id` scoping, a DAO with `Flow` reads and scoped writes, a mapper, a `SCHEMA_VERSION` bump
   to 56 with an empty `Migration55To56 : AutoMigrationSpec`, the exported `56.json`, and the
   regenerated round-trip test.
2. The chat writes a row per turn as it happens (the ViewModel already has the turn's text and the
   tool activity), and reads the transcript back on open.
3. The model's context stays exactly as it is (`ChatTurn.historyBlock()`), unless a later decision
   says otherwise. **Restoring what the reader saw is the feature; re-teaching the model is a
   second feature.**

**Non-goals, explicitly:**

- Koog checkpointing, `agents-features-snapshot`, `agents-features-memory`, or moving the chat onto
  `AIAgent.run()`.
- ACP `session/resume`, `session/load`, session-id persistence, or capability plumbing — all
  recorded here as a later decision, not built now.
- GenUI surface persistence (the rendered tree), the tool-call log's payload, a second persistence
  engine, an event-sourcing framework, a generic `AgentRuntime`, a custom ACP transport, another
  agent-profile abstraction.
- Any change to `AcpClient`'s "one door" boundary (`docs/decisions/2026-10-25-the-sdk-lives-in-one-file.md`).

**Sequencing, if the durable chat is wanted:**

1. The transcript table and the write path (§3 conventions, as above).
2. The read path: the chat screen opens on the stored transcript.
3. *Then* the question the transcript raises: should the model see the real turns instead of the
   200-character prose block?
4. *Then, separately*: the ACP capability read (`sessionCapabilities?.resume`) — two lines, no
   persistence — and, only if a profile turns out to keep its process alive across restarts, the
   session-id store and the resume call.

## How to reproduce the findings

- Koog checkpointing **is** on the classpath, transitively:
  `./gw :shared:dependencyInsight --configuration jvmTestRuntimeClasspath --dependency agents-features-snapshot`
  — `koog-agents → koog-agents-jvm → agents-features-snapshot` (runtime scope in the POM).
- The checkpoint feature is graph-only:
  `unzip -p ~/.gradle/caches/modules-2/files-2.1/ai.koog/agents-features-snapshot-jvm/1.3.0/*/agents-features-snapshot-jvm-1.3.0-sources.jar commonMain/ai/koog/agents/snapshot/feature/Persistence.kt | sed -n '120,135p'`
  — the `as AIAgentGraphStrategy<*, *>` cast.
- ACP resume exists in v1:
  `unzip -p ~/.gradle/caches/modules-2/files-2.1/com.agentclientprotocol/acp-model-jvm/0.34.0/*/acp-model-jvm-0.34.0-sources.jar commonMain/com/agentclientprotocol/model/Methods.kt | sed -n '200,210p'`
  and `…/acp-jvm/0.34.0/*/acp-jvm-0.34.0-sources.jar commonMain/com/agentclientprotocol/client/Client.kt | sed -n '560,580p'`.
- The bridge capabilities: `npm pack @agentclientprotocol/claude-agent-acp @agentclientprotocol/codex-acp @google/gemini-cli`,
  then read the `initialize` handler in each package's bundle.
- A live handshake against a real bridge: `scripts/acp-bridge-probe.sh npx -y @agentclientprotocol/claude-agent-acp`
  (the probe prints the `initialize` frame, capabilities included; on 2026-11-25 it then failed with
  `$.status` — see above).
- The SDK/bridge shape mismatch:
  `unzip -p ~/.gradle/caches/modules-2/files-2.1/com.agentclientprotocol/acp-model-jvm/0.34.0/*/acp-model-jvm-0.34.0-sources.jar commonMain/com/agentclientprotocol/model/Capabilities.kt | sed -n '233,238p'`
  — `status: Boolean? = null` against the bridges' `"status": {}`.

## What was not verified

- The **live** handshake against the Claude and Codex bridges at the versions checked on
  2026-11-25 was run and **failed**: both bridges answered `initialize` with a frame the pinned
  SDK 0.34.0 cannot decode (`$.status`, boolean vs object). So the capability shapes above are
  confirmed **on the wire**, but no session was ever opened with these bridges under the pinned
  SDK. Frames: `build/diagnostics/acp-probe-claude.log`, `build/diagnostics/acp-probe-codex.log`.
  The 2026-10-25 run against claude-agent-acp 0.95.0 is the one that completed a handshake
  (`~/.cache/pompano-tmp/acp-bridge-probe.7t7HgO.log`).
- Whether a bridge's `session/resume` **works** end to end — that needs a live agent with a live
  session, and it is not what this issue asks for.
- Gemini CLI was checked at 0.71.1 only; the flag the profile uses (`--experimental-acp`) still
  exists there, and the current documentation prefers `--acp`. That is a separate, small follow-up.
- The ACP path is **desktop-only** today (`shared/src/jvmMain/.../feature/ai/acp/`). Any durable
  ACP session story is a desktop story until that changes.
