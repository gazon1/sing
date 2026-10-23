---
title: "External agent in the app: the client role, and the tool access it is given"
date: 2026-10-23
status: accepted
status-was: open
tags: [ai, acp, mcp, process, safety, contracts]
---

# External agent in the app: the client role, and the tool access it is given

## Context

The application already has the half of this that is hard to build. There is a tool server
that exposes the user's tasks, notes, projects and tags to agents. There is a proposal
pipeline: a write to the user's data becomes a proposal, the user decides it, and only then
is it written. There is a kill switch that removes tools from the agent's reach. There is a
tool-call log. All of it lives in one place, and all of it works.

What the application does not have is the other half: it cannot talk to a third-party agent.
A user who pays for an agent that plans their week has to run it in a terminal, against a
different view of their work, and cannot see what it did.

The obvious way to close that gap is to give the external agent its own copy of the
application's tooling. That is the way that loses. Two copies of domain access means two
copies of the kill switch, two copies of the tool-call log, and two copies of the rule that
a write asks first — and the second copy is the one nobody updates.

There is also a visibility problem that exists today, before any external agent. The built-in
agent calls tools, and the user sees prose. The code states this as a principle: tool calls
and tool outcomes are the conversation's business, not the reader's. That principle was
correct when the chat screen only answered questions. It stops being correct when the
question is about the user's own data, because then "what did you just do to my tasks?" has
no answer on the screen.

And there are three defects in the ground the external agent would stand on, all present
today:

1. The startup database probe deletes the database file on *any* error, not only on proof
   that the file is not the application's.
2. The tool server's profile argument is a label; an unknown value is silently ignored, and
   the second process reads and writes the application's stored active profile — a
   preferences store that takes a file lock.
3. No tool records how it writes, so "all writes ask first" is a belief rather than an
   enforced fact.

## Idea

**The conversation and the tools travel on different protocols.** The Agent Client Protocol
carries the conversation with the external agent. The application's existing tool server
carries the tools. The application hands the agent a launchable reference to the tool server
it already runs, and the domain access stays exactly where it is.

**Activity is part of the answer.** A turn produces events, not text: text as it arrives,
tool calls with their outcome, the plan. The vocabulary is small — four kinds — and it is
the same vocabulary for every agent, built in or external.

**Ambiguity is not evidence.** A probe that cannot tell whether a file is the application's
must not delete it. A request for a profile that does not exist must fail rather than fall
back.

## Decision

**The application is the ACP client.** It launches a third-party agent, holds the
conversation, shows the work, and owns the lifecycle. The opposite role — the application
acting as an agent for an editor — is deferred: it needs a second distributable and serves a
narrow audience.

**The first-party protocol library is used, not the Koog bridge.** `com.agentclientprotocol:acp`
at an exact pin. The Koog bridge is the agent-side half and pins an older protocol version;
it belongs to the deferred stage. Three files reach the library, and only one of them
drives the protocol: `AcpClient` owns the conversation, while the session operations and
the event mapping speak the protocol's types without touching its transport. An upgrade is
therefore three files, not a codebase.

**The tool server is the only route to the user's data.** The agent receives a standard
input/output reference — the transport every agent must support — naming the command, its
arguments and its environment. The absence of a tool server is an empty reference list, not
a failed connection: the session connects and the application warns the user, before the
first message, that the agent has no access to their tasks.

**The delivery mechanism is probed, not chosen.** Whether the packaged application's
classpath can start a second main class is not knowable without looking at a real packaged
build. The packaging is probed first, the probe's output is recorded here, and exactly one
mechanism is implemented on the strength of it.

The probe (`scripts/probe-packaged-tool-server.sh`, run against a real `.deb`) answered two
things. The first is a fact about the packaging: **the packaged runtime carries no `java`
binary** — jpackage's runtime image holds only `lib/runtime/{conf,legal,lib,release}` — so
"run the bundled JRE" is not available, and a mechanism built on it would work on the
developer's machine and nowhere else. What the image does carry is the application. So the
mechanism is: **the agent is handed the application's own executable**, with the tool
server's flag and the profile it must serve:

```
/opt/singularity-todo/bin/singularity-todo --mcp-server --profile-id=01M…
```

The tool server is the application asked for a different job — the same argument the
`fire-reminder` systemd unit already uses, and for the same reason: a helper binary is
work that silently does not happen on every machine where nobody remembered to install it.

What that forced is recorded in the consequences: the server's implementation moved into
the shared JVM runtime so that the application's own classpath carries it, and the packaged
image needed no copy of the tool server at all — there was nothing to copy, because the
server *is* the application.

The probe's second answer was a defect rather than a fact, and it is recorded below as
its own decision.

**Standard output is the protocol channel.** A stdio server's client parses every line of
standard output as a message, and the MCP specification says in one sentence that a server
*must not* write anything there that is not a valid MCP message. The server was breaking
that rule twice over: its own log lines, and a start-up banner from a third-party logging
library that initialises itself on first use, both landed on standard output. No test could
see it, because the tests spoke to the server in-process rather than over a pipe. The
server now takes standard output for the protocol and moves everything else — its own
logging, a library's banner, a stray `println` — to standard error, in one place, before
anything else runs.

**Cancelling is the application's decision, not the agent's.** The protocol's cancellation is
a *request*, and an agent may ignore it. The manual pass found one that does: Claude Code's
bridge answers `session/cancel` with "method not found" and carries on working. So the screen
asks, gives the turn a few seconds to end by itself, and ends the agent if it does not. A
reader who pressed cancel must not watch the turn continue — and the alternative, trusting
the agent, would make the button a decoration on exactly the agents that need it least.

**The handshake is bounded.** Connecting shows a spinner, and the screen offers no way out of
that state while it is connecting — so an agent that never answers would leave the reader
looking at a spinner for ever. The handshake therefore has a deadline, and the deadline is a
failure the reader can act on rather than a cancellation: the screen says the agent did not
answer and offers a way back. The bound is generous, because an agent may be an `npx`
invocation downloading its package on first use, and a timeout that fired during a download
would be a timeout that fired for the wrong reason.

**The goodbye is bounded too.** The protocol's "close the session" is a *request*, and a
request waits for an answer. The protocol library puts no deadline on it, so an agent that
receives the request and never answers it — one that is wedged, one that has already lost
the thread — leaves the caller waiting for ever. This is not hypothetical: the fake agent
used by the client's own tests did exactly that, and the client's test hung on it rather
than on anything interesting. The same reasoning as the handshake applies, with one
difference: the goodbye's answer is a courtesy, not a step the shutdown depends on. The
transport and the process are closed either way, so the wait gets a short deadline and
then the teardown continues regardless. A disconnect that never finishes leaves the screen
in the state it was leaving.

**A dead agent is a failure, not a cancellation.** The protocol reports a dead agent by
cancelling its pending requests — that is how the library says "the process is gone". A
cancellation, though, is the caller's own signal to stop, and the two must not be confused:
the first is a failure the reader has to be told about, the second is a cancellation that
has to keep travelling. So the client translates the protocol's cancellation into a failure
of its own (`AgentUnavailable`), and it tells the two apart by asking the caller's own job
whether it is still active. Without that translation a dead agent leaves the screen
spinning for ever, and a cancelled screen looks like a broken agent.

**The bridges are named by their maintained scope.** The Claude Code and Codex bridges were
published under `@zed-industries` and have moved to `@agentclientprotocol`, which is where
the protocol's own maintainers publish them. The old packages still resolve, but npm marks
them deprecated and no version will land there again, so the registry names the scope that
will still be maintained. This was found by looking at the published packages, not at a
guess: the registry is the one place where a wrong package name is a feature that silently
does not work.

**The profile is passed explicitly.** The tool server gains a profile-id mode that works
against a profile given on the command line without opening the application's stored
active-profile state. The legacy label argument keeps its meaning, because client manifests
in the wild depend on it, and is marked deprecated rather than repurposed. An unknown id is
an error, never a fallback.

**Every tool declares how it writes.** The registry carries a write behaviour with no
default value, so a tool added without choosing one does not compile. The direct-write
exceptions are named and carry their reasons in the registry, where the guarantee can be
audited without reading tool code.

**The database probe is fail-safe.** It deletes only a readable, valid database that lacks
the application's bookkeeping marker. Every ambiguous outcome leaves the file and its
contents untouched.

**Ownership of the process tree.** The tool server the agent launches is a grandchild of the
application. Closing a session ends the tree — descendants first, deepest first — with a
grace period before anything is killed outright, and closing twice is safe. The protocol's
own "close the session" call is attempted, with a deadline of its own, and never relied
upon: it is unstable and available only when the agent declares the capability.

**The agent's process is owned from the moment it is launched.** The handshake can take a
minute, and the process exists for all of it. So the session is held before the handshake
rather than after it, and a connect that is cancelled — the reader navigated away — ends
the agent rather than leaving a third-party process running with the user's credentials and
the application's data directory open. The teardown of a connection that never completed is
a kill, not a goodbye: there is no conversation to close politely.

**The urgent teardown does not consume the graceful one's escalation.** Asking a process
tree to leave and making sure it left are different acts. The screen's teardown and the
session's teardown both reach the tree, in whatever order they arrive, so the two paths
have separate flags: the urgent path asks, and the graceful path still escalates to a
force-kill afterwards. One flag for both meant that a tree asked politely once could never
be force-killed, and an agent that ignores the polite signal — one real bridge does —
outlived the session that ended it.

**A turn's outcome belongs to that turn.** A turn that was stopped keeps running for a
moment — the agent is asked to stop, and the request is a request — and it can end after
the next turn has begun. Its outcome is that turn's. So every outcome carries the turn it
came from, and a turn that is no longer the current one cannot write to the state; and the
next turn waits for the previous one to finish before it starts, because two prompts on one
session are two conversations.

**The probe's companions go only when the database has gone.** A write-ahead log holds the
transactions that were committed but not yet checkpointed, so removing it while the database
survives loses committed data. The delete can fail while the file remains — a file held
open by a running application, a read-only file — so the rule is checked rather than
assumed: the database is deleted first, and the companions follow only if it is gone.

**The tool server fails in one place.** Every start-up step that can fail says so on the
protocol channel and exits with a code — including the one that resolves the tool list. A
dependency that cannot be built is a start-up failure, and a client waiting for an answer
needs a reason rather than a closed connection.

**The active profile has a file of its own.** The rule is that a process told which profile
to serve never opens the application's stored active-profile state — and the key used to live
in the settings file, next to the appearance and the notification settings, which is a file
the tool server opens for the AI configuration. The server never *read* the key and never
*wrote* it, but a rule about not opening a file is broken by opening the file, and a rule
satisfied by an intention rather than by a fact is not a rule. So the key moved to
`profile_state.preferences_pb`, which holds nothing else, and a process told which profile
to serve never resolves that store at all.

**A process told which profile to serve answers the one setting the tools need with its
default.** The tools ask for their staleness threshold through the narrow port rather than
through the whole settings repository, and a fixed-profile process answers it with the
default rather than pulling the store in with it.

**The trust contours stay separate.** The agent's permission requests govern the agent's
own tools. The proposal pipeline governs the application's writes to the user's data. The
direct-write exceptions are three named tools. None of the three gates the others, and the
user is never shown one while being asked about another.

## Rationale

**One copy of domain access.** Every guarantee the application makes about the user's data —
the kill switch, the log, "a write asks first" — is a guarantee about *one* place. A second
route to the same data is a second place where the guarantee can be forgotten, and nobody
would notice until a tool wrote without asking.

**stdio because it is the only transport everyone has.** The network transports are
conditional on a capability an agent may not declare. Standard input/output is required of
every agent, so the reference the application hands over is one every agent can use.

**The probe first, because the two answers lead to different implementations.** Choosing
"the classpath probably works" and finding out otherwise on a user's machine, after
packaging, is the expensive way to learn it. The probe is a repository artifact, so the
answer is reproducible rather than a memory of one machine.

**A probe that deletes on doubt is the worst kind of probe.** A corrupted file may be the
user's only copy. A locked file is the user's data, busy. The cost of leaving a stale file
is one confusing startup; the cost of deleting the user's data is the user's data. And the
guarantee that matters is about *contents*, not about the file's existence — a test that
checks only that the file is still there would pass against a probe that empties it first.

**A write classification with no default.** The failure mode of a default is a tool that
quietly writes. The failure mode of no default is a red build, and a red build is a
conversation rather than an incident.

**The profile state gets a file of its own.** The isolation was asserted, not established:
the server did not read the key, and the server opened the file the key was in. A
guarantee about a file is either true or it is not, and the only way to make it true was
to put the key somewhere the server never opens. The alternative — leaving the key where
it was and saying the server does not *use* it — trades a guarantee for an intention, and
the intent is not checkable by a test.

**Explicit "not supported" over silent capability.** The protocol library supplies default
behaviours for the file-system and terminal operations that refuse when the corresponding
capability was not advertised. The application advertises neither and writes neither, so
the refusal is the library's own and cannot drift.

**Four kinds of event.** The vocabulary is small enough to enumerate, which makes the
mapping from a protocol's many session updates testable by exhaustion — including the
variants deliberately dropped. An event bus, a hierarchy of adapters and a general
"platform for activity" would each add a lifecycle to own and a leak to debug, in exchange
for flexibility nothing here uses.

**Failure is not success.** A turn whose stream dies is failed. A turn that ends because the
process crashed and a turn that ended because the agent finished are different facts, and
the difference is exactly what the reader needs to know.

**A process that exists is a process that has to end.** The handshake is a window in which
the agent is running and the application has nothing to show for it. Ownership that begins
when the connection succeeds is ownership that begins too late, and the reader who
navigated away is the only one who could end it.

**Two prompts on one session are two conversations.** The protocol does not say what a
second prompt means while the first is running, and the reader cannot tell which turn an
outcome belongs to. So the second turn waits for the first to end, and each outcome says
which turn it came from.

**Asking and making sure are different acts.** A teardown that has to happen now cannot
wait, and a teardown that may wait should not be disabled by the first one. A process that
ignores the polite signal is exactly the case where the difference shows.

## Consequences

**The application's own tooling becomes the external agent's tooling.** The external agent
can read the user's tasks and propose changes to them. That is the point, and it is also why
the trust contours are stated so precisely.

**The tool server must be launchable from the packaged application.** It is, and the way
it is launchable decided where it lives. The server's implementation moved from
`:mcp-server` into the shared JVM runtime (`shared/src/jvmMain`), so that the desktop
application's own classpath carries it; the `:mcp-server` module became a one-line
launcher over the same function, so the published fat jar, its `mainClass` and the
editors that spawn it are unchanged. Two copies of the server would be two toolsets that
drift, and the drift would only show up as "the agent cannot see the tool the
documentation promises".

The packaged distribution therefore contains no second copy of the tool server. There was
nothing to copy: the server is the application, asked for a different job.

**The manual pass is the only thing that proves the delivery.** A packaged `.deb` was
built, unpacked, and its own executable was launched in tool-server mode: it registered
every tool, negotiated the protocol, answered a read, and turned a write into a proposal.
Then a real agent — Claude Code, over the protocol — was connected to that packaged
server, asked for a read, asked for a change, and closed. The agent's change came back
as a proposal, and a follow-up read found no task: the guarantee the whole feature rests
on, checked against a real build and a real agent rather than a fake.

The same pass is where the three findings above came from. A fake would have passed all
of them.

**The mobile build does not gain this.** The protocol library needs a newer bytecode level
than the Android target uses, and the application cannot spawn a process on a phone. All of
it stays on the desktop side, and the activity vocabulary is shared with the built-in agent
so that the Android chat does not lose anything.

**The built-in chat changes shape.** Cards appear in a screen that previously showed prose
only. This is a visible change to a screen people use daily, and it is the change most
likely to draw an objection — which is why the cards are the shared presentation, and why
the failed call is shown as failed rather than omitted.

**The protocol library is a moving target.** It is a 0.x release with a new version every
few weeks. The exact pin and the single touch-point file are what make that affordable.

**The external agent's cost is not surfaced.** The protocol carries usage and cost, so this
is a scope decision rather than a protocol limitation — recorded here so it is not
rediscovered as an accident later.

**The staleness threshold in a fixed-profile server is the default.** Twenty-one days,
whether or not the user chose something else. The alternative was to open the
application's settings store from a process whose whole reason for existing is to stay
away from the user's stored state. The tools' narrow port is what made the choice
available at all: a tool that asked for the whole settings repository would have pulled
the store in with it, and the isolation would have been a claim rather than a fact.

**The active profile moved to its own file.** `profile_state.preferences_pb` holds the
active profile id and nothing else. A user who ran the previous version has the key in
the settings file, so the first read after the move looks in the new file, and if the key
is not there it looks in the old one once, moves it, and takes it out of the old file. A
user who never ran the previous version has nothing to move, and the old file is not read
at all. The move is conditional: a profile the user switched to in the meantime wins, and
the value being moved is only a fallback.

## Links

- `openspec/changes/acp-client-data-safety` — the probe, the profile contract, the write
  classification
- `openspec/changes/agent-activity-visibility` — the activity vocabulary and the cards
- `openspec/changes/acp-external-agent-desktop` — the client, the process tree, the screen
- `specs/014-acp-client-agent-activity/` — the specification, the plan, the research, the
  contracts, the tasks
- `docs/decisions/2026-09-18-profile-subsystem.md` — already intended "route to any profile
  via an argument"; this decision realises it
- `docs/decisions/2026-10-07-closing-child-streams-sends-sigpipe.md` — why process
  spawning is confined to one file, and what the earlier defect looked like
- `openspec/specs/write-pipeline` — the proposal pipeline the external agent's writes use
- `scripts/probe-packaged-tool-server.sh` — the packaging probe that answered the
  delivery-mechanism question against a real `.deb`
- `docs/architecture/acp-client.md` — what the client does, in the code's own terms
