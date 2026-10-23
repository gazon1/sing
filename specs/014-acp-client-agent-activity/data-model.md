# Phase 1 Data Model: External Agent and Agent Activity

Entities, their fields, the rules that constrain them, and the state transitions that carry the two guarantees this feature exists for: **a turn reaches exactly one terminal outcome**, and **no write reaches the user's data outside the ask-first pipeline**.

Field types are given in the project's own vocabulary. Nothing here names a database column — the only persistent change in this feature is a registry field, not a schema migration.

---

## 1. Activity event (the shared vocabulary)

Transport-independent. Produced by the built-in agent's loop and by the external-agent adapter; consumed by every ViewModel.

| Kind | Fields | Rules |
|---|---|---|
| `TextDelta` | `text: String` | An **addition**, never the whole message. Concatenating the deltas of one turn reconstructs the answer; overwriting with a delta loses it. |
| `ToolCallUpdated` | `id: String`, `name: String`, `status: ToolCallStatus`, `summary: String?` | `id` is stable across updates of the same call. `summary` is bounded and sanitized; the raw payload is never carried (FR-019, FR-020). |
| `PlanUpdated` | `steps: List<PlanStep>` | Replaces the turn's current plan wholesale — it is a snapshot, not a delta. |
| completion | *(no fields)* | Emitted exactly once per turn, only on successful completion (D-05). |

**Validation rules**

- A `ToolCallUpdated` whose `id` is already known updates the existing card; it never creates a second one.
- `summary` length is bounded; over-long summaries are truncated by the adapter, not by the renderer.
- `summary` contains only identifiers, titles and field names. A note body reaching a summary is a defect, not a formatting choice.

### Tool call status

`RUNNING` · `COMPLETED` · `FAILED`

Collapsed from the protocol's four states (D-08): pending and in-progress both read as *still going*. The three values answer the only question a reader has.

### Plan step

`title: String`, `status: PlanStepStatus` where `PlanStepStatus` = `PENDING` · `IN_PROGRESS` · `COMPLETED`.

Maps one-to-one onto the protocol's plan entry status.

---

## 2. Turn

The unit of work: one user message, one response, one outcome.

| Field | Meaning |
|---|---|
| `id` | Identifies the turn; activity updates and cancellation address it. |
| `userMessage` | What was asked. |
| `activity` | The accumulated event trace — text, tool calls, plan. |
| `outcome` | Exactly one of the three terminal states. **Nullable while the turn is running.** |
| `truncationNote` | Optional: set when the agent stopped because of a limit, not because it finished (D-06). |

### Terminal outcome state machine

```text
                 ┌──────────────► Completed      (normal end, or a token/turn limit —
                 │                                  completed with a truncation note)
                 │
   [running] ────┼──────────────► Failed         (refusal, transport death, tool
                 │                                  server unreachable, or the stream
                 │                                  ended without a completion event)
                 │
                 └──────────────► Cancelled      (user cancelled, or the screen closed)
```

**Invariants**

1. **Exactly one** terminal outcome per turn. The transition happens once, in a single place, guarded by a check that the outcome is still unset.
2. A stream that ends **without** the completion event is `Failed` — never `Completed` (D-05, FR-023).
3. Cancellation resolves in `finally`, so a cancelled turn cannot leave the loading state set (FR-024).
4. A turn that has an outcome cannot change it. Late events arriving after the outcome are ignored, not applied.

### Turn lifecycle

```text
Idle ──send──► Running ──completion event──► Completed
                  │  ▲                         │
          cancel  │  │ resume?                 │ send (next turn)
                  ▼  │                         ▼
               Cancelled                      Idle
                  │
                  └──error──► Failed ─────────┘
```

---

## 3. External agent (registry entry)

Data, not behaviour — the app ships a list, the user picks one.

| Field | Meaning | Rule |
|---|---|---|
| `id` | Stable identifier | Unique within the registry |
| `displayName` | What the user reads | Non-empty |
| `command` | The executable to launch | Non-empty; never run through a shell |
| `args` | Arguments, in order | Passed as a list, never joined into a string |
| `description` | One line explaining what this agent is | — |
| `isDefault` | Preselected entry | Exactly one entry in the registry |

Shipped entries: the Gemini CLI in ACP mode (default), the Claude Agent bridge, the Codex bridge. The latter two need a Node runtime; that is a prerequisite of *those* entries, and the connect error must say so rather than failing opaquely.

---

## 4. Agent session

| Field | Meaning | Rule |
|---|---|---|
| `agent` | Which registry entry | — |
| `state` | Disconnected / Connecting / Connected / Failed | Enforces at most one live session per ViewModel |
| `profileId` | The app profile the agent's tools operate on | The app's own active profile is never changed |
| `toolAccess` | Granted / Unavailable | `Unavailable` always carries a reason |
| `warning` | Persistent message shown for the whole session | Non-null exactly when `toolAccess == Unavailable`, or when a version mismatch was detected |
| `pendingPermission` | At most one outstanding permission request | Resolved by user choice, timeout, or turn end |

### Session state transitions

```text
Disconnected ──connect──► Connecting ──ok──────► Connected
                              │                   │
                              │                   │ close
                              │                   ▼
                              └────failure───► Disconnected (with a diagnosable reason)
                                                  + the process tree is terminated
```

**Rules**

- Connect failure reasons are distinct, because they need different user actions: command not found; protocol version unacceptable; the agent requires its own sign-in; the tool server is unavailable (which is **not** a connect failure — the session connects and carries a warning, D-13, FR-032).
- `warning` survives across turns. It is a property of the session, not of a message, so it cannot flicker away after the agent's first reply.

---

## 5. Tool call (as rendered)

Distinct from the activity event: this is what the reader sees.

| Field | Meaning |
|---|---|
| `id` | Key for in-place updates |
| `name` | Tool name as the agent's own tooling reports it |
| `status` | Running / completed / failed |
| `summary` | Sanitized, bounded, **hidden by default**, revealed on demand |
| `rawPayloadAvailable` | Whether anything is available to reveal — never the payload itself in the state that renders |

The raw payload is not part of the turn state. It is fetched only when the user asks, and it never reaches the log (FR-026).

---

## 6. Permission request

| Field | Meaning | Rule |
|---|---|---|
| `toolCallId` | Which agent tool call needs it | — |
| `options` | The choices the agent offers | At least one accept and one decline |
| `outcome` | Chosen option / declined / cancelled / timed out | Resolved exactly once |
| `deadline` | When an unanswered request is declined | Default 5 minutes |

**Rules**: an unanswered request resolves as **declined** — fail-closed (FR-036). A request is resolved when the user answers, when the turn ends or is cancelled (FR-037), or when the session closes. A request that survives its own turn is a defect.

---

## 7. Tool write classification (the registry field)

Added to every agent-facing tool entry — 43 of them.

| Field | Meaning | Validation |
|---|---|---|
| `writeBehavior` | `READ_ONLY` / `PROPOSAL_WRITE` / `DIRECT_WRITE_EXCEPTION` | Exactly one; no default, so an unclassified tool cannot compile |
| `exceptionReason` | Why an exception is outside the pipeline | **Required and non-empty** for `DIRECT_WRITE_EXCEPTION`; forbidden otherwise |

**Rule**: the field has no default value, so adding a tool without classifying it breaks the build rather than silently defaulting to read-only. The existing catalog-versus-Koin consistency test is extended, not replaced (D-15).

### Current classification

| Behaviour | Count | Tools |
|---|---|---|
| `PROPOSAL_WRITE` | 13 | task/note/project create-update-delete, tag delete, task decomposition, task-proposal writing |
| `DIRECT_WRITE_EXCEPTION` | 3 | tag creation (a tag is not user content — a proposal would be noise), decision-record write and update (they write decision files, not user data) |
| `READ_ONLY` | 27 | the remaining reads and analyses |

**Invariant**: no third path exists. A write either goes through the proposal pipeline or is one of the three named exceptions with a stated reason (FR-049).

---

## 8. Tool server launch command (what the agent is handed)

The value the session publishes as the agent's access to the user's data.

| Field | Meaning | Rule |
|---|---|---|
| `name` | Identifier the agent knows it by | Stable |
| `command` | Executable to run | Uses the JRE shipped with the app, never a system `java` (D-13) |
| `args` | Arguments, including the profile selector | List, never a joined string |
| `env` | Environment for the child | Part of the contract, not an afterthought |

**Rules**: an unknown profile id is a startup error, never a fallback (FR-007). Both profile selectors at once is a startup error (FR-009). The legacy label selector keeps its meaning and is logged as deprecated (FR-008).

---

## Relationships

```text
AgentSession ──uses──► ExternalAgent          (registry entry)
      │
      ├──publishes──► Tool server launch command  (one per session; may be absent)
      │
      └──owns───────► ProcessTree            (agent process + everything it spawned)

Turn ──produces──► Activity events ──► Tool calls (in-place updates, one card per id)
                               └────► Plan (snapshot)

Tool call ──may need──► Permission request

Tool (43) ──classified by──► WriteBehaviour (+ reason)
```

The single most important relationship is the one that is *absent*: nothing in this model lets the external agent reach the user's data except through the tool server launch command. There is no second path, which is what makes the trust boundary in the spec a structural property rather than a convention.

---

## What is deliberately not modelled

- **Cost and usage.** The protocol carries them; this release does not surface them (D-12).
- **Agent session modes, models and config options.** The SDK exposes them; this release ignores them, and the adapter drops their updates (D-07).
- **Agent reasoning (`AgentThoughtChunk`).** Not part of the vocabulary and not something the reader asked to see (D-07).
- **A general event bus.** Activity flows to exactly one consumer: the ViewModel that owns the turn.