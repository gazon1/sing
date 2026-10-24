# ACP client

> **Reference, not a procedure.** This describes the current state of the external-agent
> feature: how the application hosts a third-party agent, what that agent is allowed to
> reach, and what the application does not do. It goes stale whenever the code changes, so
> a change to it updates this file in the same commit.
>
> The decision it implements is ADR `2026-10-23-acp-client-and-agent-activity.md`. The
> argument contract it shares with the tool server is
> `specs/014-acp-client-agent-activity/contracts/tool-server-cli.md`. The tool server
> itself is described in `docs/architecture/mcp-server.md`.

## The role: the application is the client

The Agent Client Protocol (ACP) has two ends. The **agent** answers prompts; the
**client** hosts it, holds the conversation, and decides what the agent may do. This
application is the client. It launches a third-party agent — the Gemini CLI in its ACP
mode, or the Claude Code / Codex bridges — as a child process, speaks ACP to it over
that process's standard input and output, and shows the reader what the agent did.

The opposite role — the application acting as an agent for an editor — is deliberately
deferred. It would need a second distributable and serves a narrow audience.

The agents the application offers are data, not behaviour
(`shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentProfile.kt`):

| Entry | Command | Note |
|---|---|---|
| Gemini CLI | `gemini --experimental-acp` | The default: one installed binary, nothing else to arrange |
| Claude Code | `npx @agentclientprotocol/claude-agent-acp` | A bridge; needs a Node runtime |
| Codex | `npx @agentclientprotocol/codex-acp` | A bridge; needs a Node runtime |

A registry entry is an executable and a list of arguments, never a shell string. The
exact command is shown to the user before it runs: an external agent is a third-party
program the user installed, and a command that cannot be seen cannot be checked.

## The SDK pin

The first-party protocol library is used, not a bridge to another framework:

```
com.agentclientprotocol:acp:0.34.0
com.agentclientprotocol:acp-model:0.34.0
```

Both are pinned exactly, in `gradle/libs.versions.toml` (`acp = "0.34.0"`, with
`acp-model` taking the same version ref). The pin is exact on purpose: this is a 0.x
SDK — ten releases existed in the version list when 0.34.0 was adopted — so a version
range would let a minor bump change the wire format underneath. Bump it deliberately,
one version at a time.

Only the v1 packages are used (`com.agentclientprotocol.model`, not `model.v2`): v2 is
draft, and the types this feature would want from it are `@UnstableApi`.

The dependency is **JVM-only**, declared in the `jvmMain.dependencies` block of
`shared/build.gradle.kts`:

```kotlin
jvmMain.dependencies {
    // Agent Client Protocol — the client half, used by the external-agent
    // screen. JVM-only on purpose: the SDK needs JDK 21, and this project
    // keeps the Android target at JVM_11 bytecode. Nothing in commonMain or
    // androidMain may depend on it — the mobile build must not acquire this.
    implementation(libs.acp)
    implementation(libs.acp.model)
}
```

The SDK needs JDK 21; the Android target stays at JVM_11 bytecode. Nothing in
`commonMain` or `androidMain` may depend on it — the mobile build must not acquire a
dependency it cannot use.

Three production files import the library, all under
`shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/`: `AcpClient.kt` (the
protocol conversation), `AcpClientSessionOperations.kt` (what the agent may ask of this
application), and `AcpEventAdapter.kt` (the mapping from protocol updates to the
activity vocabulary).

## The lifecycle: process → protocol → session → turn → close

```
ViewModel                    AcpClient                     agent process
    │                             │                              │
    │ launch ───────────────────► │ spawn duplex process        │
    │                             │ ProcessTree(root)           │
    │ connect() ─────────────────► │ StdioTransport ────────────► │
    │                             │ Protocol.start()            │
    │                             │ initialize(ClientInfo) ────► │
    │                             │ newSession(McpServer.Stdio)► │
    │ prompt(text) ─────────────► │ session.prompt(...) ───────► │
    │                             │ ◄──── AcpTurnEvent.*        │
    │ close/terminate ──────────► │ session.close()             │
    │                             │ tree.close() / destroyNow() │
```

**The process.** `SubprocessAcpAgentLauncher` spawns the agent with
`Subprocess.spawnDuplex(...)` and wraps the handle in a `ProcessTree`
(`shared/src/jvmMain/kotlin/com/singularity/todo/core/process/`). The tree is what
makes closing meaningful: an agent is a program that starts programs, and a child that
outlives its parent is exactly the leak the tree exists to prevent. `ProcessTree.close()`
asks the deepest descendants to leave first, then the root, waits out a grace period
(2 seconds), and kills whatever is left (given another 500 ms to actually die).
`destroyNow()` is the impatient version, used when there is no scope left to wait in.
Closing twice is not an error.

The two paths have **separate** flags. `destroyNow()` asks the tree to leave and does not
wait; `close()` asks, waits, and escalates to a force-kill. One flag for both meant that
the impatient path consumed the graceful one's chance to run — so a tree that had been
asked politely once could never be force-killed, and an agent that ignores the polite
signal outlived the session that ended it. The screen's teardown and the session's teardown
both reach the tree, in whatever order they arrive, and neither may disable the other.

**The process is owned from the moment it is launched.** The handshake can take a minute,
and the agent is running for all of it. The ViewModel therefore holds the session
*before* `connect()` rather than after it, so a connect that is cancelled — the reader
navigated away — ends the agent instead of leaving a third-party process running with the
user's credentials and the application's data directory open. A connection that never
completed is killed rather than closed politely: there is no conversation to close.

**The protocol.** `StdioTransport` over the process's own pipes, then
`Protocol(scope, ioTransport)` and `ioProtocol.start()`. The client then calls
`initialize(...)` with a `ClientInfo`, and opens a session.

**The session.** `newSession(...)` is created with the tool server as a stdio MCP
reference — see the trust boundary below. The session is what a turn is run against.
The handshake — initialize, then the session — is bounded: connecting shows a spinner,
and the screen offers no way out of that state, so an agent that never answers would
leave the reader looking at a spinner for ever. A deadline that fires is reported as a
failure with a way back, not as a cancellation.

**A dead agent is a failure, not a cancellation.** The protocol library reports a dead
agent by cancelling its pending requests. That cancellation is not the caller's own — the
caller's is the screen going away — and the two must not be confused, because one is a
failure the reader has to be told about and the other is a signal that has to keep
travelling. `AcpClient.connect()` therefore translates the protocol's cancellation into
`AgentUnavailable`, and tells the two apart by asking the caller's own job whether it is
still active. Without that translation a dead agent leaves the screen spinning for ever.

**The turn.** `prompt(message)` returns a `Flow<AcpTurnEvent>`. The flow ends when the
turn ends, and **a turn ends when the agent sends its completion event, not when the
stream ends**. The stream also ends when the process dies, when the transport breaks,
and when the reader cancels — none of which is a completion. A turn whose stream dies
without a completion event is a failure (`AcpTurnEvent.Failed`), never a success. The
protocol's stop reasons are mapped to outcomes by `AcpEventAdapter.outcomeFor`: a turn
that hit a limit (`MAX_TOKENS`, `MAX_TURN_REQUESTS`) *succeeded* — the agent answered,
and the answer was cut short, which is a caveat and not a failure. A `REFUSAL` is a
failure.

**A turn's outcome belongs to that turn.** A turn that was stopped keeps running for a
moment — the agent is asked to stop, and the request is a request — and it can end after
the next turn has begun. So every outcome carries the turn it came from, and a turn that
is no longer the current one cannot write to the state; and the next turn waits for the
previous one to finish before it starts, because two prompts on one session are two
conversations and the protocol says nothing sensible about the second one.

**The close.** The process tree belongs to whoever started the conversation, and that is
the screen's ViewModel — `AcpExternalAgentViewModel` holds the `AcpAgentSession`, which
holds the tree, and registers a closeable that calls `session?.terminate()`. The
closeable runs *instead of* the scope, not through it: the scope is closed first, so a
coroutine launched there would never run. The protocol's own "close the session" call is
attempted and never relied upon — it is unstable and available only when the agent
declares the capability.

That call is also *bounded*. It is a request, and a request waits for an answer, and the
protocol library puts no deadline on it — so an agent that receives `session/close` and
never answers it (a wedged agent, one that has already lost the thread) would leave the
disconnect waiting for ever, with the screen stuck in the state it was leaving.
`AcpClient.close()` gives the answer a short deadline (`CLOSE_TIMEOUT`, five seconds)
and tears the transport and the process down either way: the answer is a courtesy, and a
shutdown that waits for a courtesy is a shutdown that never finishes. The client's own
test agent had exactly this defect — it received the goodbye and never answered it —
and the client's test hung on it rather than on anything interesting, which is how the
gap was found.

**The cancel.** The protocol's cancellation is a *request*, and an agent may ignore it.
The manual pass found one that does: Claude Code's bridge answers `session/cancel` with
"method not found" and carries on working. So the screen asks the agent to stop, gives
the turn `CANCEL_GRACE` (five seconds) to end by itself, and ends the agent if it does
not — a reader who pressed cancel must not watch the turn continue. The agent is asked
first, because a request that is answered and then abandoned is a request the agent never
got an answer to, and the agent is the one that has to know.

## The trust boundary

Two contours, and they do not gate each other.

**The agent's own tools.** The agent's permission requests are answered by the reader
through the permission sheet — the only interactive approval in this feature
(`AcpExternalAgentScreen`, the `PermissionSheet` bottom sheet). A request suspends
until the reader answers; five minutes later it declines itself, because an unanswered
request is not a granted one. The protocol allows exactly one outstanding request per
session, so there is no queue to get wrong.

**The application's writes.** The agent reaches the user's tasks through the
application's own MCP tool server, and that server's writes go through the proposal
pipeline — a write becomes a proposal, the user decides, and only then is it written.
Every tool declares how it writes (`WriteBehavior` in
`shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt`), and
the write behaviour has no default: a tool added without choosing one does not compile.
The declaration is **published** in the exported tool surface
(`config/docs/tool-surface.json`), so a reader outside the compiler can read it, and
**enforced** by the catalogue itself: a tool that writes directly carries the reason it
sits outside the proposal pipeline, and nothing else carries a reason.

Every proposal-gated tool is driven through the whole round trip: the call proposes, the
user's data is unchanged, the confirmation writes, and the refusal changes nothing. The
refusal is the half that gets forgotten — a tool that proposes and then writes anyway
passes every test that only looks at the confirmation path.

**The application advertises neither the file system nor the terminal.**
`AcpClient.connect()` sends `ClientCapabilities()` with no `fs` and no `terminal`. The
protocol's client operations cover the file system, the terminal, elicitation, and
permissions; `AcpClientSessionOperations` implements the permissions and nothing else.
That is not an omission — the file system, terminal, and elicitation methods have
defaults in the protocol library, and those defaults **refuse**. An agent that honours
the protocol never asks; one that asks anyway is told no. An implementation that
quietly granted access would be a hole in a guarantee the application makes about the
user's files. An agent with file and terminal access would have a second, quieter way
to change the user's data, and it would not ask.

**The client's transitions are mutually exclusive — except the one that cannot wait.**
`connect()` and `close()` take a lifecycle lock, so a `close()` cannot land between two
assignments of the connection's fields and leave a session bound to a closed transport.
`terminate()` *tries* that lock rather than taking it: it cannot suspend — one of its
callers is a ViewModel being cleared — and it exists to end a conversation that is not
ending, so a terminate that waited for a close that was waiting for an agent would be a
terminate that never fires. It ends the process and the pipes whatever the lock is
doing, and clears the fields itself when no transition is running. The fields
that are read outside the lock — `prompt()` is not a suspend function — are read safely
rather than under it. The screen only closes a session it already holds, so the defect
was latent rather than live; it is closed anyway, because a latent race is one code
change away from a live one.

**One server, one toolset.** The tool server the agent is handed is *the same server
the application runs*: the implementation lives in the shared JVM runtime
(`shared/src/jvmMain/kotlin/com/singularity/todo/mcp/`), and the desktop application
starts it in its own `--mcp-server` mode. Two servers would be two toolsets that drift,
and every guarantee the application makes about the user's data — the kill switch, the
call log, "a write asks first" — is a guarantee about one place.

**A server told which profile to serve does not open the application's profile state.**
The active profile id lives in a file of its own — `profile_state.preferences_pb`, which
holds nothing else — and a server given a profile id therefore builds its profile
repository without a store: it never reads or writes the active profile, and it never
opens the file that says which profile the user is working in. The key used to live in the
settings store, next to the appearance and the notification settings; that file is one
the server opens for the AI configuration, and a guarantee about not opening a file is
either true or it is not. The tools read their one setting (the staleness threshold)
through the narrow `TasksSettingsRepository` port rather than through the whole settings
repository, which keeps the settings store out of the graph as well. The price is that a
fixed-profile server answers with the setting's default rather than the user's, and the
price is stated rather than discovered.

**The server's standard output is the protocol channel.** The server takes standard
output for the protocol and moves everything else — its own logs, a third-party
library's start-up banner, a stray `println` — to standard error, before anything else
runs (`ProtocolStdout.reserve()`). The MCP specification says a stdio server must not
write anything to standard output that is not a valid MCP message, and a client that
takes that literally stops at the first line it cannot parse. The server was breaking
that rule, and no test could see it, because the tests spoke to it in-process rather
than over a pipe. The packaged build is what showed it: a real agent connected to the
packaged server and got its tools, but only after the channel was clean.

## The check against a real bridge

The tests drive `FakeAcpAgent` — a program in this repository that speaks the protocol
over a pipe. It is ours, and it was written to answer what our client expects, so the
suite says that the client and the fake agree with each other. It says nothing about
whether they agree with the protocol, because the protocol is written by somebody else.

**The probe puts the two side by side.**

```bash
scripts/acp-bridge-probe.sh                                   # the Claude bridge
scripts/acp-bridge-probe.sh npx -y @agentclientprotocol/codex-acp
```

It starts the bridge, drives **this application's own client** against it, and prints
what came back: the verdict, the agent's name and version, the methods the bridge used
and every frame verbatim. The exit code is the answer — zero when the handshake
completed, one when it did not.

It is a script and not a test because it needs a real `npx`, a real network and a real
bridge; a test that cannot run is a skipped test, and this repository does not allow
those. It is a program (`AcpBridgeProbe`) with a Gradle task
(`:shared:acpBridgeProbe`) beside it, the same shape as `exportToolSurface`.

**What it covers.** The handshake and the session — the protocol's own layers, and the
ones this application owns. Against `@agentclientprotocol/claude-agent-acp` 0.95.0 the
client completed the handshake on the first run.

**What it does not cover.** The prompt half, and the tool call behind it: those need
the agent behind the bridge to be installed and signed in, and a bridge on a machine
without credentials says so itself (`"authStatus":{"kind":"none"}`). The tool server
half needs a profile and a database on top of that.

**What it found.** The bridge announces two notifications our fake never sent — its
authentication state right after the handshake, and its available commands right after
the session is open — and it answers `session/new` with five permission modes and
three configuration options where the fake answered with one mode and no options. The
client survived all of it. The fake now sends the same shapes, so the suite covers
what the bridge sends rather than what we taught the fake to send.

**When the transcript is read.** When the bridge goes quiet. The bridge keeps talking
after the session it answered — its available commands come on a timer, and its
authentication state comes when its own probe resolves — so a transcript read the
moment the connect returned would be a snapshot of a race. The probe waits for the
quiet, and the frames are written down when it has lasted. The bridge's own frames are
committed with the change
(`openspec/changes/the-client-meets-a-real-bridge/bridge-transcript.txt`), so the
claims about them can be checked without a network. The list of models the machine is
configured for, and the catalogue of commands it happens to have installed, are
redacted there — they are the machine's, not the bridge's — and the file says which.

## The known limitation: the agent's own token cost is not surfaced
The protocol carries usage: `SessionUpdate.UsageUpdate` arrives as a session update, and
`PromptResponse.usage` carries `Usage` (input, output, and total tokens) at the end of
a turn. **The application displays neither.**

`AcpEventAdapter` maps `SessionUpdate.UsageUpdate` to `null` — it is bookkeeping, not
activity, and an unrecognised update must not invent one. `AcpClient.prompt` reads
`event.response.stopReason` from the completion event and nothing else; the `usage`
field on the same response is not read.

This is a scope decision, not a protocol limitation, and it is recorded so it is not
rediscovered later as an accident. The application's own LLM usage tracking
(`llm_usage`, per-profile) covers the built-in Koog agent; the third-party agent bills
its own provider, and this application does not see that bill.

## Where the pieces are

| File | Role |
|---|---|
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClient.kt` | The protocol conversation: transport, initialize, session, prompt, close |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClientSessionOperations.kt` | The client side of a session: permissions, and the refusal of everything else |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpEventAdapter.kt` | The mapping from protocol updates and stop reasons to the activity vocabulary |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentProfile.kt` | The agent registry: what to launch, shown before it runs |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentLauncher.kt` | Starting a real process and building the session around it |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentSession.kt` | The interface the screen talks to, so the protocol stays behind one door |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt` | The probe: this client, against a real bridge, and what the bridge said |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModel.kt` | The state, the intents, and the screen's own logic |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpAgentConversation.kt` | The conversation: the agent's process, the session on top of it, and the process tree that ends both |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpTurnRunner.kt` | A turn: its clock, and the rules about when it may write to the screen |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpPermissionBroker.kt` | The permission handshake: a question with a deadline |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentScreen.kt` | The screen and the permission sheet |
| `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/di/AcpDiModule.jvm.kt` | The Koin bindings, composed into the desktop module list only |
| `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/Subprocess.kt` | Spawning the agent and talking to its pipes |
| `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/ProcessTree.kt` | Ending the agent and everything it started |
| `shared/src/jvmMain/kotlin/com/singularity/todo/core/mcp/McpServerCommandProvider.kt` | The command that starts the tool server for the agent |
| `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolServerMain.kt` | The tool server the agent reaches — the same one the application runs |
| `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ProtocolStdout.kt` | Keeps the protocol channel clean: standard output is the protocol, everything else goes to standard error |

## Related

- `docs/architecture/mcp-server.md` — the tool server, its `--profile-id` contract, and
  how it is started.
- ADR `2026-10-23-acp-client-and-agent-activity.md` — why the client role, why one
  server, and what the trust contours are.
- ADR `2026-10-24-the-client-meets-a-real-bridge.md` — why the client is checked
  against a real bridge by a script, and what the bridge sent that the fake did not.
- `specs/014-acp-client-agent-activity/contracts/tool-server-cli.md` — the argument
  contract the agent's tool server is started with.
