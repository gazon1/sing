# Design: The agent's surface says what it does

Related ADRs: `docs/decisions/2026-10-23-the-agent-surface-says-what-it-does.md` (the
decision this change rests on), `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`
(the decision behind the external agent whose follow-ups these are).

## 1.1 — the write classification

### Where the declaration lives

The catalogue (`McpToolCatalog`) declares how each tool writes: `READ_ONLY`,
`PROPOSAL_WRITE`, or `DIRECT_WRITE_EXCEPTION` with a reason. That declaration is the one
copy of the fact.

Two things were missing from it.

**It was not published.** The declaration lived in Kotlin and nowhere else, so a reader
outside the compiler — a document, an agent, a gate — could not see it. The exported tool
surface (`config/docs/tool-surface.json`) already carries what each tool *is*; it now
carries how each tool *writes* as well. The export's own version is bumped, because the
shape changed. The surface *contract* version is not bumped: the contract is what an agent
reads over the wire, and a new field in the export is not a change to it.

**It was not enforced.** The catalogue's own documentation says the reason is "required for
`DIRECT_WRITE_EXCEPTION` and forbidden otherwise", and nothing checked. The enforcement is
now in the catalogue itself, so a tool that breaks the rule fails when the catalogue loads
rather than when a reviewer notices.

### The declaration against the behaviour

The old gate compared a text file against the tool sources. It was deleted when the tools
were converted to the proposal path, and the claim moved into
`WriteToolsOnlyProposeTest` — which is the better home for it, because a test runs the
tools instead of reading them.

But a test that only scans the sources cannot see a declaration that is wrong. The scan
recognises a write on a repository of the user's own data; it does not place a write
through any other port on that data, and it never compares what it saw with what the
catalogue says.

So the check is now two-sided, and it reads the scan's two nets separately:

- a tool the scan sees writing, that the catalogue does not call an exception, is a finding;
- a tool the catalogue calls an exception, that the scan sees no writing in at all, is a
  finding too — a stale exception is a promise nobody keeps;
- an exception whose write the scan sees but cannot place on the user's own data is
  **named** in the output — and the naming is what keeps the check from failing on what
  it cannot see: a check that fails on what it cannot see teaches its readers to ignore
  it. The named ones are exempt from that branch; an exception the scan cannot verify and
  that nobody has named is still a finding, because the name is a claim somebody has to
  argue for. The ADR tools write a decision file through a storage port, and the scan
  cannot place that write on the user's own data — it is not the user's tasks. An
  unchecked write should be a visible one.

Both nets are name-based, and the design says so rather than claiming more: the narrow
one recognises a repository by the receiver's name, the wide one recognises a write by
the method's name, and a write whose method is called something neither list holds is
invisible to them. A write that belongs to no tool class is seen but cannot be
attributed — the scan cannot say whose write it is — and that is a finding of its own
rather than a gap. The reach is what it is, and a gate that claims more than it can
see is the thing this change is against.

This found a real drift: `create_tag` was declared a direct-write exception, and the tool
had been converted to the proposal path without the declaration following. The declaration
was the stale half.

### The contract for every proposal-gated tool

One tool — a creation — was driven through the whole round trip: the call proposes, the
user's data is unchanged, the confirmation writes, and the refusal changes nothing. The
deletion had half of one: a refused deletion left the task in place, and nothing said
that a confirmed one archives it. The other eleven proposal-gated tools had no such
test.

The round trip is the half that matters. A tool that proposes and then writes anyway, or
that deletes the row it proposed to delete, passes every test that only looks at the
confirmation path. So each of the thirteen gets the round trip, not a presence check.

`decompose_and_create` needs an LLM to produce a plan, so the test needs a fake one. The
fake lives in the test source set: a `PromptExecutor` that answers with a fixed plan, and
the model it answers for.

### What the two files that are not there

`config/docs/ai-direct-writes.txt` and `scripts/check-ai-write-tools.py` are already gone
from `main` — another change deleted them when it converted the tools. This change does not
delete them again; it finishes what their deletion started.

## 1.3 — the client's lifecycle

`AcpClient` holds four mutable fields: the transport, the protocol, the client and the
session. `connect()` sets them, `close()` clears them, and `terminate()` ends the process.
Nothing synchronises them, so a `close()` that lands between two assignments can leave a
session bound to a transport that has been closed.

The fix is a lock around the transitions and a volatile read for the fields that are read
outside it. A `Mutex` is the right instrument: the transitions are suspending, and the
reads that are not (`prompt()` is not a suspend function) need a volatile read rather than
the lock.

The test is the race itself: a turn in flight, a close during it, and an assertion that the
client ends in one state or the other and not in a half-updated one.

## 1.4 — the protocol's standard output

A stdio MCP client parses every line of the server's standard output as a message. The
server's own log lines and a third-party library's start-up banner once landed there, and a
client that takes the specification literally stops at the first line it cannot parse.

The fix that is on `main` reserves standard output in one place: the original stream is
kept for the protocol and `System.out` is pointed at standard error. It works, and it is
invisible — the contract "the protocol owns standard output" is expressed as a side effect
somewhere else in the file.

So it becomes a type:

- `ProtocolStdout` owns the protocol's stream and is passed to everything that writes the
  protocol. A writer that holds it cannot write anywhere else, and a writer that does not
  hold it cannot write to the protocol.
- The logger is pointed at standard error explicitly, so the application's own log lines do
  not depend on the global redirect at all. The redirect stays as the defence against a
  third-party library that prints to standard output without asking — but the contract no
  longer rests on it.

## 2.2 — the module's rules

`:mcp-server` is judged by `detekt-minimal.yml`: a reduced configuration, chosen for a
module that was a launcher. The module is now a thin launcher over code in `:shared`, and
its own remaining source is one end-to-end test. A module on a reduced configuration is a
module whose rules are a choice nobody made, and the project's own rules — the ones in
`:detekt-rules` — do not reach it.

So `:mcp-server` moves to the root configuration and to `:detekt-rules`, like every other
module. Whatever that surfaces is fixed rather than baselined.

## 2.3 — the two god classes

**`AcpExternalAgentViewModel`** holds four concerns: the agent's selection and the
connection lifecycle, the turn and its activity, the permission handshake, and the input
field. The turn and the connection are separate conversations that happen to live in one
class.

The split is three collaborators the ViewModel owns: one that connects and disconnects, one
that runs a turn, and one that brokers a permission request. The ViewModel keeps the MVI
surface — the state, the intents, the one-shot events — and delegates the work.

**`ToolServerMain`** runs twelve start-up steps in one function: reserve the protocol, parse
the arguments, refuse an ambiguous request, warn about a deprecated one, build the graph,
resolve the dependencies, verify the database, refuse a profile that does not exist,
bootstrap the profiles, read the configuration, build the server, serve.

The split moves the start-up out of the entry point: the steps are functions of their own,
and what they came to is a value — either a server to serve or the reason it cannot
start. The entry point becomes: read the arguments, start the server, serve.

Neither split changes what the code does. They change where a reader has to look.
