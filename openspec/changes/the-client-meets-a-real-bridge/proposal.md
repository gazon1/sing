# The client meets a real bridge

Issue: #993 · Related: `docs/decisions/2026-10-24-the-client-meets-a-real-bridge.md`

## What

The application's ACP client has never spoken to an agent that is not ours.

Every test drives a fake agent: a program in this repository, taught to answer what
our client expects. That proves the client and the fake agree with each other. It says
nothing about whether they agree with the *protocol* — and the protocol is written by
somebody else.

This change puts the two side by side. The application's own client is driven against a
real bridge — `@agentclientprotocol/claude-agent-acp`, the bridge this repository
registered first — and what came back is written down. The fake is then taught the
shapes the bridge sends, so that what the probe found is covered by a test that runs on
every build instead of by a run somebody remembers to do.

## The change in behaviour

- **The client survives a notification it has no answer for.** The real bridge announces
  its authentication state during the handshake, before the session answer, and its
  available commands after the session is open. Both are notifications — a method with no id, answering no
  request this application made. The client reads them, produces no activity for them,
  and carries on. The fake sends both, and a test says so.

- **The pass is reproducible.** `scripts/acp-bridge-probe.sh` starts the bridge, drives
  the client against it and prints the verdict. The exit code is the answer: zero when
  the handshake completed, one when it did not.

## What was found

The handshake completed against the real bridge on the first run, and the client's
reading of the answer was right: `@agentclientprotocol/claude-agent-acp` 0.95.0, protocol
version 1.

The difference was in what the bridge sends besides the initialize answer — and after
the session is open:

| What | The bridge sends | The fake sent, before this change |
|---|---|---|
| Authentication state | `_auth/status_update`, a notification, during the handshake and before the session answer | nothing |
| Available commands | `session/update` with `available_commands_update`, after the session is open | nothing |
| Permission modes | five: `default`, `acceptEdits`, `plan`, `auto`, `bypassPermissions` | one: `default` |
| Configuration options | three — a mode, a model and an effort level | nothing |

None of it broke the client, and that is the point: the client reads what it does not
know and produces nothing for it — a rule that had never been asked to hold against
something that was not ours. The client now has a test that says it survives a
notification it has no answer for.

The fake sends the same shapes now — the bridge's modes, the bridge's three
configuration options and both notifications — plus one field the bridge leaves out, the
models. The *values* are the fake's own: the
bridge's list of models is whatever the machine it runs on is configured for, and a
machine's configuration is not the repository's business. The shape is what the client
has to read.

The bridge's own frames are written down with the change
(`bridge-transcript.txt`), so that the claims about them can be checked by a reader
without a network. Two things in them are the machine's rather than the bridge's — the
list of models the agent is configured for, and the catalogue of commands the machine
happens to have installed — and both are redacted, with the redactions stated in the
file itself. The shape of every frame is complete.

## What this change does not do

It does not prompt the agent. A prompt needs the agent behind the bridge to be
installed and signed in, and the bridge on a machine without credentials says so itself
(`"authStatus":{"kind":"none","label":"Not logged in"}`). The handshake and the session
are the protocol's own layers, and they are what the application owns.

It does not give the agent the tool server. The tool server needs a profile, a profile
needs a database, and the bridge would only use it at prompt time — which is the half
that needs the credentials. The wiring exists and is not exercised; saying so is better
than a probe that pretends.
