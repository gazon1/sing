---
title: "ACP session resume is not buildable yet, and the prerequisite proves it on the wire"
date: 2026-11-25
status: accepted
# `accepted` is the vocabulary's word for "this is the current decision"; the decision here is
# not to build. Precedent for a won't-do: docs/decisions/2026-10-03-tag-icon-unmapped.md.
status-was: closed-wontdo
tags: [ai, acp, external-agent, protocol, research]
---

## Context

Issue #3162 asks the app to resume ACP sessions "only when supported by the agent", and makes
the whole feature conditional:

> Implement this issue only if the pinned SDK 0.34.0 exposes a usable client-side resume
> operation **and** at least one supported profile advertises the required capability.
> Otherwise close as not planned with the evidence recorded in the decision note.

Read literally, both halves hold — and that is the trap this record exists to close. The
distinction the literal reading misses is between **the API existing** and **the operation being
usable end to end**, which is what the issue actually demands ("a usable client-side resume
operation"):

| Condition | Status | Why |
|---|---|---|
| The SDK exposes a resume call | **true** | `AcpResumeCapabilityTest` (`the pinned client exposes the resume call`) pins it, along with the call travelling as protocol **v1** and the marker travelling as `{"resume":{}}` |
| A profile advertises resume | **true** | `claude-code` and `codex` both answer `sessionCapabilities.resume: {}` on the wire |
| A usable session can be established, so resume is reachable | **false** | both advertising profiles fail `initialize` under the pinned SDK, so no session exists to resume |

**The first prerequisite therefore fails when it is read as the issue meant it.** The API-exists
column is not a pass; it is only the reason the failure is worth writing down rather than
guessing at. A reading that stops at "the SDK has the call and a profile advertises it" would
build resume; the third row is why that is not possible. The prerequisite research (#3160,
`docs/research/3160-koog-persistence-and-acp-resume.md`) instead ends in a **no-go**, for a
reason that is not visible in either of those two facts: with the pinned SDK, the two profiles
that advertise resume cannot complete the handshake at all.

That no-go was recorded on 2026-11-25 against bridge versions captured in the research note.
Capabilities are negotiated at runtime and the profiles launch through **unpinned** `npx`
packages, so the conclusion is only true of those versions — which is why it was re-proved
here rather than inherited. Those re-probe logs are **not** committed (they live under `build/`,
which is gitignored); they are a record of one execution on one host, and the re-runnable
evidence is the probe itself: `scripts/acp-bridge-probe.sh npx -y @agentclientprotocol/claude-agent-acp`
(and the same for `codex-acp`) re-derives the failure on demand.

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

Logs from that run (not committed — `build/` is gitignored): `build/diagnostics/acp-reprobe-claude.log`,
`build/diagnostics/acp-reprobe-codex.log`. To reproduce, run the probe in this section's Links.
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
bridges send. **As of 2026-11-25, 0.34.0 is the newest version published** — Maven Central's
`acp-model` metadata gives `<latest>0.34.0</latest>` and `<release>0.34.0</release>`
(`https://repo1.maven.org/maven2/com/agentclientprotocol/acp-model/maven-metadata.xml`, readable
at the time of writing; re-check it before relying on this) — so "wait for an SDK bump" was not
an option that day.

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
**agent's** transcript, so it forces a "whose transcript is the truth" decision — and the
direction #3160 recommends is that the app's own transcript should be the one that counts
(a project-owned Room transcript is that note's *recommendation*, **not** something built today:
nothing about a conversation is persisted at this revision, and no `chat_*` table exists).

## Rationale

The gate reads as satisfied only if "exposes a usable resume operation" is taken to mean "has a
method by that name"; read as the issue meant it, the prerequisite fails, because no usable
session can be established. That gap between the literal words and the usable operation is the
useful output of this issue — written down, so the next reader does not re-derive it and then
build resume on top of a handshake that cannot complete.

Choosing to record rather than build also follows the issue's own safety rules: there is no
resume flow that could avoid "never assume an external session ID is valid indefinitely" or
"never resume under a different profile" without a working session to test against, and a
feature that cannot be exercised end to end cannot be shown to satisfy its own cleanup
criteria (success, failure, cancellation, fallback).

## Consequences

- **#3162 is closed as not planned**, not deferred-work-with-a-stub. Nothing in the app
  changes: `AcpClient` still never calls `session/resume`, `session/load`, `session/list`,
  `session/fork` or `session/delete`, and no session id is persisted.
- **The trigger to revisit is two triggers, and only one of them is automatic.** An SDK-side
  change is caught by `AcpResumeCapabilityTest` (`the pinned SDK cannot decode the bridges'
  initialize response`), which turns red the moment the pinned SDK starts (or stops) decoding
  those frames — that half needs no vigilance. A **bridge-side** change is caught by nothing in
  CI: the test asserts against captured frames, while the profiles resolve through unpinned
  `npx`, so a newer `claude-agent-acp`/`codex-acp` could fix its frame and every test would stay
  green. Re-run `scripts/acp-bridge-probe.sh` (both commands are in Links) when either bridge
  releases, or when someone wants to re-open this. No monitoring infrastructure is implied.
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
- `scripts/acp-bridge-probe.sh` — the probe that produced the live failures above. Reproduce:
  `scripts/acp-bridge-probe.sh npx -y @agentclientprotocol/claude-agent-acp` and
  `scripts/acp-bridge-probe.sh npx -y @agentclientprotocol/codex-acp`; both exit non-zero with
  the `$.status` failure (last re-run 2026-11-25, bridge versions 0.112.0 and 2.12.0)
- `docs/decisions/2026-10-25-the-sdk-lives-in-one-file.md` — the boundary this decision leaves
  untouched
