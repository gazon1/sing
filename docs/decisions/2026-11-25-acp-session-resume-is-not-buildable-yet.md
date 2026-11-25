---
title: "ACP session resume is not buildable yet, and the prerequisite proves it on the wire"
date: 2026-11-25
status: accepted
status-was: closed-wontdo  # recorded as a won't-do, normalized to the vocabulary
tags: [ai, acp, external-agent, protocol, research]
---

## Context

Issue #3162 asks the app to resume ACP sessions "only when supported by the agent", and makes
the whole feature conditional:

> Implement this issue only if the pinned SDK 0.34.0 exposes a usable client-side resume
> operation **and** at least one supported profile advertises the required capability.
> Otherwise close as not planned with the evidence recorded in the decision note.

Read literally, both halves are true, and that is the trap this record exists to close:

- the pinned SDK **does** expose `resumeSession` — `AcpResumeCapabilityTest`
  (`the pinned client exposes the resume call`) pins it, along with the call travelling as
  protocol **v1** and the marker travelling as `{"resume":{}}`;
- two of the three profiles **do** advertise it — `claude-code` and `codex` both answer
  `sessionCapabilities.resume: {}` on the wire.

So a reading that stops at "the SDK has the call and a profile advertises it" would build
resume. The prerequisite research (#3160,
`docs/research/3160-koog-persistence-and-acp-resume.md`) instead ends in a **no-go**, for a
reason that is not visible in either of those two facts: with the pinned SDK, the two profiles
that advertise resume cannot complete the handshake at all.

That no-go was recorded on 2026-11-25 against bridge versions captured in the research note.
Capabilities are negotiated at runtime and the profiles launch through **unpinned** `npx`
packages, so the conclusion is only true of those versions — which is why it was re-proved
here rather than inherited.

## Idea

Distinguish "the SDK can express resume" from "a session exists that could be resumed". The
first is a property of a pinned library and is already true. The second is a property of a
live handshake, and it is the one the feature actually needs.

## Decision

**Do not build ACP session resume.** Close #3162 as not planned. No protocol workaround, no
home-grown transport, no capability plumbing on top of a handshake that does not complete.

The blocking fact, re-verified live on 2026-11-25 through the repository's own probe
(`scripts/acp-bridge-probe.sh`) against the two resume-advertising profiles:

| profile | resolved bridge | handshake | failure |
|---|---|---|---|
| `claude-code` | `claude-agent-acp` 0.112.0 | **failed** | `Expected JsonPrimitive, but had JsonObject as the serialized body of boolean at path: $.status` |
| `codex` | `codex-acp` 2.12.0 | **failed** | same, byte-identical |

Logs: `build/diagnostics/acp-reprobe-claude.log`, `build/diagnostics/acp-reprobe-codex.log`.
These are the only two profiles that advertise resume, so failing both is enough to decide the
issue. The default profile is not a second chance — `@google/gemini-cli` 0.71.1 advertises
`loadSession: true` and **no** `sessionCapabilities` (captured frame, pinned by
`AcpResumeCapabilityTest` → `the default profile decodes and still advertises no resume`), so
it cannot resume; that row is the research note's observation, not a re-probe here, because
the `gemini` CLI is not installed on this machine.
The root cause is a shape mismatch, not a version lag: both bridges send
`"auth":{"logout":{},"status":{}}` (an **object**), while the pinned SDK declares
`AgentAuthCapabilities.status: Boolean?`
(`acp-model` 0.34.0 sources, `commonMain/com/agentclientprotocol/model/Capabilities.kt:236`,
`@UnstableApi`). The frame cannot be decoded, so `initialize` never returns, so there is no
session to resume. `AcpResumeCapabilityTest`
(`the pinned SDK cannot decode the bridges' initialize response`) already pins this against
the captured frames, and the live re-probe above confirms the frames are still what the
bridges send. **0.34.0 is the newest version published** — Maven Central reports it as both
`<latest>` and `<release>` for `com.agentclientprotocol:acp-model` — so "wait for an SDK bump"
is not an option available today.

The one cheap thing the research left on the table stays unbuilt too, on purpose: reading
`sessionCapabilities?.resume` and `loadSession` into `AcpAgentInfo` would be two lines, but it
is only meaningful **after** `initialize` returns, and for the two profiles it would describe
it does not. Building it now would add a field that is always absent in practice.

A second, independent reason the restart case is not a small change: this app launches the
agent as a child and speaks over its pipes (`SubprocessAcpAgentLauncher.launch` →
`Subprocess.spawnDuplex`), and closes the tree on `close()`/`terminate()`. After a restart
there is a *new* child and no way to reach the old one, so `session/resume` — which reconnects
to a session **without replaying** — has nothing to reconnect to. `session/load`, the
protocol's documented cross-restart path, would work in principle, but it replays the
**agent's** transcript while the app keeps its own in Room, so it forces a "whose transcript is
the truth" decision that the built-in chat already answered in favour of the app's own.

## Rationale

The two literal gate conditions are true and the feature is still not buildable, so the useful
output of this issue is the *reason* the gate is not the right question — written down, so the
next reader does not re-derive it and then build resume on top of a handshake that cannot
complete.

Choosing to record rather than build also follows the issue's own safety rules: there is no
resume flow that could avoid "never assume an external session ID is valid indefinitely" or
"never resume under a different profile" without a working session to test against, and a
feature that cannot be exercised end to end cannot be shown to satisfy its own cleanup
criteria (success, failure, cancellation, fallback).

## Consequences

- **#3162 is closed as not planned**, not deferred-work-with-a-stub. Nothing in the app
  changes: `AcpClient` still never calls `session/resume`, `session/load`, `session/list`,
  `session/fork` or `session/delete`, and no session id is persisted.
- **The trigger to revisit** is a pinned-SDK or bridge change that lets `initialize` decode:
  specifically, an SDK whose `AgentAuthCapabilities.status` accepts the object form (or a
  bridge that sends a boolean). `AcpResumeCapabilityTest`
  (`the pinned SDK cannot decode the bridges' initialize response`) turns red when the SDK side
  changes, which is the signal to re-open this — not a date, and not a passing conversation.
- **Resume remains unusable from the app** even for a separately running agent, because the
  app only ever talks to a child it spawned. A socket/attach transport is a different feature
  with a different safety story, and is not implied by this one.
- **`session/load` is the call to evaluate first** if resume is ever revisited: it is the
  documented persistence-across-restarts path, both bridges implement it, and all three
  profiles advertise `loadSession: true`. It still carries the two-transcripts question above.
- The evidence is live-only for the bridge versions checked; the profiles resolve through
  unpinned `npx`, so a future bridge release may change the answer without this repository
  changing at all — capabilities must be negotiated at runtime, never assumed from this record.

## Links

- Issue #3162 — "feat(ai): resume ACP sessions only when supported by the agent"
- Issue #3160 — the prerequisite research, and its note
  `docs/research/3160-koog-persistence-and-acp-resume.md` (verdict and "What was not verified")
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpResumeCapabilityTest.kt` —
  the pinned tripwire: resume exists in the SDK, v1, marker-shaped, and the bridges' frames
  still fail to decode
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClient.kt` — the handshake
  and the protocol-version comparison; the only place the SDK is touched
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentLauncher.kt` — the
  child-per-launch topology that makes `session/resume` unreachable after a restart
- `scripts/acp-bridge-probe.sh` — the probe that produced the live failures above
- `docs/decisions/2026-10-25-the-sdk-lives-in-one-file.md` — the boundary this decision leaves
  untouched
