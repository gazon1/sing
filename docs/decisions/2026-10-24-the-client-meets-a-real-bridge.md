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
network would be a build that fails in a tunnel.

**The fake sends the bridge's shapes.** The authentication status notification
(`_auth/status_update`, right after the handshake), the available-commands notification
(`session/update` with `available_commands_update`, right after the session is open),
five permission modes and three configuration options in the answer to `session/new`.
The fake keeps the models the client requires, so it answers a superset of the bridge's
session — a client that reads both is the client that reads the bridge.

**The client's reading of a notification is specified.** A notification that answers no
request the application made is read and produces nothing: the conversation continues,
and the reader's screen shows nothing it did not justify. That is `REQ-ACPL-004`, and a
test in `AcpClientProcessTest` says so.

## What was found

The handshake completed against `@agentclientprotocol/claude-agent-acp` 0.95.0 on the
first run, protocol version 1, and the client named the agent correctly. The difference
was in what the bridge sends around the handshake — the two notifications, the five
modes, the three configuration options — and none of it broke the client.

That is the finding, and it is worth stating plainly: **the client was already
compatible, and nothing in the suite had said so.** The compatibility was luck until
the probe ran.

## What was not established

**The prompt half.** A prompt needs the agent behind the bridge to be installed and
signed in; the bridge says so itself when it is not (`"authStatus":{"kind":"none","label":
"Not logged in"}`). The tool-call half needs the same credentials, plus a tool server,
plus a database holding the profile the tool server insists on. So the probe covers the
protocol's own layers and says plainly that the rest was not exercised.

**That every bridge agrees with this one.** One bridge was run. The Codex bridge was
registered in the same breath and has not been driven yet; the probe takes its command
line, so the second run is one argument away — but it has not been run, and this ADR
does not claim it would pass.

## Links

- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt` —
  the probe.
- `scripts/acp-bridge-probe.sh` — the way in.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt` — the
  fake, and what it learned.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpClientProcessTest.kt`
  — the test that says the client survives a notification it has no answer for.
- `docs/architecture/acp-client.md` — how to run the probe.
- Issue #993 — the gap this change closes.
