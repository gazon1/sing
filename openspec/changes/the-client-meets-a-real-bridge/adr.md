# ADR Review Manifest — the client meets a real bridge

The sequence numbers below are the ordinal of the ADR inside this change; the
corpus in `docs/decisions/` is not numbered.

## ADRs Created

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| 1 | the-client-meets-a-real-bridge | The client meets a real bridge | accepted | The client is driven against a real bridge by a script; the fake learns the shapes the bridge sends; a notification the application has no answer for is read and produces nothing |

## ADRs Reviewed (No Action)

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| — | acp-client-and-agent-activity | External agent in the app (ACP client) and agent activity visibility | accepted | The decision behind the external agent; this change checks the client that decision built against an agent that is not ours, and amends it in part — the suite now says the client survives a notification it has no answer for |
| — | the-agent-surface-says-what-it-does | The agent's surface says what it does | accepted | The decision behind the guarded lifecycle; this change adds the client's reading of an unknown notification to what that lifecycle promises |

## Decisions That Did Not Meet the Bar

- **A test that needs the network.** It cannot be written: a test that cannot run is a
  skipped test, and this repository does not allow them. The pass is a program, and its
  verdict is an exit code.
- **The prompt half, against a signed-in agent.** The bridge on a machine without
  credentials says so itself, and a probe that reported a green tick for a half it did
  not run would be the kind of lie a reader cannot check. The proposal says what was not
  exercised.
- **A second reader of the bridge's standard output.** The protocol owns the stream,
  and a second reader would race it for a line. The transcript is a copy taken
  underneath the protocol.
- **A gate over every bridge.** One bridge was run. The script takes its command line,
  so the second is one argument away — but it has not been run, and a claim about it
  would be a claim about nothing.
