---
change: agent-surface-follow-ups
verdict: pass
date: 2026-10-23
pr: https://github.com/gazon1/sing/pull/1041
reviewed: 727421656bc5963119929ffb0f7db50c3f00e2e2
documents: sha256:8f23ab31dca253d0646e8392dcc80e33b67128712ebf115718a7a8e6b574f7b1
---

## What was reviewed

This change closes the five items the review of the external-agent feature left
behind. The review itself is not repeated here; what is reviewed is the work
that answers it.

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt`
  and `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolSurfaceExport.kt`
  — the classification and its publication (1.1)
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/tools/WriteToolsOnlyProposeTest.kt`
  and the four `*ProposalWriteContractTest.kt` files in
  `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/` — the check against the
  tools' behaviour, and the round trip (1.1)
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClient.kt`
  — the client's lifecycle (1.3)
- `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ProtocolStdout.kt` and
  `shared/src/jvmMain/kotlin/com/singularity/todo/core/log/StderrLogWriter.kt`
  — the protocol's output (1.4)
- `mcp-server/build.gradle.kts` — the module's rules (2.2)
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/` and
  `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolServerStartup.kt`
  — the two god classes (2.3)

The review that named the five items was a second agent's, given the diff and
the decision record rather than the session that wrote the feature. The work
was split between that session and three workers, and every worker's output
was read and checked against the item it was given rather than taken on
trust.

## The review of this change, and what it found

The change was reviewed by a separate agent, in rounds, each one given the diff
and the change's own records. The first seven rounds failed, and each failure
was a real defect rather than a matter of taste. The eighth found none, and its
observations are closed.

**The eighth round found no blocking defect and seven observations.** Six are
closed: the client's promise that a connection which did not happen leaves
nothing behind had no observable, so a client that kept the transport it had
built and dropped only the session — half a connection, the state the lock
exists to prevent — would have passed every lifecycle assertion; the round trip
was enforced per file and not in aggregate, so a fourteenth proposal-gated tool
would have shipped with no contract and every test green; the `delete_task`
refusal asserted that the task survived but not that the proposal was refused;
the helper that reads the client's state described a branch it could not reach;
the skill's tool table still presented a write as a write; and the count of the
detekt findings below said twenty and enumerated eleven. The seventh is left
standing on purpose: a commit message calls the entry point's start-up nine
steps where the records say twelve, and a commit message is the record of what
happened.

**The first round found that the change's central claim was not delivered.**
`create_tag` had been reclassified as proposal-gated, and no test drove it
through the round trip — the claim the change makes for all thirteen
proposal-gated tools. It also found that a `connect()` that gave up kept the
transport it had built, against the client's own promise that a connection
which did not happen leaves nothing behind, and that the decision record and
the check disagreed about the exceptions the check cannot see.

**The second round found the same class of gap one tool over.** `delete_task`
was driven through the round trip except for the confirmation half — the half
that is load-bearing, because a proposal that carried the wrong kind of change
would do nothing at all on confirmation while every test stayed green. It also
found `create_tag` published as idempotent although it is not, the design still
describing the check as it had been, and the path where a close lands before a
connect with no test of its own.

**The third round found that the named exceptions were never exempt.** The
scan recorded only the classes that wrote, so an exception whose write the
scan could not see was not in the scan at all: it took the "its class is not
among the tools" branch and was failed, and the clause that was supposed to
exempt the named ones could never be true. The check was green by an accident
of a method name — the ADR tools' writes happened to match the wide net's verb
list — while the decision record said the named ones are exempt from the write
check rather than failed by it. The scan now records every class it reads, and
the two nets answer different questions: the wide one says whether a write
happened at all, and the narrow one says whether the write is on the user's own
data. An exception the scan sees writing but cannot place is the case the named
set exists for, and the name is what decides it.

**The seventh round found the count fixed in one place and left in three.** The
sixth round corrected the classification tables in the spec-kit draft of the
earlier change, and left the trust-contour table twenty lines below and the
rejected-alternatives rationale that led with the reversed tag case. It also
found a requirement that named one exception where there are two modules
without a detekt task, two bullets of the architecture document that
contradicted each other about the order of the first two lines of the entry
point, a design whose post-split entry point was a subset of the entry point's
own list, and two other live changes still counting three exceptions with the
reversed rationale.

**The sixth round found three records that the change made false and left
standing.** The decision behind the external agent still said the direct-write
exceptions were three — `create_tag` was one of them, and this change
reclassified it — so that decision carries a "superseded in part" note now. The
records said two tools were driven through the round trip; one was, because the
deletion's confirmation half is what this change added. And the live
architecture document still presented `create_tag` as idempotent, which the
catalogue denies. The old detekt decision still said `:mcp-server` is on a
reduced configuration, and the spec-kit draft of the earlier change still
counted three exceptions with a rationale this change reversed.

**The fifth round found that the fourth round's fix had gone the wrong way.**
The fourth round took a review at its word and wrote into the architecture
document that the tool server's refusals *had* changed — because the old code
"said nothing" before it exited. The old code wrote a JSON-RPC error before
every `exitProcess`, and the client sees the same error frame, with the same
code and the same exit code, as it did before. The behaviour did not change;
the shape did. The same round found the records counting the start-up's steps
wrongly, the design naming a type that does not exist, the scan's records
contradicting themselves about what the scan cannot see, a requirement that
claimed a coverage it does not have, and a skill table that had rotted.

**The fourth round found that the records and the code disagreed, and that the
verdict itself had gone stale.** The requirement, the decision record and the
architecture document all said that `connect()`, `close()` and `terminate()`
take a lock; the code says that `terminate()` *tries* it, on purpose, because it
cannot suspend and because it exists to end a conversation that is not ending.
Three records said the scan "cannot see" the ADR tools' writes where the code
says it cannot *place* them — a distinction that decides whether a name alone
can exempt a write. The architecture document said the tool server's behaviour
did not change, and its refusals did. The ratchet script's help named a module
that no longer uses the configuration it named. The scan's own documentation
claimed a reach the two name-based nets do not have, and a write in a helper
that sits above no tool class was invisible to it. The requirement's
"a terminate lands during a close" scenario had no test, and `terminate()` was
exercised by none. And this file itself had gone stale: it spoke for a contract
that had moved since it was written, which the repository's own gate says.

## What was found before the review, and what was done about it

**The catalogue was stale, and the review had already guessed it.** `create_tag`
was declared a direct writer while the tool had been proposing for a while —
the declaration was the stale half of a real drift, and nothing noticed. It is
reclassified, and the export now carries the behaviour and the reason, so the
declaration is readable from outside the compiler.

**A close that landed during a turn broke the goodbye.** The lock and the fast
close were implemented together, and the fast close closed the transport before
the goodbye was sent — so the goodbye went out on a closed channel and a reader
who pressed Disconnect was met with an exception instead of a closed screen.
This was not in the review; it was found by the tests this change added, and it
is the reason the transport now goes only when there is nothing to say on it.

**A close that landed during a connect waited for the handshake.** The lock
alone would have made a close wait out a handshake that may take a minute,
because the agent may be an `npx` invocation downloading its package on first
use. The close now ends the handshake before it waits for the lock.

**The tag-coverage gate read a class as untagged because of a comment.** The
explanation for the suppressed `runBlocking` in the end-to-end test sat between
the `@Tag` lines and the class header, and the gate reads the *contiguous*
annotation block. An untagged class is one that no CI population ever selects,
so the finding was real: the test would have stopped running.

**The full detekt configuration surfaced eleven findings in the new code**, all
fixed rather than baselined: four class signatures that fit on one line, two
loops with more than one jump, a generic catch, a missing trailing comma, a
blank line between `when` conditions, a blank line before a closing brace, and
a mangled line in one of the contract tests.

## What is still open

- The ACP client against a real bridge — issue #993.
- The god-class debt beyond the two classes named here — issue #994.
- The archive order. `ai/write-classification` is introduced by the still-active
  `acp-client-data-safety` change, so this delta modifies requirements that are
  not in `openspec/specs/` yet: `acp-client-data-safety` archives first, this
  one after. The delta validates, and the archive would refuse it until then.
- The two exceptions the check cannot verify — `write_adr` and `update_adr` —
  are named in the test rather than failed, because the scan cannot place their
  write on the user's own data: it is a decision file, not the user's tasks. A
  gate that cannot see the write would be a gate that lies. The name is a claim
  somebody has to argue for: an exception the check cannot verify and that
  nobody has named is a finding.
- The scan's reach is name-based, and the test says so. A write whose method is
  called something neither net holds is invisible to the check; a write through
  a port whose name is not a repository's is invisible to the narrow net. A
  write that belongs to no class is a finding of its own, so the gap is
  reported rather than hidden.

## How the fixes were checked

- `:shared:jvmTest :mcp-server:test :desktopApp:test` with the full population —
  every test, the slow ones included.
- `:shared:detekt :mcp-server:detekt :desktopApp:detekt :androidApp:detekt` —
  no findings, and no baseline entries added.
- The two races are tested against a real agent on the other end of the pipe,
  not against a fake of one: a close that lands during a turn, and a close that
  lands during a connect.
- The round trip is checked in aggregate, not per file: the tools the contract
  tests name against the tools the catalogue calls proposal-gated. A
  proposal-gated tool with no contract test would fail that check, instead of
  shipping with every test green.
- The client's promise that a connection which did not happen leaves nothing
  behind is asserted, through one field the tests can read: a connect that gave
  up holds no session, no transport, no protocol and no client.

## Links

- `docs/decisions/2026-10-23-the-agent-surface-says-what-it-does.md`
- `openspec/changes/agent-surface-follow-ups/`
