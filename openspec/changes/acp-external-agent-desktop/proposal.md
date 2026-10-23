# The user brings their own agent

Issue: — · Related: `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`

## What

The user already pays for an agent. It knows how to plan a week, how to read a pile of
files, how to work for forty minutes without being reminded. It works well, and it works
*elsewhere* — in a terminal, in a directory that is not this app's data.

This change lets that agent work on the user's tasks, from inside the app, under the app's
supervision.

The obvious way to do this — give the agent its own copy of the app's tooling — is the way
that loses. Two copies of the domain access means two copies of the kill switch, two copies
of the tool-call log, and two copies of the rule that a write asks first. The second copy is
the one nobody updates.

So the app does not get a second copy of its tooling. It hands the agent a **launchable
command for the tool server it already runs**. The conversation travels on one protocol; the
tools travel on the other. The domain access stays in one place, where the kill switch, the
log and the proposal pipeline already are.

## The change in behaviour

- A new desktop screen where the user **picks an external agent** from a list of known ones,
  sees which command will be launched, and connects to it.
- The agent gets **access to the user's own tools**, through the application's existing tool
  server. It proposes changes; the proposals appear in the app's own confirmation screen.
- The app **shows the agent working** — the same cards the built-in agent uses.
- The agent may ask for **permission** to use one of its own tools. The app asks the user,
  applies the answer, and declines the request if nobody answers.
- **Closing the session ends the whole process tree** — including processes the agent itself
  started, such as the tool server it launched.
- If the tool server **cannot be found**, the session still connects, and the app warns the
  user before the first message that the agent has no access to their tasks. That warning
  stays for the whole session and is visibly different from a tool that failed after a
  successful connection.
- If the agent **crashes**, the answer so far stays, the user is told, and nothing is
  retried — a retry could duplicate a proposal or a write.

## Out of scope

- The application acting as an agent for an editor. That is a second distributable and a
  narrow audience; it is a later stage.
- The draft second version of the protocol, and carrying the tools over the conversation
  protocol.
- Serving the tool server over the network. Nothing here needs it.
- Mobile support of any kind.
- Showing what the external agent cost.

## The one thing that has to be decided first

How the tool server is launched from a packaged desktop build is not yet known, and the two
answers lead to different implementations. So the packaging is **probed** before anything is
built, and the probe's result is recorded before the mechanism is chosen. Exactly one
mechanism is implemented on the strength of that result.
