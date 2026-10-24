# The client meets a real bridge — design

## The problem this solves

The client is the application's own code, and its tests drive a fake agent that the
same repository wrote. The fake answers what the client expects, so the two agree by
construction. The protocol is a third party's, and nothing in the suite said whether
our client agrees with it.

The gap is not hypothetical. The real bridge announces two notifications our fake never
sent — its authentication state and its available commands — and it answers
`session/new` with five permission modes and three configuration options where the fake
answered with one mode and no options. The client survived all of it. That is worth
knowing, and worth keeping: a future bridge that announces something the protocol
library of the day does not model is exactly the case where "it worked with our fake"
would have been worth nothing.

## Why a program and not a test

The probe needs a real `npx`, a real network and a real bridge. In this repository a
test that cannot run is not allowed: a class that no population selects is a class that
stops running, silently, and `scripts/check-test-runs.py` fails the build over it.

There is no way to have a test that needs the network and a suite that is allowed to
skip it. So the pass is a *program*:

- `AcpBridgeProbe` — a `main` in `shared/src/jvmMain`, next to the client it drives. It
  spawns the bridge, builds the real `AcpClient`, calls `connect()`, and prints what
  came back: the verdict, the agent's own name and version, the methods the bridge
  used, and the frames verbatim.
- `:shared:acpBridgeProbe` — the Gradle task that runs it, beside `exportToolSurface`,
  which is the same shape: a `main` in `jvmMain` and a task that has the compiled
  classes and the runtime classpath.
- `scripts/acp-bridge-probe.sh` — the way in. It checks that `npx` is there when the
  bridge *is* an npx invocation, runs the task, and turns the output into an exit code:
  zero when the handshake completed, one when it did not. A build that failed and a
  probe that did not connect are two different answers, and the script says which.

The task does not fail the build on a failed probe. A task that did would make every
build on a machine depend on whether the network was up.

## How the transcript is taken without a second reader

The protocol owns the bridge's standard output. Nothing else may read it — a second
reader would race the first for a line, and a line read twice is a frame sent twice.

So the probe copies the bytes *underneath* the protocol: it builds the duplex process
with an input stream that tees into a buffer on its way past. One reader, one copy.

The copy is a byte copy and not a line copy, so a frame that arrives in two pieces is
still one frame in the transcript. The transcript is what a reader looks at when a
bridge's behaviour has to be compared with the fake's.

## When the transcript is read

When the window is over.

The bridge keeps talking after the session it answered: its available commands come on
a timer. A transcript read the moment the connect returned would be a snapshot of a
race: the frame this change is about would be in it sometimes, and the change would be
built on a frame that was not always there.

So the probe reads the bridge's stream for thirty seconds after the connect, and
writes the transcript down when the window is over. The promise is the simple one:
**every frame the bridge wrote in the window, and that reached the client, is in the
transcript**, and a frame that comes later is a frame the records do not claim.

The window closes at a moment, and a frame the bridge wrote in its last milliseconds
may not have been read yet — the protocol's reader and the copy under it are two
steps, and the copy happens when the reader takes the bytes. So the probe drains the
stream for a moment after the window: three hundred milliseconds of quiet, and three
seconds at most. Without the drain, the promise would hold for every frame but one
written microseconds before the boundary.

The window is a length of time and not a heuristic. An earlier version waited for the
bridge to *go quiet* — and a quiet period ends the read as soon as the bridge stops
talking, so a bridge that announces on a timer longer than the quiet period has its
announcement cut off, every time, not sometimes. A fixed window has no such edge: it
either holds the frame or it does not, and a reader can tell which by looking at the
clock.

The two notifications are not in the same place. The authentication status is sent when
the bridge's own authentication probe resolves — in the recorded run that was during the
handshake, before the session answer, so it was already in the transcript when the
connect returned. The available commands come after the session is open, and *that* is
the frame a connect-time snapshot can miss. Saying that the transcript "would miss both"
would be wrong, and the records say which is which.

## What the fake learns

The fake is taught the bridge's shapes: the two notifications, the five modes and the
three configuration options. The fake's session answer has the same shape as the
bridge's, with the fake's own values — and one field more: the models, which the
bridge leaves out and the fake keeps. A client that reads both is the client that
reads the bridge.

The values are the fake's own. The bridge's list of models is whatever the machine it
runs on is configured for, and a machine's configuration is not the repository's
business; what the client has to read is the shape.

The client's adapter already ignores the session settings (available commands, current
mode, configuration options): they belong to a settings screen, not to the story of
what the agent just did. That decision was made before this probe; the probe confirms
it against a real bridge.

What the probe adds is the *notification*. The client has to read a method it has no
answer for and carry on, and a fake that never sends one cannot say that it does.

## What stays unverified, and why

The prompt half. A prompt needs the agent behind the bridge to be installed and signed
in; the bridge says so itself when it is not (`"authStatus":{"kind":"none","label":"Not
logged in"}`). The tool-call half needs the same credentials, and a tool server, and a
database to hold the profile the tool server insists on.

So the probe covers the protocol's own layers — the handshake and the session — and
says plainly that the rest was not exercised. A probe that reported a green tick for a
half it did not run would be the worst kind of lie: the kind a reader cannot check.
