---
title: "The agent's surface says what it does: a published classification, a guarded lifecycle, an explicit protocol channel"
date: 2026-10-23
status: accepted
tags: [ai, acp, mcp, contracts, architecture]
---

# The agent's surface says what it does

## Context

The external-agent change shipped three claims about the surface it added:

1. **All writes ask first.** A tool that changes the user's data proposes it; the user
   confirms; only then is it written. Two tools write directly, and they say why.
2. **The client's lifecycle is safe.** The connection can be closed, and closing it ends
   the agent and everything it started.
3. **The protocol channel is the protocol's.** Nothing but an MCP message reaches the
   client's standard output.

Each claim was checked, and each was found to be weaker than it looked.

**The write classification was a belief.** The catalogue declared how each of the
forty-three tools writes, and the declaration was the only copy of the fact — it lived in
Kotlin and in nowhere else. Nothing compared the declaration with the tools. A gate used
to compare them, against a text file, and it was deleted by a later change that converted
the tools to the proposal path — without updating the catalogue. So `create_tag` was still
declared a direct writer while the tool had been proposing for a while. The declaration
was the stale half, and nothing noticed.

The behavioural side had the same shape: two of the thirteen tools that proposed were
driven through the whole round trip — propose, the data unchanged, confirm writes, decline
changes nothing. The catalogue called twelve of them proposal-gated; the thirteenth was
`create_tag`, whose declaration had not caught up with the tool. The other eleven had no
such test. A tool that proposes and then writes anyway passes every test that only looks
at the confirmation path.

**The client's lifecycle was unsynchronised.** `AcpClient` holds four mutable fields — the
transport, the protocol, the client and the session. `connect()` sets them, `close()`
clears them, and nothing synchronises the two. A `close()` that landed between two
assignments would leave a session bound to a closed transport. No reachable path does
that today: the screen only closes a session it already holds. It is latent, not live.

**The protocol channel was a side effect.** The rule "standard output is the protocol's"
was true because one function called `System.setOut` and kept the old stream. Every
caller that wanted to write the protocol had to know that a stream had been kept
somewhere. The application's own log reached standard error as a side effect of the same
redirect, so the one thing the repository controls leaned on an arrangement nobody could
see in the logging code.

And two classes had grown past the shape they were written for: the screen's ViewModel
held four concerns, and the tool server's entry point ran twelve start-up steps in one
function, with the two refusals buried as `exitProcess` calls in the middle of it.

## Idea

Three declarations, one per claim, and the classes they were hiding in.

- A declaration that is **published** can be compared with anything. A declaration that is
  **enforced** cannot be wrong quietly.
- A lifecycle with a **lock** has one state at a time, and a race has a test.
- A channel that is **a value** is a contract; a channel that is a side effect is an
  arrangement.

## Decision

**The write classification is published and enforced.**

The exported tool surface carries each tool's write behaviour and, for a direct writer,
the reason it sits outside the proposal pipeline. The export's own version is bumped; the
surface *contract* version is not, because a new field in the export is not a change to
what an agent reads over the wire.

The catalogue refuses to load when its declaration is malformed: a direct writer without a
reason, or a reason on a tool that is not a direct writer. The check is in the catalogue
rather than in a test, because the catalogue is what everything else reads.

The comparison with the tools' behaviour is two-sided, and it names what it cannot see. A
tool the scan sees writing, that the catalogue does not call an exception, is a finding. A
tool the catalogue calls an exception, that the scan sees no writing in at all, is a
finding too: a stale exception is a promise nobody keeps.

What the scan cannot see is *whose* data a write touched. It casts two nets — one that
recognises a write on a repository of the user's own data, and a wider one that catches a
write whose method is named like a write whatever port it goes through — and only the
first can place a write on the user's data. An exception whose write the wider net sees
and the first cannot is therefore *named* among the exceptions the check could not verify,
and the naming is what keeps the check honest: a check that fails on what it cannot see
teaches its readers to ignore it, so the named ones are exempt from that branch rather
than failed by it. The name is a claim that somebody has to argue for, not a way to
silence a finding: an exception the check cannot verify and that nobody has named is a
finding, because then the check is failing on what it cannot see for no reason.
An unchecked write has to be a visible one.

Both nets are name-based, and the record says so rather than claiming more: a write
through a method whose name is not among the verbs the nets hold is invisible to them,
and a write that belongs to no tool class is seen but cannot be attributed — the scan
cannot say whose write it is — which is why a write that belongs to no class is a
finding of its own rather than a gap. The reach is what it is, and a gate that claims
more than it can see is the thing this change is against.

The round trip — propose, unchanged, confirm writes, decline changes nothing — is now
every proposal-gated tool's contract, not two tools' contract.

**The client's transitions are mutually exclusive, except the one that cannot wait.**

`connect()` and `close()` take a lock; the fields that are read outside it are read safely.
`terminate()` *tries* that lock rather than taking it, because it cannot suspend — one of
its callers is a ViewModel being cleared — and because it exists to end a conversation
that is not ending: a terminate that waited for a close that was waiting for an agent
would be a terminate that never fires. A `Mutex` rather than a `synchronized` block,
because the transitions are suspending. The test is the race itself: a turn in flight, a
close during it, and an assertion that the client ends in one state or the other.

**The protocol's output is a value.**

A type owns the protocol's stream and is passed to everything that writes the protocol. A
writer that holds it cannot write anywhere else; a writer that does not hold it cannot
reach the protocol. The logger is pointed at standard error by its own configuration, so
the application's log does not depend on the global redirect. The redirect stays as a
defence against a third-party library that prints to standard output without asking — but
it is a defence now, not the contract.

**The two classes are split by concern.**

The screen's ViewModel keeps the MVI surface and delegates to three collaborators: the
conversation (the agent's process and the session), the turn (its clock, and the rules
about when it may write to the screen), and the permission handshake (a question with a
deadline). The tool server's entry point becomes parse, start, serve; the start-up returns
either a server to serve or a refusal with the code and the message the client should see,
so the refusals are values rather than `exitProcess` calls buried in a sequence.

## Rationale

**Why publish the classification rather than only enforce it.** An enforced declaration
that only exists in Kotlin can still be wrong in a way nobody sees: the enforcement says
the declaration is *well-formed*, not that it is *true*. The export is what lets a
document, an agent, and a check read the same sentence.

**Why the catalogue checks itself.** The rule was written down in the catalogue's own
documentation and enforced nowhere. The first thing the absence bought was a tool that had
been converted to the proposal path while the catalogue still called it a direct writer.
A rule that is written down and not checked decays the moment somebody is in a hurry.

**Why over-declaring is named rather than failed.** Under-declaring is dangerous: a tool
that writes where the catalogue says it proposes is a change the user never saw. Over-
declaring is not: a catalogue that calls a tool a direct writer when it no longer writes
is a stale sentence, not a hole. So the failure falls on the dangerous direction, and the
harmless one is named — a named exception is a visible one, and the name is what a reader
checks.

**Why a `Mutex` and not a lock around the whole client.** The fields are read from code
that does not hold the lock — `prompt()` is not a suspend function — so the lock cannot
protect those reads. A `Mutex` protects the transitions; the volatile read protects the
rest. Anything else would be a lock that appears to cover more than it does.

**Why the redirect stays.** The MCP specification says a stdio server must not write
anything to standard output that is not a valid MCP message. The known offender was the
application's own log; the possible offender is a third-party library's start-up banner,
which no configuration of ours can reach. Removing the redirect would trade a known
problem for an unknown one. Keeping it, and pointing the logger at standard error
explicitly, means the thing we control does not depend on it.

**Why the classes are split rather than reorganised.** A god class is not a class that is
too long; it is a class that is about several things, so a change to one of them is a
change to the others' context. The split is by concern, and the collaborators own the
state that belongs to their concern — the turn owns the turn's clock, the conversation owns
the session. The ViewModel keeps what a screen is about: what the reader sees.

## Consequences

- The exported tool surface carries two fields per tool. Anything that reads it has to
  tolerate them — the format version is what says so.
- The catalogue can no longer be loaded with a malformed entry. That is the point, and it
  is a crash at start-up rather than a warning, because the entry would be a promise the
  user cannot read.
- `create_tag` is a proposal-gated tool now, in the catalogue as well as in the code.
- The exceptions the source scan cannot verify are a named list in the test. A tool whose
  write the scan sees but cannot place on the user's own data has to be added to it —
  deliberately, with a human looking. A tool whose write the scan does not see at all has
  to be made visible to the scan first: a name cannot stand in for a write nobody can find.
- The client's `connect` and `close` are now mutually exclusive; a caller that used to
  rely on two of them overlapping is now serialised. `terminate` is deliberately not: it
  ends the conversation whatever the lock is doing, and clears the fields itself when no
  transition is running.
- The protocol's stream is a `ProtocolStdout` rather than a `PrintStream`. Everything that
  wrote the protocol now says so in its signature.
- The ViewModel's collaborators are constructed inside it, not injected. They are part of
  the screen's behaviour, not services the screen asks for; injecting them would add a
  binding per class and change nothing about what they do.

## Links

- `openspec/changes/agent-surface-follow-ups/` — the change this decision belongs to.
- `docs/decisions/2026-10-23-acp-client-and-agent-activity.md` — the decision behind the
  external agent, and the reviews whose findings this one finishes.
- `docs/architecture/acp-client.md`, `docs/architecture/mcp-server.md` — the architecture
  notes this decision changes.
