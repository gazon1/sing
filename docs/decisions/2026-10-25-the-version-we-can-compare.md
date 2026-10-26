---
title: "The version the app can compare is the protocol's, not the tool server's"
date: 2026-10-25
status: accepted
tags: [acp, protocol, external-agent]
---

## Context

The feature's FR-043 asks for one thing: compare the version of the tool server the app
expects with the version of the tool server that is running, and surface a mismatch.

The app cannot do that, and the reason is not an accident of the implementation — it is
who starts what:

- The **app** does not start the tool server. It hands the agent a *command* — the
  application's own executable with `--mcp-server --profile-id=…` — and the agent decides
  when and whether to run it.
- The **agent** starts the tool server, over a bridge it owns, and the app is not told
  what it started or what version answered.

So the version the app "expects" is the version it ships, and the version that is
"running" is one the app never sees. A comparison between them would compare the app with
itself.

What the app *does* see is the protocol version: the agent's handshake says which version
of the protocol it speaks, and the client's own version is known. That is a real
comparison — a mismatched agent may mean something different by the same words — and it
was not being made.

## Idea

Compare what can be compared, report what is known, and say plainly which of the two the
requirement had in mind cannot be checked.

## Decision

1. **The client speaks protocol version 1, and refuses an agent that speaks another.**
   A mismatch ends the connection with a message that names both versions. Failing closed
   is the rule the rest of this feature follows: a permission request nobody answers is
   declined, a tool call that fails is failed, and an agent that speaks another protocol
   is refused rather than talked to on a guess.

2. **The tool server's version is reported, not compared.** The app knows which version
   it handed over — it is the application's own build — and the connect outcome says so.
   A mismatch would mean the agent started something other than what the app offered, and
   the app has no way to observe that.

3. **FR-043 is re-scoped to the protocol version.** The requirement is amended in the
   feature's specification: the comparison is against the protocol version, and the tool
   server's version is stated rather than checked. The requirement's intent — a mismatch
   is surfaced, not ignored — is kept; the mechanism it named is replaced by one that can
   actually run.

## Rationale

**Why refuse rather than warn.** A warning about a protocol mismatch is a warning the
reader cannot act on: the reader did not choose the agent's version, and the only thing
they can do is choose another agent. Refusing says the same thing in a way that ends the
conversation instead of leaving it half-working.

**Why not compare the tool server's version anyway.** The comparison would be against the
app's own build — the app comparing itself with itself — and it would always pass. A check
that cannot fail is not a check, and a green one is worse than none: it says the question
was asked.

**Why report the version at all.** The reader is entitled to know which build of the
application is answering the agent's tool calls. That is not a comparison; it is a fact,
and it is worth having on the screen and in the log.

## Consequences

- An agent that speaks another protocol version is refused, with a message that names
  both versions.
- The connect outcome carries the protocol version the agent speaks.
- The tool server's version is part of the session's own description: the app knows which
  build it handed over, and says so.
- FR-043 in the feature's specification is amended rather than deleted, and the amendment
  says why the original wording could not be implemented.

## Links

- ADR `2026-10-23-acp-client-and-agent-activity` — the decision behind the external agent
- ADR `2026-10-25-the-turn-is-one-model-call` — the built-in chat's turn, the other
  place where a second model call was being made
- `specs/014-acp-client-agent-activity/spec.md` — FR-043, as amended
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClient.kt` — the
  comparison
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentSession.kt` —
  `AcpAgentInfo`
