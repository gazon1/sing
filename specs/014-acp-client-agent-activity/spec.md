# Feature Specification: External Agent in the App (ACP Client) and Agent Activity Visibility

**Feature Branch**: `gazon1/acp-client-agent-activity`

**Created**: 2026-10-23

**Status**: Draft

**Input**: User description: "Внешний агент в приложении (ACP-клиент) и видимость активности агента" — a full brief covering three independently mergeable parts: (A) database safety, profile isolation and write-classification of agent tools, (B) visible activity of the built-in agent, (C) an ACP client that hosts a third-party agent (Gemini CLI / Claude Agent / Codex) inside the desktop app and gives it access to the user's tasks through the app's own tool server.

## Problem

Three separate problems block "let a third-party agent work on my tasks, inside my todo app":

1. **The built-in agent is invisible.** It calls tools, it gets results, and the user sees only prose. When it fails, when it is cancelled, when it asks permission to write — nothing of that is visible. There is no way to answer "what did it just do to my data?".

2. **There is no supported way to host a third-party agent.** The app cannot launch Gemini CLI / Claude Agent / Codex, cannot hold a conversation with them, cannot show their activity, and cannot supervise their lifecycle. The pieces needed to do so — the app's own tool server, the proposal pipeline, the process-spawning port — exist, but nothing connects them.

3. **The groundwork that a second process would stand on is unsafe.** The startup database probe deletes the user's database file on *any* error, not only on proof that the file is not ours. The profile selection of a second process silently switches the app's stored active profile. The set of tools agents can call has no declared way of writing, so "all writes ask first" is a belief, not an enforced fact.

The brief's central conclusion is worth stating up front: **the conversation and the tools travel on different protocols.** The Agent Client Protocol (ACP) carries the conversation with the external agent; the app's existing MCP tool server carries the tools. The app does not gain a second copy of its tooling — it hands the agent a launchable command for the tool server it already has, so domain access, the kill switch, the tool-call log and the proposal pipeline all stay in one place.

## Terminology

| Term | Meaning |
|---|---|
| **Built-in agent** | The assistant inside the app's chat screen. |
| **External agent** | A third-party command-line agent (Gemini CLI, Claude Agent, Codex) that the app launches and talks to over ACP. |
| **Tool server** | The app's existing MCP server, exposing the user's tasks, notes, projects and tags to agents. |
| **Turn** | One user message to an agent and the agent's response to it. |
| **Activity** | The visible trace of a turn: text as it arrives, tool calls and their outcomes, plan steps. |
| **Proposal** | The existing "ask before writing" mechanism: the agent proposes a change, the user confirms, and only then is it written. |
| **Write behaviour** | How a tool writes: read-only, asks-first (goes through a proposal), or a direct-write exception with a stated reason. |

## Delivery scope

The work is delivered as **three parts, each independently mergeable**, each with its own acceptance criteria. This specification covers all three; the implementation order is A → B → C.

| Part | Content | Depends on |
|---|---|---|
| **A — Data safety** | Fail-safe database probe; profile isolation for external processes; write-behaviour classification of every agent-facing tool; valid, non-leaking error responses. | — |
| **B — Activity visibility** | Minimal activity event vocabulary; terminal turn outcomes; activity cards in the built-in chat. | — |
| **C — External agent** | Reproducible packaged-build probe; one chosen tool-server delivery mechanism; process-tree ownership; ACP client; external-agent screen with a permission sheet. | A, B |

## Out of scope

- The app acting as an **agent for an IDE** (a headless second distributable) — Stage 2.
- Protocol v2 (draft), MCP-over-ACP, in-process HTTP tool server.
- Mobile support of any kind.
- Changing the set of tools, the behaviour of existing tools, or the proposal pipeline's mechanics.
- Cost or usage accounting for the external agent's own provider.
- A universal roles/permissions system, a general event bus, or auto-retry of a failed turn.
- Any second, parallel delivery mechanism for the tool server.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - I can see what the agent is doing to my data (Priority: P1)

I ask the built-in agent to reorganise my projects. While it works, I want to watch which tools it calls, what each call touched, whether it succeeded or failed, and what its plan is — and when it stops, I want to know that it stopped, and why.

**Why this priority**: This is the cheapest part of the brief and it delivers value on its own, with no external agent, no packaging work and no protocol surface. It also removes the trust problem that blocks everything else: if the user cannot see what an agent did, they cannot approve it.

**Independent Test**: Drive the built-in chat with a prompt that triggers several tool calls, one of which fails, then cancel a second prompt. Assert that every call appears as a card with a name and an outcome, that the failure is visible, and that the cancelled turn ends in a state that is not "loading". This part can be merged, demoed and reverted without Part C.

**Acceptance Scenarios**:

1. **Given** a chat message that makes the agent call two tools, **When** the response streams in, **Then** the chat shows one activity card per call, each with the tool name, a running → completed status, and a short readable summary of what it touched.
2. **Given** a tool call that fails, **When** the failure arrives, **Then** the card shows the failure rather than staying silent, and the turn ends with a visible error outcome that is distinguishable from success.
3. **Given** the agent produces a plan, **When** the plan is emitted, **Then** the chat shows the plan steps with their current state.
4. **Given** the user cancels a running turn, **When** the cancellation completes, **Then** the loading indicator stops, the partial answer that was already produced remains, and the turn is marked cancelled.
5. **Given** a tool whose full payload contains note bodies or long text, **When** its card is shown, **Then** only a bounded, sanitized summary is visible by default — identifiers, titles and field names, but no note bodies — the raw payload is hidden until the user asks for it, and no raw payload is written to the activity log.
6. **Given** the agent's stream ends unexpectedly, **When** the turn is finalised, **Then** the turn reaches exactly one terminal outcome — completed, failed or cancelled — and never remains in a loading state.

---

### User Story 2 - My data stays mine, and my identity in the app is not hijacked (Priority: P2)

I keep my whole task history in this app. It must never be deleted because a startup check was unsure, and no tool running next to me may quietly switch which profile I am working in.

**Why this priority**: The database-deletion defect exists today, before any external agent exists: on an ambiguous startup probe the app deletes the user's database. That alone justifies the priority. The rest of Part A is the ground that Part C stands on — a second process touching the same database and the same profile state must be safe by construction, not by luck.

**Independent Test**: Point the startup probe at a locked, unreadable, damaged and empty file and assert that each is left byte-identical; point it at a valid database file that lacks the app's bookkeeping marker and assert that it *is* cleaned up. Run a second tool-server process against a named profile and assert that the app's stored active profile is unchanged afterwards and that an unknown profile id fails loudly.

**Acceptance Scenarios**:

1. **Given** the app starts and the startup database probe fails for an ambiguous reason — file locked, unreadable, damaged, empty, permission denied — **When** startup continues, **Then** the database file and its contents are left exactly as they were, and the condition is recorded rather than resolved by deletion.
2. **Given** a readable, structurally valid database file that lacks the app's own bookkeeping marker, **When** the probe runs, **Then** the file is recognised as not belonging to the app and is cleaned up as before, together with its companion files.
3. **Given** an external process that needs the user's tasks, **When** it starts with a named profile, **Then** it works against that profile's data without opening or changing the application's stored active-profile state.
4. **Given** an unknown or missing profile id is supplied to an external process, **When** the process starts, **Then** it stops with a clear diagnostic and a non-zero exit, instead of silently falling back to a different profile.
5. **Given** every tool that agents can call, **When** the tool registry is inspected, **Then** each tool declares exactly one write behaviour, each direct-write exception carries a stated reason, and the set of declared tools matches the set of tools actually exposed to agents.
6. **Given** a tool declared as asks-first, **When** an agent calls it, **Then** a proposal appears and nothing is written until the user confirms; if the user declines, the user's data is unchanged.
7. **Given** the tool server reports an error whose text contains a quote, a newline or other control characters, **When** the error is sent back to the caller, **Then** the response is still a valid protocol message and contains no file paths or internal diagnostics.
8. **Given** the tool server fails while starting up, **When** the failure is handled, **Then** the process stops in a defined state — no live dependency container, no open database handle, no half-started process — and the caller receives a diagnostic.

---

### User Story 3 - I can bring my own agent and watch it work on my tasks (Priority: P3)

I already pay for Gemini CLI and it knows how to plan my week. I want to open my todo app, pick that agent, and say "reorganise my projects and replan the week" — and see the agent work, watch it propose changes to my tasks, and approve what I want.

**Why this priority**: This is the headline capability and the widest user value, but it is also the most expensive and the riskiest part: it depends on Part A's ground and Part B's presentation, and its delivery mechanism for the tool server inside a packaged desktop build is not yet proven. It is last in priority because Parts A and B are viable, valuable, and independently shippable on their own.

**Independent Test**: Run the desktop app from a packaged build, select the default external agent, connect, send one prompt that triggers a read and a write, and assert that the read result and the write proposal are visible, that the proposal applies only after confirmation, and that ending the session leaves no process behind.

**Acceptance Scenarios**:

1. **Given** the desktop app, **When** the user navigates to the external agent screen from the application's navigation, **Then** they can pick an agent from a registry of known agents, see which command will be launched, and one entry is preselected.
2. **Given** an agent is selected, **When** the user connects, **Then** the app reports the session as connected and the user can send a message.
3. **Given** the external agent calls the app's tools, **When** those calls happen, **Then** they appear as activity cards in the same presentation as the built-in agent's, and every write reaches the user as a proposal.
4. **Given** the agent requests permission to use one of its own tools, **When** the request arrives, **Then** the app asks the user, applies the answer, and the answer is visible afterwards.
5. **Given** the user ignores a permission request, **When** the deadline passes, **Then** the request is declined and the turn continues; ending or cancelling the turn resolves any request that is still pending.
6. **Given** the tool server cannot be found in this installation, **When** the session starts, **Then** the app warns the user *before the first message* that the agent has no access to their tasks, and that warning stays visible for the whole session — visibly different from a tool that failed after a successful connection.
7. **Given** the user ends the session, **When** the session closes, **Then** no process started by that session — including a process the agent started itself — is left running, and closing an already-closed session is safe.
8. **Given** the agent process dies mid-turn, **When** the user reads the chat, **Then** the partial answer stays, an error with a way to reconnect is shown, and nothing is retried automatically.
9. **Given** the user sends a second message while a turn is in progress, **When** the send is attempted, **Then** it is refused with a clear message instead of interleaving two turns.
10. **Given** the bundled tool server and the running tool server report different versions, **When** the connection is made, **Then** the mismatch is surfaced as a warning rather than silently ignored.
11. **Given** a packaged desktop build, **When** the app determines how to launch its own tool server, **Then** the determination comes from a check that is reproducible from the repository, and exactly one launch mechanism is in use.
12. **Given** the external agent asks for file-system or terminal access, **When** the request arrives, **Then** it is declined with an explicit not-supported outcome.

---

### Edge Cases

- **Two processes on one database.** The app and an external agent's tool server may hold the same database at once. Reads must succeed for both; a lock must be waited out, not resolved by deleting anything.
- **The database file is not a database.** A file that is not a structurally valid database is an ambiguous case, not a licence to delete: only a *valid* database file that lacks the app's bookkeeping marker is cleaned up.
- **The external agent is not on the machine.** The app shows a diagnosable error naming the command and where it was expected to be found — never a stack trace.
- **The agent asks for file-system or terminal access.** The todo domain has neither; the app refuses with an explicit "not supported" outcome rather than pretending.
- **The agent itself launches the tool server.** The tool server is a grandchild of the app's process; ending the session must end it too, otherwise an orphaned server keeps running against the user's database.
- **A permission request arrives after its turn ended.** It is resolved immediately as declined; it never becomes a dangling question.
- **The tool server is missing in an older installation.** The session still starts, without tool access and with the warning visible — the app does not fail to open.
- **The agent crashes repeatedly.** Each crash ends the turn; the app does not restart the agent or replay the prompt, because a replay could duplicate a proposal or a write.
- **The built-in agent's tool payload contains sensitive content.** Only a bounded, sanitized summary is ever rendered; the raw payload stays out of the UI and out of the log.

## Requirements *(mandatory)*

### Part A — Data safety and write classification

- **FR-001**: The app MUST NOT delete the user's database when the startup probe cannot determine with certainty that the file is not an app-managed database.
- **FR-002**: The app MAY delete a database file only when the file is readable, is a structurally valid database file, and lacks the app's own bookkeeping marker.
- **FR-003**: When the probe result is ambiguous, the app MUST leave the file and its contents unchanged and MUST record the diagnostic; deletion MUST NOT be used as a resolution.
- **FR-004**: The probe MUST NOT delete the database's companion files unless the primary database file itself was deleted under FR-002.
- **FR-005**: The probe MUST leave a file byte-identical in every ambiguous case, not merely present on disk.
- **FR-006**: A tool server process started by an external party MUST be able to operate against a caller-supplied profile id WITHOUT opening or modifying the application's stored active-profile state.
- **FR-007**: An unknown or missing profile id MUST cause a clear startup diagnostic and a non-zero exit; it MUST NOT silently fall back to a default profile.
- **FR-008**: The legacy label-based profile argument MUST retain its current meaning, MUST NOT be silently reinterpreted, and MUST be marked as deprecated in documentation and in the startup log.
- **FR-009**: Supplying both the legacy and the new profile arguments MUST be a startup error.
- **FR-010**: Every tool exposed to agents MUST declare exactly one write behaviour: read-only, asks-first, or direct-write exception. (The registry that carries this is the same one FR-049 and FR-050 make auditable.)
- **FR-011**: A direct-write exception MUST carry a non-empty reason stating why it sits outside the ask-first pipeline. (The exceptions themselves are listed in FR-050.)
- **FR-012**: A tool declared as asks-first MUST NOT change user data until the user confirms the proposal; declining the proposal MUST leave the data unchanged.
- **FR-013**: The declared tool registry and the set of tools actually exposed to agents MUST agree; any tool present in one and missing from the other MUST fail the consistency check.
- **FR-014**: Every response the tool server sends MUST be a valid protocol message, including when the underlying error text contains quotes, newlines, backslashes or other control characters.
- **FR-015**: The tool server MUST NOT expose internal diagnostics — file paths, database messages, internal exception text — in its responses to callers; such detail belongs in the log only.
- **FR-016**: A failure during tool-server startup MUST NOT leave the process in an undefined state: no live dependency container, no open database handle, no half-started process. **Observable proxies**: the container's `close()` is called on every failure path, the database handle is released (the process exits rather than waiting on an open connection), and the failure path is covered by a test that asserts the cleanup ran.

### Part B — Activity visibility of the built-in agent

- **FR-017**: A turn MUST produce a stream of activity events drawn from a small fixed vocabulary: text addition, tool-call update, plan update, and successful completion.
- **FR-018**: A tool-call update MUST carry the tool identifier, the tool name, and a status of running, completed or failed.
- **FR-019**: The activity event vocabulary MUST NOT carry raw tool payloads; a tool-call update MUST carry a bounded, sanitized summary instead. **The bound is 200 characters** — a summary longer than that is cut at a word boundary and the cut is visible.
- **FR-020**: A sanitized summary MUST contain only identifying information — identifiers, titles, field names, numbers and flags — and MUST NOT contain note bodies or other free user content. **Measurably**: at most 6 fields are named; a value is quoted only when its **field** is one that identifies — an identifier, a title or name, a kind or state, a date, a number or a flag — and the value is single-line and at most 60 characters; every other value, and every longer or multi-line value, is replaced by an ellipsis. The field decides, not the value's length: a one-line note body is free text however short it is.
- **FR-021**: The raw payload MUST be hidden by default, revealed only on demand, and length-bounded when revealed — the revealed payload is bounded by the same 200-character rule as the summary.
- **FR-022**: Every turn MUST end with exactly one terminal outcome — completed, failed, or cancelled — no matter how the event stream ends. **The fallback is `failed`**: a stream that ends without a completion event and without an error is a failure, because a turn that ends in silence did not answer.
- **FR-023**: A transport or provider failure MUST be reported as a failure outcome; it MUST NOT be reported as a successful completion. A missing credential is a failure too: a provider with no API key cannot answer, and reporting it as a completion tells the reader their agent worked when it never ran.
- **FR-024**: Cancelling a turn MUST stop the loading state, release the turn's resources, and preserve the answer produced so far.
- **FR-025**: The chat MUST render one activity card per tool call and one per plan, using a single shared presentation that any other agent surface can reuse.
- **FR-026**: Raw tool payloads MUST NOT be written to the activity log.
- **FR-027**: The text-generation capability MUST expose the activity stream as its primary response shape for a turn, while keeping its plain-text form available for callers that only need the answer.

### Part C — External agent sessions in the desktop app

- **FR-028**: The desktop app MUST offer a screen where the user can select an external agent from a registry, connect, exchange messages, and disconnect.
- **FR-029**: The agent registry MUST contain at least one entry that is preselected by default.
- **FR-030**: The app MUST determine, by a reproducible check that runs against a packaged desktop build, how the tool server is launched — and exactly ONE delivery mechanism MUST be implemented. The check is `scripts/probe-packaged-tool-server.sh`, run against a real packaged desktop build; a run is reproducible when the same script, on the same machine class, yields the same mechanism — the script's own output is the record.
- **FR-031**: The app MUST hand the external agent a launchable reference to its own tool server so that the agent's access to the user's data is mediated by the app's existing tooling.
- **FR-032**: When the tool server reference cannot be produced, the app MUST start the session WITHOUT tool access, MUST show a visible warning before the first message is sent, and MUST keep that warning visible for the whole session. **The access state starts *unknown*** — nothing has been negotiated yet, and a state that claims access before then is a false claim; a handshake that fails leaves the state *unavailable* (with the reason), never *granted*.
- **FR-033**: The "no access to your tasks" state MUST be visually and textually distinguishable from "a tool failed after a successful connection".
- **FR-034**: The app MUST NOT require the external agent to be granted file-system or terminal capability; such requests MUST be answered with an explicit not-supported outcome.
- **FR-035**: A permission request from the external agent MUST be presented to the user as an accept/decline choice.
- **FR-036**: A permission request that receives no answer within the timeout MUST be declined, and the turn MUST continue afterwards.
- **FR-037**: Cancelling or ending a turn MUST also resolve any pending permission request.
- **FR-038**: Closing a session MUST terminate every process in the session's process tree — the agent and any process the agent itself started — including those that survive the agent's own termination.
- **FR-039**: Closing a session MUST be safe to repeat and MUST NOT fail on an already-closed session.
- **FR-040**: A crashed or hung agent process MUST NOT leave the interface in a loading state; the user MUST see an error and a way to reconnect. **Measurably**: a turn that produces no event for 60 seconds is treated as hung and ends as a failure with a visible error; a session whose process tree dies is marked disconnected rather than connected; and the turn that was running keeps the answer produced so far.
- **FR-041**: The app MUST NOT automatically retry a failed prompt or a failed tool call.
- **FR-042**: The app MUST refuse to begin a second turn while one is in progress, with a clear message.
- **FR-043**: The **protocol** version the app speaks and the version the running agent reports MUST be compared when connecting, and a mismatch MUST be surfaced as a warning — and a mismatch of the protocol version MUST refuse the connection rather than continue on a guess, because two versions of the protocol may mean different things by the same words. The tool server's own version is **reported, not compared**: the agent launches the tool server, so the app cannot see a version it would compare against. The choice is recorded in `docs/decisions/2026-10-25-the-version-we-can-compare.md`.
- **FR-044**: Session lifecycle events — connection, disconnection, session identity, permission-request identity, transport errors, process-tree termination — MUST be written to the log and MUST NOT be written into the protocol stream. **The sink is the `acp-session` log tag**, and the minimum content of each event is: the agent's name, version and protocol version (connection), the session id (session identity), the tool-call id and title (permission request), the reason (disconnection), and the number of processes (process-tree termination).
- **FR-045**: The external-agent screen MUST be reachable through the application's navigation; it MUST NOT exist only as an unreferenced preview. **One tap from the chat surface**: the chat's toolbar is the entry point.
- **FR-046**: The mobile build MUST NOT depend on the external-agent protocol, the process-spawning capability, or any part of the external-agent screen.

### Trust boundaries

- **FR-047**: Permission requests from the external agent govern the agent's OWN tools; they MUST NOT be presented as if they gated the app's own writes.
- **FR-048**: A write performed by an external agent MUST reach the user through the same ask-first pipeline as a write performed by the built-in agent. **The pipeline is the application's own proposal pipeline** (`feature/proposals`): the external agent reaches the user's tasks through the application's MCP tool server, and every asks-first tool in that server turns its write into a proposal the user confirms.
- **FR-049**: Every write to the user's data MUST be gated by the ask-first pipeline or be one of the explicitly listed direct-write exceptions; no third path may exist. (The exceptions are listed in FR-050.)
- **FR-050**: The declared set of direct-write exceptions MUST be visible in the tool registry together with the reason for each, so that the guarantee can be audited without reading the implementation.

### Requirement traceability

Every functional requirement is exercised by at least one acceptance scenario, except where the requirement concerns an internal artefact that is verified by an automated check rather than by user-visible behaviour. **The two exceptions are named below, and both checks exist**: the log check (FR-044) and the mobile-isolation check (SC-011).

| Requirements | Exercised by |
|---|---|
| FR-001 – FR-005 | User Story 2, scenarios 1 and 2 (database probe) |
| FR-006 – FR-009 | User Story 2, scenarios 3 and 4 (profile isolation) |
| FR-010 – FR-013, FR-049 – FR-050 | User Story 2, scenario 5 (write classification) |
| FR-047 – FR-048 | User Story 2 scenario 6; User Story 3, scenario 3 (ask-first pipeline) |
| FR-014 – FR-015 | User Story 2, scenario 7 (valid, non-leaking errors) |
| FR-016 | User Story 2, scenario 8 (defined startup failure) |
| FR-017 – FR-021, FR-025 – FR-027 | User Story 1, scenarios 1, 3 and 5 (activity vocabulary, cards, summaries) |
| FR-022 – FR-024 | User Story 1, scenarios 2, 4 and 6 (terminal outcomes) |
| FR-028 – FR-029, FR-045 | User Story 3, scenario 1 (registry, navigation) |
| FR-030 | User Story 3, scenario 11 (reproducible determination, one mechanism) |
| FR-031 – FR-033 | User Story 3, scenarios 3 and 6 (tool access and its absence) |
| FR-034 | User Story 3, scenario 12 (no file-system, no terminal) |
| FR-035 – FR-037 | User Story 3, scenarios 4 and 5 (permission sheet, timeout, cancellation) |
| FR-038 – FR-039 | User Story 3, scenario 7 (process tree, repeatable close) |
| FR-040 – FR-042 | User Story 3, scenarios 8 and 9 (crash, no auto-retry, single turn) |
| FR-043 | User Story 3, scenario 10 (protocol version mismatch) |
| FR-044 | Automated check: `AcpSessionEventTest` — lifecycle events reach the log and never the protocol stream |
| FR-046 | Automated check: `check-mobile-isolation.py` — the mobile build carries no external-agent code (SC-011) |

## Key Entities

- **External agent**: a selectable third-party agent — display name, the command that launches it, its arguments, and a description of what it is.
- **Agent registry**: the ordered list of external agents the app offers, with exactly one default selection.
- **Agent session**: a live connection to one external agent — the chosen agent, the connection state, the profile in use, whether tool access was granted, and any persistent warning attached to the session.
- **Turn**: one user message and the agent's response — the activity produced, the terminal outcome, and the cancellation state.
- **Activity event**: one entry in the turn's trace — a text addition, a tool-call update, a plan update, or a successful completion.
- **Tool call**: an identified, named call with a status (running / completed / failed) and a bounded sanitized summary; the raw payload is never part of it.
- **Plan**: an ordered list of steps, each with its own state (pending / in progress / completed).
- **Permission request**: an agent's request for a capability, with the offered options, a deadline, and the outcome the user chose.
- **Tool write classification**: the declared write behaviour of a tool — read-only, asks-first, or direct-write exception with a reason.
- **Proposal**: the pending, user-confirmable change produced by an asks-first tool.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100 % of the built-in agent's tool calls are visible to the user with a name and an outcome — no call is invisible in any turn.
- **SC-002**: 0 turns end in an unexplained loading state; every turn reaches exactly one of completed, failed, or cancelled.
- **SC-003**: A user goes from "external agent selected" to "first visible agent response" in under 15 seconds on a typical developer machine (median over 20 runs). **The machine class is pinned**: a developer machine means a 4-core x86-64 laptop with 16 GB of RAM, running the packaged desktop build on the same host, with the agent and its Node runtime already installed and warm — the measurement excludes the one-off cost of the agent's own first-run download.
- **SC-004**: 0 databases are deleted across the ambiguous-probe scenarios (locked, unreadable, damaged, empty, permission-denied) — verified by content comparison, not by file presence.
- **SC-005**: 100 % of agent-exposed tools carry a write classification, and the consistency check fails whenever one is missing.
- **SC-006**: 0 writes to the user's data occur without a user confirmation across the full tool set, excluding the explicitly listed exceptions — and each exception's reason is visible in the registry.
- **SC-007**: After a session ends, 0 processes from that session's process tree remain, including processes the agent started itself.
- **SC-008**: A permission request that receives no answer is declined within 5 minutes in 100 % of cases; 0 turns hang on an unanswered request.
- **SC-009**: After an agent crash mid-turn, the partial answer is preserved in 100 % of cases and the prompt is never re-executed.
- **SC-010**: The tool server's responses remain valid protocol messages in 100 % of error cases, including error texts containing quotes and newlines, and contain no file paths.
- **SC-011**: The mobile build contains no external-agent or process-spawning code.
- **SC-012**: A write by an external agent reaches the user as a visible proposal before it is applied, in 100 % of cases.

## Assumptions

- The user has a compatible external agent installed and reachable on their machine. The preselected default is the Gemini CLI in ACP mode; Claude Agent and Codex require a Node runtime and are offered through their own launch commands.
- The external agent brings its own credentials and subscription. The app does not require an app-side API key for the external-agent path.
- The app's existing tool server remains the single source of tools. An external agent obtains access to the user's data only through it — there is no second copy of the tooling and no direct database access from the agent.
- The app's existing ask-first proposal pipeline is the only path for writes to user data, and it is reused unchanged.
- Two tools remain outside the ask-first pipeline as declared exceptions, each with a stated reason: `update_adr` and `write_adr` — they write **decision records**, not user data, so a proposal would be noise about the app's own bookkeeping. The registry in `config/docs/tool-surface.json` is the authority: 28 read-only, 13 asks-first, 2 direct-write exceptions. Tag creation is *not* an exception — it is an asks-first tool.
- A permission request that the user does not answer is declined after 5 minutes — the app fails closed rather than waiting indefinitely.
- The legacy label-based profile argument keeps working, because already-distributed tool-server manifests depend on it; its use is deprecated and documented, not removed.
- The delivery mechanism for the tool server inside a packaged desktop build is chosen by a reproducible probe run against a real packaged build, and exactly one mechanism is implemented as a result.
- Part A and Part B have no dependency on the external-agent feature and are merged first; Part C follows.
- The desktop application is the only target for this feature; the mobile application is unaffected and must not gain a dependency on it.
- The app-as-agent role (the application serving an IDE over the same protocol) is a Stage 2 topic, as are an in-process HTTP tool server and the protocol's v2 draft.
- The external agent's own provider cost is outside this feature's scope and is not accounted for.
- **Closed gap, recorded rather than deleted**: the summary's field policy *was* length-based, so a short, single-line free-text value — a one-line note body — was quoted in a card. It is field-based now: a value is quoted only when its field identifies, and every other string is elided however short it is. The rule and its reason are in `openspec/changes/the-summary-names-only-what-identifies`; T062 carries it.
