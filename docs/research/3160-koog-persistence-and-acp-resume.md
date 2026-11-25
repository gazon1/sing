# 3160 — Verify Koog persistence and ACP session resume before implementation

Status: research recorded 2026-11-25 (workspace `gazon1/research-ai-verify-koog-persistence-and-acp-sess`).
Outcome: **Koog's checkpointing does not apply to the built-in chat path; the pinned ACP client can
issue `session/resume` and `session/load`, but no configured profile makes it worth building yet —
and with the pinned SDK the current bridges cannot even complete a handshake. The smallest viable
design is a project-owned transcript table in Room — text only, no live session restore.**

Everything below was checked against the pinned versions in this checkout: Koog `1.3.0`
(`gradle/libs.versions.toml:122`), the Kotlin ACP SDK `0.34.0` (`gradle/libs.versions.toml:133`),
Room `3.0.3` (`gradle/libs.versions.toml:35`). The SDK claims below are read out of the pinned
jars in the Gradle cache, not out of upstream documentation.

The low-level evidence — the exact sources read, the commands that reproduce the reading, the
captured bridge frames and the rationale behind each proof test — is in the companion
[investigation appendix](3160-koog-persistence-and-acp-resume-appendix.md). This note carries only
the decisions and the evidence they rest on.

## The answer in one screen

| Question | Answer | Where |
|---|---|---|
| Can Koog checkpointing carry a built-in chat across process death? | **No.** The feature is graph-only, and the chat never enters a graph | §1 |
| Can the pinned ACP client issue `session/resume`? | **Yes** — protocol **v1**, gated on a marker capability | §2 |
| Can it issue `session/load` (the restart-capable call)? | **Yes** — gated on a separate boolean capability | §2 |
| Should ACP session restore be built now? | **No** — five independent reasons, and the pinned SDK cannot even decode the Claude and Codex `initialize` frames | §2 |
| What should be built? | One project-owned transcript table in Room, text first | [Recommendation](#recommendation-the-smallest-viable-design) |
| What is explicitly out of scope? | Koog checkpointing, moving the chat onto `AIAgent`, ACP session plumbing, GenUI surfaces, tool-call payloads, a new persistence engine | [Recommendation](#recommendation-the-smallest-viable-design) |

The rest of the note is the evidence for those answers.

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
| ACP | `session/load` | A session the **agent itself** stored, reloaded into a freshly connected agent process and replayed as `session/update`s | Whatever the agent never stored — and this application's own transcript still does not exist |

Neither is "a chat log". A chat log has to be written by this application, in this application's
storage, or it does not exist. And the two ACP calls are not two names for one thing — §2 keeps
them apart, because they need different processes to be alive.

## What the built-in chat is today

The chat screen does **not** run on Koog's agent runtime. One user message travels
`ChatViewModel.send()` → `ChatTurn.run()` → `TextGenPort.streamChat()` →
`KoogAgentService.streamChat` → `ToolCallLoop.run` (`core/ai/ToolCallLoop.kt:111`) →
`PromptExecutorPort.executeStreaming` — a **hand-written loop**, not Koog's graph. The full call
chain and the facts that decide the question are in the
[appendix](3160-koog-persistence-and-acp-resume-appendix.md#the-chat-execution-path):

- `ToolCallLoop` keeps the conversation in a local variable and throws it away when the turn ends;
  the class says why (ADR `docs/decisions/2026-10-22-the-chat-can-call-tools.md`).
- `AIAgent` is used in exactly one place — `KoogAgentService.generate()`, whose only production
  caller is the API-key "ping" in AI settings. The chat never touches it.
- The conversation the model sees is **prose, not messages**: the last six turns, each side clipped
  to 200 characters. The full `Prompt` (with tool calls and tool results) exists only inside the
  loop's local variable.
- **Nothing about a conversation is persisted today**: the UI list is ViewModel-scoped, the
  tool-call log is shape-only by design, and GenUI surfaces live in memory — so a restored
  conversation has no screen to restore unless the surface tree is persisted too.

## 1. Koog persistence: **not applicable** to the chat path

Koog 1.3.0 does ship checkpointing, and **it is on this project's classpath** — transitively:
`ai.koog:koog-agents:1.3.0` declares `ai.koog:agents-features-snapshot:1.3.0` and
`ai.koog:agents-features-memory:1.3.0` as **runtime** dependencies
(`gradle/libs.versions.toml:371-376`). Availability is therefore *not* the reason to reject it. The
reason is what the feature can checkpoint:

- Checkpointing is an **agent-pipeline feature**. `Persistence.Feature` implements
  `AIAgentGraphFeature` / `AIAgentPlannerFeature` and installs itself by casting the running
  strategy: `val strategy = ctx.strategy as AIAgentGraphStrategy<*, *>`
  (`agents-features-snapshot: Persistence.kt:130`). The cast only holds inside a Koog *graph* run.
  The built-in chat never enters a Koog agent at all — `KoogAgentService.streamChat` drives the
  app-owned `ToolCallLoop` — so there is no strategy to cast and nothing for the feature to install
  into. (The one place the app does run a Koog agent, `KoogAgentService.generate()` →
  `createAgent(...)` → `agent.run(prompt)`, builds a `FunctionalAIAgent`, whose strategy is
  functional rather than `AIAgentGraphStrategy`.)
- Restoring is an **agent-graph operation**: `runFromCheckpoint(agent: AIAgent<Input, Output>, input,
  checkpoint, …)` (`Persistence.kt:267`) writes the checkpoint into an `AIAgentRunSession`'s
  *graph* context (`Persistence.kt:290-299`). There is no `AIAgent` and no graph session in the
  chat path to restore into.

So the two candidate readings of "Koog persistence" both fail for the chat:

- **Graph checkpointing** — not applicable: the chat is not a Koog graph. Adopting it means moving
  the chat onto `AIAgent.run()`, which is exactly the move ADR `2026-10-22` rejected, and it would
  cost the streaming and multiplatform properties the loop was written for.
- **Transcript/history persistence** — `agents-features-memory` does ship (`ChatMemory`,
  `ChatHistoryProvider`, `InMemoryChatHistoryProvider`), but 1.3.0's only provider is in-memory, and
  its own KDoc says it is "useful for testing, prototyping, and short-lived applications where
  persistence across process restarts is not required". Using it would mean writing a durable
  provider — a new persistence format for something this project can write to Room in one table.

**Verdict: do not adopt.** The tripwire is `KoogCheckpointApplicabilityTest`
(`shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/KoogCheckpointApplicabilityTest.kt`): it
pins the facts the decision rests on — the feature itself installs into a **graph** pipeline only
(`AIAgentGraphFeature`, and *not* `AIAgentFunctionalFeature`), and the chat holds no Koog agent at
all: neither in the loop, nor in the project-owned port it turns through (`TextGenPort`, `ChatTurn`),
nor in the streaming implementation that actually runs the chat (`KoogAgentService.streamChat`).

The applicability conclusion rests on those *contract* assertions — the feature's interfaces, the
pipeline it installs into, and the production path the chat actually takes. The API-shape assertion
(`runFromCheckpoint` takes an `AIAgent`) is a **compatibility tripwire**: it says the dependency is
still there and still shaped the same way, not that the feature is applicable. A Koog release could
keep the signature and change the runtime behaviour; the contract assertions are what would catch
that. The full inventory of Koog sources read is in the
[appendix](3160-koog-persistence-and-acp-resume-appendix.md#the-pinned-sources-that-were-read).

## 2. ACP session restore: the SDK can do it; the profiles and the lifecycle do not yet justify it

### What the pinned client offers

Verified in `acp-jvm-0.34.0-sources.jar` and `acp-model-jvm-0.34.0-sources.jar` (the full
inventory of sources read is in the
[appendix](3160-koog-persistence-and-acp-resume-appendix.md#the-pinned-sources-that-were-read)):

- `Client.resumeSession(sessionId, sessionParameters, operationsFactory)` —
  `commonMain/com/agentclientprotocol/client/Client.kt:576`, and the operations overload at `:604`.
  Both are `@UnstableApi`. The SDK's own contract for it says what the app would have to honour:
  *"This method is only available if the agent advertises the `session.resume` capability."*
  (`Client.kt:564`), and resume is a **marker** capability —
  `SessionCapabilities.resume: SessionResumeCapabilities?`, presence means supported
  (`Capabilities.kt:198`).
- `Client.loadSession(sessionId, sessionParameters, operationsFactory)` — the same file, `:445`,
  and the operations overload at `:475`. It is **not** marked unstable, and it is gated by the
  separate boolean `AgentCapabilities.loadSession` (`Capabilities.kt:266`), not by
  `sessionCapabilities.resume`: *"Only available if the agent supports the `loadSession`
  capability"* (`commonMain/com/agentclientprotocol/model/Requests.kt:399-408`). It is the call the
  protocol documents as the persistence-across-restarts path, and the one §4 keeps apart from
  `session/resume`.
- The wire method exists in protocol **v1**, not only in the draft v2:
  `AcpMethod.AgentMethods.V1.SessionResume` → `"session/resume"`
  (`commonMain/com/agentclientprotocol/model/Methods.kt:205-206`). The app speaks v1
  (`SUPPORTED_PROTOCOL_VERSION = 1`, `AcpClient.kt:638`), and `session/load` sits next to it at
  `Methods.kt:152`.

So the client side is not the blocker: **no SDK bump is needed to issue `session/resume`.**

The claim is pinned by `AcpResumeCapabilityTest`
(`shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpResumeCapabilityTest.kt`): the
pinned client exposes the resume call and it travels as **v1** (v1 and v2 resume share the wire name
`session/resume` and are told apart by their request type —
`com.agentclientprotocol.model.ResumeSessionRequest` against
`com.agentclientprotocol.model.v2.ResumeSessionRequest`); the client's own request builder produces
the v1 type; resume is a *marker* capability (present or absent — no payload to interpret) whose
wire form is exactly `{"resume":{}}`; and a fake transport captures the outgoing frames, one for
`session/resume` and one for the restart-capable `session/load`. A dependency bump that changes any
of that fails the test instead of quietly invalidating this note.

The same class carries the **captured** frames the configured agents actually sent on 2026-11-25
and pins the facts the practical no-go rests on: the pinned SDK cannot decode the two bridge frames
(`auth.status` is an object where the SDK expects `Boolean?`), it *does* decode the default Gemini
CLI's frame, and the capabilities the agents advertise are the ones the profile table above lists.
The frames are an observation of a specific package version — the agents are launched through
unpinned packages, so capabilities still have to be negotiated at runtime — but the *SDK side*,
which this repository does pin, is protected. The frames themselves, and what each probe did next,
are in the [appendix](3160-koog-persistence-and-acp-resume-appendix.md#the-captured-bridge-frames-of-2026-11-25).

**The version cannot be read off the wire.** The v1 and v2 resume payloads are wire-identical, so
no wire-level test can tell the versions apart — the only observable difference is which request
type the method object is bound to, which is why the test pins the version there and reads the wire
separately. Why the two encodings coincide:
[appendix](3160-koog-persistence-and-acp-resume-appendix.md#why-the-wire-cannot-tell-v1-from-v2-resume).

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

Two facts the table does not carry: Gemini's dispatcher answers `session/resume` with *method not
found*, because the agent object has no `unstable_resumeSession` — so the **default profile cannot
resume**; and a **live handshake against the real Claude bridge** (the repository's own probe,
`scripts/acp-bridge-probe.sh`, run on 2026-10-25 against 0.95.0) returned
`"loadSession": true, "sessionCapabilities": {…, "resume": {}}` — the same shape the package source
shows for 0.112.0.

Source line references for the three packages:
[appendix](3160-koog-persistence-and-acp-resume-appendix.md#the-bridge-packages-read-offline-from-the-npm-cache).

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

Three caveats that a reader of the table above must not miss:

1. **The bridges are not version-pinned.** The profiles launch `npx @agentclientprotocol/…`, so the
   package resolves to *latest* at launch time. What a profile supports is a property of the
   installed bridge, not of this repository — the table is true as of the versions checked, and the
   capability must be read at runtime, not assumed.
2. **Capability is not the same as usefulness — and `resume` and `load` are not the same thing.**
   The protocol has two ways to pick a conversation back up, and they differ in *where the
   transcript comes from*:

   - **`session/resume`** reconnects to an **existing** session *without replaying* the history.
     That is all the protocol promises about it: the documentation describes it as "reconnect to
     an existing session **without replaying** the conversation history"
     (`https://agentclientprotocol.com/protocol/v1/session-setup.md`, "Resuming sessions"). The
     protocol does **not** state which process must still be alive — whether a session outlives
     the agent process that created it is the agent's own business, and the note did not verify it
     for the configured bridges (see "What was not verified"). What *is* verified here is this
     application's transport: it never connects to an agent that is already running. Every profile
     is *launched* as a child process (`SubprocessAcpAgentLauncher`, `AcpAgentLauncher.kt:78-104`)
     and the protocol travels over that child's standard input and output (`Subprocess.spawnDuplex`,
     `core/process/Subprocess.kt:295-309`), so a restarted app launches a *new* child and has no
     way to reach the old one — an agent orphaned by a killed app cannot be reached either, even
     if it survives the kill, because the pipes died with the app that held them. An orderly close
     ends the tree outright as well (`AcpClient.close()`/`terminate()` → `ProcessTree`,
     `AcpClient.kt:485-601`). **So with this application's transport, `session/resume` cannot carry
     a conversation across an app restart** — not because the protocol forbids it, but because
     there is no surviving agent for this app to talk to.
   - **`session/load`** is the protocol's documented "persistence across restarts" path: the
     **agent** stores the transcript, and a *newly connected* agent process reloads it by id and
     replays the history as `session/update`s before it answers. The protocol documentation says
     the `loadSession` capability "allow[s] Clients to resume previous conversations with history
     replay. This feature enables persistence across restarts"
     (`https://agentclientprotocol.com/protocol/v1/session-setup.md`, "Loading sessions";
     `sessionCapabilities.resume` is the *other* thing — "reconnect to an existing session
     **without replaying** the conversation history"). All three configured profiles advertise
     `loadSession: true`, and **both bridges implement it**: claude-agent-acp 0.112.0 reads the
     stored transcript and replays it, and codex-acp 2.12.0 does the same — its own comment calls
     the two the same operation: reattach, replay retained history as ordinary `session/update`s,
     then answer (sources in the
     [appendix](3160-koog-persistence-and-acp-resume-appendix.md#the-bridge-packages-read-offline-from-the-npm-cache)).
     **So the restart case is not ruled out by the protocol, and it is not ruled out by the bridges
     either** — it is ruled out by *this application*, whose transport cannot reach an agent it did
     not spawn, and which keeps no session id and does not read the capability that promises it.
3. **The pinned SDK cannot open the two bridges' sessions at all** (see above): `auth.status` is a
   boolean in SDK 0.34.0 and an object in the Claude and Codex bridges. Whatever the session-restore
   design ends up being, it starts after that mismatch is resolved. The default Gemini profile is
   the odd one out — its frame decodes — and it advertises no resume anyway.

### The default profile is a different case: Gemini decodes, and then fails for another reason

The same probe was run against the **default** profile on 2026-11-25, launched as the registry
launches it. Its `initialize` result is carried verbatim as a test fixture, so the frame is
readable in `AcpResumeCapabilityTest` rather than duplicated here. Four things follow, and they
must not be merged with the bridges' case:

1. **The frame carries no `auth` block at all**, so the pinned SDK 0.34.0 **decodes it** — the
   `$.status` mismatch above is a property of the two bridges, not of the protocol version and not
   of the SDK.
2. **It confirms the table's Gemini row on the wire**: `loadSession: true` and **no
   `sessionCapabilities`**, so no resume — now an observation, not only a reading of the bundle.
3. **The handshake still failed**, but later and for an unrelated reason: the client got past
   `initialize` and the probe's `session/new` was refused with
   `Gemini API key is missing or not configured.` That is this host's configuration, not a protocol
   or SDK defect, and it is why no session was opened against any of the three agents.
4. **So the connection fails before resume is reachable.** With the pinned SDK 0.34.0, the resume
   capability the two bridges advertise is currently unusable in practice — not because resume is
   wrong, but because the session cannot even be opened. Any future ACP-resume work with them
   starts by fixing this shape mismatch (or by moving to an SDK version whose
   `AgentAuthCapabilities.status` accepts the object form). The default profile is not in this case.

One caveat the table still carries: the capability advertisement was read from the wire frame, so
the *advertisement* is real; what is not real is any end-to-end session with these bridges under
the pinned SDK.

### Verdict: **no-go** for building ACP session restore now

Not because the SDK lacks it — the pinned client has both calls, in v1, with documented capability
gates — but because everything a restore needs is missing and none of it is cheap:

1. the app never reads capabilities, so it cannot know whether resume — or `session/load` — is on
   the table;
2. the app never persists a session id, so it has nothing to resume and nothing to load;
3. `session/resume`, the call the pinned SDK exposes, reconnects to an existing session without
   replaying it — and this application launches its agent as a child it speaks to over pipes, so
   after a restart it has a *new* child and no way to reach the old one. Whether the configured
   bridges would themselves accept a resume after *their own* process restarted is unverified
   here; either way, this application's transport gives it no session to resume;
4. `session/load`, the call that *can* cross a restart, replays the history **from the agent**,
   which would put two transcripts in the picture — the agent's and this application's own — and
   the recommendation below is that this application's own is the one that counts;
5. and, as the live probe shows, the pinned SDK 0.34.0 cannot decode the Claude and Codex
   bridges' `initialize` response at all (`$.status`), so with those two there is no working
   session to resume or to load in the first place. (The default Gemini profile decodes — but it
   advertises no resume, and it is the profile with no session id and no capability read.)

The one thing that *is* cheap and worth doing later: read
`agentInfo.capabilities.sessionCapabilities?.resume` and `agentInfo.capabilities.loadSession` right
after `initialize`, and surface them in `AcpAgentInfo` (`AcpAgentSession.kt:55-60`). That is a
two-line change with no persistence attached, and it turns "we do not know" into "we know" — the
precondition for every later decision. It is deliberately **not** in this issue, which asks for no
production change.

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

| Situation | Transcript (text) | A live model/agent execution | ACP `session/resume` | ACP `session/load` |
|---|---|---|---|---|
| Process death, app restarted | **Yes** — if the transcript is in Room | No — the model call is over; the next message starts a new call | No — the old child died with the app, and this app cannot re-attach to a process it did not spawn | In principle **yes** — a new child reloads the agent's stored transcript by id and replays it — but the app keeps no id to ask with |
| Turn cancelled by the reader | **Yes** — the partial answer is a row | No | No — `session/cancel` stops the turn; the session survives only while the process does | In principle yes — the agent still holds the session; the app still has no id |
| App killed, agent orphaned | **Yes** | No | No — the pipes died with the app, so the orphan cannot be reached | The orphan's stored transcript survives on disk, and a *new* child could load it — if the app kept the id |
| Agent process died, app alive | **Yes** | No | No — `AgentUnavailable` (`AcpClient.kt:399`), and the id is dead | The stored transcript may still be there; the app has no id, and the agent may not have finished writing it |
| A *separately* running agent, reachable over a socket | **Yes** | No | **Yes** — if the agent advertises `sessionCapabilities.resume` | **Yes** — if the agent advertises `loadSession` |

Three rules follow from the table:

1. **Transcript restoration and execution resumption are different features.** The transcript is the
   application's own data, and it survives the process and agent-session events in the table —
   provided the Room database itself remains intact. Nothing here promises durability against a
   deleted or corrupted database, cleared application data, or an uninstall.
2. **Resuming an *execution* is a property of *someone else's* live process.** `session/resume`
   reconnects to a session that process still holds, so it needs a reachable agent that kept that
   session — the last table row is the case where the agent is reachable (a separately running
   agent over a socket). This application does not run anyone else's long-lived process: it launches
   the agent itself and speaks to it over pipes, so the session lives and dies with the app
   (`Subprocess.spawnDuplex`, `core/process/Subprocess.kt:295-309`; `ProcessTree`,
   `AcpClient.kt:485-601`) — and the app cannot re-attach to a process it did not spawn.
   **`session/load` promises something different, and this application cannot collect it either —
   yet.** There, the persistence is the *agent's*: the agent stores its transcript, and a fresh child
   reloads it by id. The application's own blockers are the ones from §2 (no capability read, no
   session id, a handshake the pinned SDK cannot complete) plus one that is a design question rather
   than a missing piece: the replayed history comes *from the agent*, so an application that also
   keeps its own transcript in Room would have two, and would have to say which one is the truth.
   The recommendation below answers that for the built-in chat — the application's own transcript —
   and leaves the ACP path as a later decision.
3. **Restoring a transcript does not restore the model's memory of it.** Today the model is re-told
   the last six turns as 200-character prose (`ChatTurn.kt:114-134`). If the transcript becomes
   durable, the same lossy block is still what the model sees — and a durable transcript invites the
   question "why does the model not remember what I can see?", whose honest answer is a separate
   decision (feed the real turns, and pay for the tokens).

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
4. *Then, separately*: the ACP capability read (`sessionCapabilities?.resume` and `loadSession`) —
   two lines, no persistence — and, with it, the session-id store. The call to look at first is
   `session/load`, not `session/resume` (the child-per-launch topology gives this app no surviving
   agent to reconnect to after a restart, while `load` is the protocol's documented
   persistence-across-restarts path and both bridges implement it) — but it carries a question the
   transcript does not: the agent would replay its own history, so the app must decide whose
   transcript is the truth.

## How to reproduce the findings

Every command that produced the evidence above — the dependency lookups, the pinned-source reads,
the bridge-package reads and the live handshake probe — is listed in the
[appendix](3160-koog-persistence-and-acp-resume-appendix.md#how-to-reproduce-the-findings).

## What was not verified

- The **live** handshake against the Claude and Codex bridges at the versions checked on
  2026-11-25 was run and **failed**: both bridges answered `initialize` with a frame the pinned
  SDK 0.34.0 cannot decode (`$.status`, boolean vs object). So the capability shapes above are
  confirmed **on the wire**, but no session was ever opened with these bridges under the pinned
  SDK. Frames: `build/diagnostics/acp-probe-claude.log`, `build/diagnostics/acp-probe-codex.log`.
  The 2026-10-25 run against claude-agent-acp 0.95.0 is the one that completed a handshake
  (`~/.cache/pompano-tmp/acp-bridge-probe.7t7HgO.log`).
- The **default** profile was probed live on 2026-11-25 (`build/diagnostics/acp-probe-gemini.log`,
  `@google/gemini-cli@0.71.1`). Its `initialize` frame **decodes** under the pinned SDK and confirms
  the table's row — `loadSession: true`, no `sessionCapabilities` — but the probe then failed at
  `session/new` with `Gemini API key is missing or not configured.`, so no session was opened
  against it either. Nothing about Gemini's behaviour *after* `initialize` is established here.
- Whether a bridge's `session/resume` **works** end to end — that needs a live agent with a live
  session, and it is not what this issue asks for.
- Whether a bridge would answer `session/resume` for a session **retained across a restart of the
  agent's own process** (claude-agent-acp 0.112.0, codex-acp 2.12.0). The protocol does not forbid
  it, and the note does not assert that it is impossible — it was simply not exercised, so this
  remains **unverified**. What *is* verified is the transport fact on this application's side: it
  launches a fresh child per run and cannot re-attach to an agent it did not spawn.
- Whether a bridge's `session/load` **works** end to end. The implementations were read from the
  packages (claude-agent-acp 0.112.0, codex-acp 2.12.0) and both replay a stored transcript by
  session id, but no load was performed: it needs a live agent that already has a stored session,
  and under the pinned SDK 0.34.0 the handshake does not complete at all (see above).
- Whether the bridges' stored transcripts survive a restart **in practice**. That is the agents' own
  storage, not this repository's, and it is not covered by anything here.
- Gemini CLI was checked at 0.71.1 only; the flag the profile uses (`--experimental-acp`) still
  exists there, and the current documentation prefers `--acp`. That is a separate, small follow-up.
- The ACP path is **desktop-only** today (`shared/src/jvmMain/.../feature/ai/acp/`). Any durable
  ACP session story is a desktop story until that changes.
