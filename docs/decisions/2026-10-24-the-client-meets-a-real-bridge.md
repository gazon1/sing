---
title: "The client meets a real bridge: a probe, and a fake that speaks what the bridge speaks"
date: 2026-10-24
status: accepted
tags: [acp, protocol, testing, verification]
---

# The client meets a real bridge

## Context

The application's ACP client is real code, and its tests are real: they start a program
on the other end of a pipe and speak the protocol to it. That program — `FakeAcpAgent` —
is ours. It was written to answer what our client expects, and it does.

So the suite says that the client and the fake agree with each other. It says nothing
about whether they agree with the protocol, because the protocol is written by somebody
else: `@agentclientprotocol/claude-agent-acp` and `@agentclientprotocol/codex-acp` are
third-party implementations of it, and they were the reason the client was written at
all.

Nothing had ever put the two side by side. The client's reading of the protocol came
from the library's models and from a fake built against those models — two things that
cannot disagree with each other, because one was written from the other. The fields the
client reads were the fields the fake sends, and the fake sent the fields the client
reads. A bridge that announced something else would have met a client that had never
been asked.

The library is lenient about what it does not model (`ignoreUnknownKeys`), and the
adapter ignores session settings it does not show. Both are right, and neither is a
substitute for the check: "the model would not have failed" is not "the client survives
a bridge that says something new".

## Idea

**One script, one verdict.** `scripts/acp-bridge-probe.sh` starts a real bridge and
drives the application's own client against it. Its exit code is the answer: zero when
the handshake completed, one when it did not. It prints what came back — the agent's
name and version, the methods the bridge used, and every frame verbatim — because the
frames are the record.

**The probe is a program, not a test.** It needs a real `npx`, a real network and a real
bridge. In this repository a test that cannot run is not allowed: a class that no
population selects is a class that stops running, silently. There is no way to have a
network test and a suite that may skip it, so the pass is a program and the verdict is
its exit code.

**The transcript is taken underneath the protocol.** The protocol owns the bridge's
standard output, and a second reader would race it for a line. So the probe tees the
bytes as they pass: one reader, one copy.

**The transcript is read when the window is over.** The bridge keeps talking after
the session it answered: its available commands come on a timer. A transcript read the
moment the connect returned would miss that frame — and it is one of the frames this
probe is for. So the probe reads the bridge's stream for thirty seconds after the
connect, and the promise is the simple one: every frame the bridge wrote in the window
is in the transcript, and a frame that comes later is a frame the records do not claim.

The window is a length of time and not a heuristic. An earlier version waited for the
bridge to go quiet, and a quiet period ends the read as soon as the bridge stops
talking — so a bridge that announces on a longer timer has its announcement cut off,
every time, not sometimes. A fixed window has no such edge.

The two notifications are not in the same place, and the records say which is which:
the authentication status arrives when the bridge's own probe resolves — in the
recorded run, during the handshake, before the session answer — while the available
commands arrive after the session is open.

**The fake learns.** What the bridge sends that the fake did not becomes something the
fake sends, and a test covers it. A finding that lives only in the transcript of one run
decays; the next bridge that announces something new is exactly the case the probe was
run to find out about.

## Decision

**The client is driven against a real bridge by a script, and the fake is taught what it
finds.**

The probe is `AcpBridgeProbe`, a `main` in `shared/src/jvmMain` beside the client it
drives; `:shared:acpBridgeProbe` runs it, beside `exportToolSurface`, which is the same
shape; `scripts/acp-bridge-probe.sh` is the way in and turns the output into an exit
code. The task does not fail the build on a failed probe — a build that depended on the
network would be a build that fails in a tunnel — so the script reads the verdict out
of the probe's own output, and the three answers it can give stay apart: the build
failed, the bridge did not answer, or the handshake completed.

**The fake sends the bridge's shapes.** The authentication status notification
(`_auth/status_update`, during the handshake and before the session answer), the
available-commands notification
(`session/update` with `available_commands_update`), five permission modes, and three
configuration options — a mode, a model and an effort level — in the answer to
`session/new`. The fake's session answer has the same shape as the bridge's, with the
fake's own values — and one field more: the models, which the bridge leaves out and
the fake keeps. A client that reads both is the client that reads the bridge.
The *values* are the fake's own: the bridge's list of models is whatever the machine it
runs on is configured for, and a machine's configuration is not the repository's
business. The shape is what the client has to read.

The available-commands notification is sent by the fake *during* the turn, where the
bridge sends it after the session is open. The moment is the fake's own, and it
is chosen: a notification sent before the turn is dropped by the protocol library
before the client's adapter ever sees it, and a test over it would say nothing about
the window in which a session update could become something the reader sees.

**The client's reading of a notification is specified.** A notification that answers no
request the application made is read and produces nothing: the conversation continues,
and the reader's screen shows nothing it did not justify. That is `REQ-ACPL-004`, and a
test in `AcpClientProcessTest` says so.

**The bridge's own frames are written down.** The transcript is committed with the
change, so that the claims about what the bridge sends can be checked by a reader
without a network and without running anything. Two things in it are the machine's
rather than the bridge's — the models the agent is configured for, and the commands
the machine happens to have installed — and they are redacted, with the redactions
stated in the file. The shape of every frame is complete, and the shape is the point.

## Rationale

The handshake completed against `@agentclientprotocol/claude-agent-acp` 0.95.0 on the
first run, protocol version 1, and the client named the agent correctly. The difference
was in what the bridge sends besides the initialize answer, and after the session is
open — the two notifications, the five modes, the three configuration options — and none
of it broke the client.

That is the finding, and it is worth stating plainly: **the client was already
compatible, and nothing in the suite had said so.** The compatibility was an assumption
until the probe ran, and an assumption that nobody had checked is worth no more than a
guess.

The reasons for the shape of the change follow from what a test can and cannot do here.
A test cannot reach the network, so the pass is a program. A program has no second
reader of the protocol's stream, so the transcript is a copy taken underneath it. A
bridge that talks after the session it answered would have its announcements cut off
by a snapshot taken too early, so the snapshot waits for a window. And a finding that
lives in one run's transcript decays, so the fake learns it and the suite covers it.

## Consequences

**The suite now covers a bridge's shapes.** The fake sends the bridge's notifications
and the bridge's session answer, and a test says the client survives a notification it
has no answer for. A bridge that announces something new is no longer a surprise to
the client's *test* — only to its author.

**The probe is a tool, not a gate.** It is not run by the build and it is not a test,
so nothing fails when the network is down. What it gives is a verdict a person asks
for, and a transcript that can be compared with the fake's shapes.

**The prompt half is still unverified.** A prompt needs the agent behind the bridge to
be installed and signed in; on a machine without credentials the bridge says so itself
(`"authStatus":{"kind":"none","label":"Not logged in"}`). The tool-call half needs the
same credentials, plus a tool server, plus a database holding the profile the tool
server insists on. That half is issue #1091, and this decision does not claim it.

**One bridge was run.** The Codex bridge is registered in the same breath and has not
been driven; the probe takes its command line, so the second run is one argument away —
but it has not been run, and this ADR does not claim it would pass.

## Links

- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt` —
  the probe.
- `scripts/acp-bridge-probe.sh` — the way in.
- `openspec/changes/the-client-meets-a-real-bridge/bridge-transcript.txt` — the bridge's
  own frames.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt` — the
  fake, and what it learned.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpClientProcessTest.kt`
  — the test that says the client survives a notification it has no answer for.
- `docs/architecture/acp-client.md` — how to run the probe.
- Issue #993 — the gap this change closes. Issue #1091 — the half that is left.
