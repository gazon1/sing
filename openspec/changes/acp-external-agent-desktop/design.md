# Design: The user connects an external agent

Related ADR: `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`

## Two protocols, two jobs

The conversation with the agent travels on the Agent Client Protocol. The tools travel on the
application's own tool server. The application holds no second copy of its tooling: it hands
the agent a launchable reference to the tool server it already runs.

That split is what makes the rest possible. The kill switch, the tool-call log and the rule
that a write asks first stay in one place, because there is only one place where domain
access happens.

## stdio, because it is the only transport everyone has

Every agent must support the standard-input/output transport. The network transports are
conditional on a capability an agent may not declare. So the reference the agent receives
names a command, its arguments and its environment.

The absence of a tool server is expressed as an **empty list** of references — not as a
missing one — and it is a session that connects with a warning, not a failed connection. An
agent with no access to the user's tasks is still a working agent; the user has to be able to
tell the difference between that and a broken tool.

## The delivery mechanism is probed, not chosen

The application is packaged with `jpackage`. Whether the packaged launcher's classpath can
start a second main class — the tool server's — is not a matter of taste, and it is not
knowable without looking. The packaging is probed first, and the probe's output decides.

If the packaged classpath carries the shared application classes, then the tool server's
code has to live with them, and the command the agent receives runs from the application's
own classpath. If it does not, the tool server is shipped as its own artifact, located
relative to the installation root.

One mechanism. Two half-implemented mechanisms is worse than either.

## Process ownership

The agent is a process. The tool server it launches is a child of that process. Ending the
agent does not end its children, so closing a session ends the **tree**: descendants first,
deepest first, then the root, with a grace period before anything is killed outright.

Closing is idempotent, because closing is something that happens on teardown paths, and
teardown paths run more than once.

The protocol's own "close the session" call is unstable and only available when the agent
declares the capability, so it is attempted and never relied upon.

## One place touches the SDK

The protocol library is young and moving. Every file that imports it is a file that has to
change when it moves. So exactly one file imports it; everything else — the event mapping,
the permission handling, the process ownership — speaks in the application's own vocabulary
and knows nothing about the protocol.

The same discipline applies to the operation interfaces: the library supplies default
behaviours for the file-system and terminal operations that refuse when the corresponding
capability was not advertised. The application advertises neither and writes none of them.

## Permission is not the proposal pipeline

Three separate contours, kept separate:

| Contour | Governs | Gate |
|---|---|---|
| The agent's permission requests | the agent's own tools | the confirmation sheet in the application |
| The proposal pipeline | the application's writes to the user's data | the existing proposal confirmation |
| The direct-write exceptions | two named tools | the reason recorded in the registry |

> **Amended (2026-10-23):** there were three exceptions, and `create tag` was one of
> them — the tool had been proposing for a while and the declaration had not caught up.
> A tag is user content after all, so it is a proposal-gated tool now. See
> `docs/decisions/2026-10-23-the-agent-surface-says-what-it-does.md`.

Presenting them as one mechanism would tell the user that they are protecting something they
are not, and would train them to approve reflexively.

## Rollback risk

**Medium, and it is the delivery mechanism.** The screen, the client and the process
ownership can all be removed without touching the tool server, the proposals or the built-in
agent. The delivery mechanism is the part that reaches into the packaging, and if it is
wrong the symptom is a launch that fails on a user's machine — after packaging, on hardware
nobody in the project has.

The mitigation is the probe: the mechanism is chosen from an observation of a real packaged
build, and the manual pass in the quickstart runs against a real packaged build too.

The second risk is **orphaned processes**, which is why the process-tree test uses a fake
agent that spawns a child of its own. A test that only kills the root would pass while
leaving the tool server running.
