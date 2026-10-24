---
change: the-client-meets-a-real-bridge
verdict: pass
date: 2026-10-24
pr: 1114
reviewed: 7798020293a4ecc055542197ba6c1b1977995041
documents: sha256:5cda382b3cf9c9e4759e964e1101472268bf89b6eb6d91700a31f18e282206f6
---

## What was reviewed

The change that puts the application's ACP client against a bridge that is not ours.

- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt` — the
  probe: a `main` that spawns a real bridge, drives the real client, and copies the
  bridge's bytes underneath the protocol's reader.
- `scripts/acp-bridge-probe.sh` and `:shared:acpBridgeProbe` — the way in, and the task
  that runs it.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt` — the
  fake, taught the bridge's shapes.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpClientProcessTest.kt`
  — the test that says the client survives a notification it has no answer for.
- `openspec/changes/the-client-meets-a-real-bridge/**` and
  `docs/decisions/2026-10-24-the-client-meets-a-real-bridge.md` — the records, and the
  bridge's own frames.

The review was a separate agent's, given the diff and the records rather than the
session that wrote them. It ran six times. The first four rounds found blocking
findings; every one of them was in the change's *words* rather than in the client's
behaviour. The fifth round found the change sound — no blocking findings — and left
three observations, which are closed too. The sixth round checked those three closures
themselves: that the strengthened assertion really fails when the notification is sent
after the answer, that the wording landed everywhere, and that the promise says what
the code guarantees. It found no blocking finding, and it confirmed that the closures
broke nothing else — the gates, the exit-code chain and the transcript's own
consistency all held.

## What was found, and what was done about it

**Round 1 — the records claimed a shape the code did not have.** The fake sent one
configuration option with an empty list of choices, and every record said it sent
three — a mode, a model and an effort level, of the bridge's shape. The suite
therefore covered a shape the bridge never sends. The fake sends the bridge's three
now, with the fake's own values: the bridge's list of models is whatever the machine it
runs on is configured for, and a machine's configuration is not the repository's
business.

**Round 1 — the transcript was a snapshot of a race.** The report was built the moment
the connect returned, and the bridge keeps talking afterwards. The transcript is now
read over a window, so the frames this change is about are in it every time.

**Round 1 — the script could not tell a failed build from a failed handshake.** It
reported the second as the first, and the branch that said "the bridge did not answer"
was unreachable. The task no longer fails the build on a failed probe — which is what
the decision record always said it did — and the script reads the verdict out of the
probe's own output: three answers, three messages.

**Round 2 — three records described the authentication notification's timing in a way
the transcript contradicted.** The frame order is the initialize answer, then
`_auth/status_update`, then the session answer, then the available commands. The
notification arrives *during* the handshake, and only the commands are the frame a
connect-time snapshot can miss. The records say which is which now, and so does the
transcript's own summary.

**Round 2 — the redaction broke the shape it was cited for.** The model option's list
of choices had been replaced by a string where the bridge had sent objects, so the
file's claim that "the shape of every frame is complete" was false for that field, and
a reader comparing the transcript with the fake would have seen the fake as the one
with the wrong shape. The placeholder is an object with the same keys now.

**Round 2 — the test could not detect the activity it said it ruled out.** It mapped
the turn's activity to texts and asserted the texts, so an activity that was not a text
would have passed. It asserts the count now, and the fake sends the available-commands
notification *during* the turn — the window in which a session update could become
something the reader sees. A notification sent before the turn is dropped by the
protocol library before the client's adapter ever sees it, and a test over it would
have proved nothing. The fake writes down what it sent, and the test shows that the
notification was sent while the turn was running.

**Round 3 — the records promised thirty seconds; the code promised ten.** The quiet
period ended the read as soon as the bridge stopped talking, so a bridge announcing on
a fifteen-second timer had its announcement cut off — every time. The records called
the thirty-second limit "where the promise ends", and it was not. The probe now reads
the bridge's stream over a fixed thirty-second window, and the promise is the simple
one: every frame the bridge wrote in the window is in the transcript.

**Round 3 — a table that was true became false.** The proposal says what the fake sent
before this change. It sent no configuration options at all — the key was absent from
its session answer — and round 2 had replaced that with "one — a mode, with no
choices", because the review had described the fake *after* the change's first commit.
The cell says "nothing" again.

**Round 3 — "right after the handshake" was still in the sentences a reader meets
first.** The handshake here is initialize *plus* the session, and the transcript's own
frame order puts the notification between the two answers. Every record now says
"during the handshake, before the session answer", and the transcript's summary says
that the *session's* answer carries the modes and the options.

**Round 3 — the fake's session answer was described as more than it is.** The records
called it "a superset of the bridge's session", and the fake carried a comment saying
the client "requires" the modes and the models. It does not: the protocol library
gives both a default, and this application's client reads neither. The fake sends them
because the bridge sends the modes and because a fake that answers a different session
than the bridge proves nothing about the bridge.

**Round 4 — the phrase round 3 had removed from five records was still in the two
comments the change adds to the code.** The fake's comment and the test's comment said
"right after the handshake"; in this repository's vocabulary the handshake is
initialize *plus* the session, and the transcript's frame order puts the
authentication notification between the two answers. The comment contradicted the code
it sat in. Both comments say "during the handshake, before the session answer" now.

**Round 4 — the drop is this application's doing.** A notification sent outside the
turn is handed to this application's own notification handler, which deliberately does
nothing — not to the protocol library, which the records had said. The conclusion was
right and the attribution was not; three records now say whose it is.

**Round 4 — the window had a boundary race.** The snapshot was taken exactly when the
window closed, so a frame the bridge wrote in the last few milliseconds — and that the
protocol's reader had not taken yet — was not in the transcript. The promise held for
every frame except one written microseconds before the boundary, which is not a
promise. The probe now drains the stream after the window, and the records say "every
frame the bridge wrote in the window, and that reached the client".

**Round 4 — the report took two snapshots of a buffer that was still moving**, so a
frame arriving between them made the printed method counts disagree with the printed
frames. The counts come out of the one snapshot now.

**Round 4 — the committed transcript was a capture of an older reader.** The frames had
been byte-identical since the quiet-period version, and nothing in the file spoke to
the thirty-second window the change now promises. The probe was run again with the
current code and the frames were written down again — the same four frames in the same
order, with a new session id — and the file says which reader produced them.

**Round 4 — three smaller gaps.** The header did not say that the model option's
`currentValue` was redacted too; the requirement did not cover a build that fails
before the probe runs; and the architecture document did not say that a *crashed* probe
also exits zero, because the task ignores the probe's exit value. All three are said
now.

**After the review — the CI found what six rounds had not.** The `SubprocessConfinedTest`
gate — "only Subprocess spawns a process" — failed on the branch: the probe started the
bridge with `ProcessBuilder` of its own, outside `core/process`. The rule is right and
the probe was wrong; a spawn outside `core/process` is a spawn that `SubprocessTest`
does not cover, and the defect that class of spawn carries — closing a read end early
and delivering SIGPIPE to the child — has shipped twice in this repository.

The child is started by `Subprocess.spawnDuplex` now, and the probe wraps the child's
output in a tee afterwards: the transcript is still a copy taken underneath the
protocol's reader, not a second reader of the stream. A bridge that could not be
started is now a report instead of a stack trace.

The reason it took a CI run and not a review round is worth writing down: the rule is
a *test*, and a review that does not run the suite does not see it. The fix was
checked by the gate that found it, by the ACP tests, by detekt, and by the probe
against the real bridge.

**Round 5 — no blocking findings.** The fifth round found the change sound: the
round-4 fixes were real in the code, every claim it could check against the
transcript, the fake, the protocol library's own jar and the exit-code chain held, and
the records were truthful about what the code does. It left three observations, and
they are closed too — the sixth round checked the closures and found them correct:

- one code comment still said "right after the session is open", which round 4 had
  removed from the two places it knew about;
- the test's comment said the notification arrived "while the turn was running", while
  the assertion only proved "after the prompt was received". The fake now writes down
  its answers as well as what it received and what it sent, and the test asserts the
  whole order: the prompt arrives, the notification is sent, and only then does the
  turn end. A notification sent after the answer would fall into the handler that does
  nothing, and the test would have passed green while covering nothing;
- the promise said "every frame that reached the client", while the drain guarantees
  "every frame the protocol's reader had taken by the time the drain ended". The
  records now say what the code guarantees.

## How the fixes were checked

- The probe was run against the real bridge — `@agentclientprotocol/claude-agent-acp`
  0.95.0 — and exits 0: the handshake completes, the agent names itself, and both
  notifications are in the transcript.
- The script's three answers were exercised one at a time: the real bridge (success),
  a process that dies before the handshake (`connected: false`), and a build that fails
  before the probe runs.
- The fake's session answer was compared with the bridge's, field by field: the five
  mode ids, the three configuration options with their ids, categories, types and
  choice lists — the same shapes, with the fake's own values.
- The ACP tests: 22 tests across `AcpClientProcessTest` and `AcpEventAdapterTest`, 0
  skipped, 0 failures. The linter: 0 findings. The static gate suite: 57 of 58, the
  one failure being this verdict's own absence before it was written.
- The committed frames were compared with the probe's live output: the same four
  frames, in the same order, with the same shape — and the only differences are the
  two stated redactions.
- The bridge's frames are committed with the change, with the machine's model list and
  command catalogue redacted and the redactions stated in the file, so that every
  claim about them can be checked without a network.

## What is still open

The prompt half and the tool call behind it. A prompt needs the agent behind the
bridge to be installed and signed in, and the tool server needs a profile and a
database on top of that. That half is issue #1091, and this change does not claim it.

One bridge was run. The Codex bridge is registered in the same breath and has not been
driven — the probe takes its command line, so the second run is one argument away, but
it has not been run and nothing here says it would pass.
