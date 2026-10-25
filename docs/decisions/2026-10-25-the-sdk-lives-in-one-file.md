---
title: "The SDK lives in one file, because the plan said so and the code was the thing that disagreed"
date: 2026-10-25
status: accepted
tags: [acp, protocol, external-agent, architecture]
---

## Context

The feature's plan states one structural rule:

> the ACP SDK is touched in exactly one file (`AcpClient.kt`), so an SDK upgrade is a
> single-file diff; every adapter concern (event mapping, permissions, process
> ownership) sits in files that do not import the SDK.

The shipped code touched it in three: `AcpClient.kt` (the conversation),
`AcpClientSessionOperations.kt` (the client's operations) and `AcpEventAdapter.kt` (the
mapping). Two of the three could not avoid the import without changing what they were:

- the session operations **implement** a protocol interface — `ClientSessionOperations` —
  so the protocol's method signatures are its method signatures;
- the adapter's job was to match the protocol's `SessionUpdate` variants one by one, so
  the protocol's variants were its input type.

The convergence pass called this an inconsistency and recommended amending the plan
rather than the code, on the grounds that "the plan's rule was wrong, not the three SDK
files". That reading treats the rule as an accident of phrasing. It was not: the rule
exists because a protocol library that is spread across files is a protocol library that
every file has to be re-read when the library changes, and because a domain that has to
import the SDK to describe what the SDK said is a domain that cannot be tested without
the SDK.

The evidence for that is already in the tree: the adapter's test had to import the SDK's
`SessionUpdate`, `ToolKind`, `PlanEntry` and `StopReason` to state its cases, and the
external-agent ViewModel's test had to import `PermissionOption` and `RequestPermissionOutcome`
to fake an agent. The tests of the application's own behaviour were pinned to a library
they were not testing.

## Idea

Make the rule true rather than deleting it: give the protocol a door, and make
everything above that door speak the application's own vocabulary.

## Decision

1. **One file imports the SDK: `AcpClient.kt`.** It holds the conversation, the
   translation of the protocol's vocabulary into the application's, and the
   implementation of the protocol's client operations.

2. **The protocol's updates are translated at the door into `AcpSessionUpdate`** — the
   application's own vocabulary: a text delta, a tool call, a plan, and the four things
   that carry nothing for the reader (the user's own message, the agent's private
   reasoning, a session setting, an unrecognised update).

   The translation is **exhaustive over the protocol's variants**, so a new variant in a
   new SDK version is a compile error in the door and nowhere else.

3. **The uninteresting variants are translated, not dropped.** The door says *what
   happened*; `AcpEventAdapter` — SDK-free — decides *what a reader sees*. The ignore
   list therefore lives where the reader is known, and it is a table that can be checked
   by exhaustion without the SDK.

4. **The client's operations are built from the application's port.**
   `AcpSessionPermissions` is a port in the application's vocabulary; the door builds
   the protocol's implementation of it. The permission request, the reader's decision
   and the refusal are the application's; the protocol's option types and its wire
   outcome stay in the door.

5. **The tests follow.** The adapter's table is checked without the SDK, the ViewModel's
   fake agent asks for permission in the application's vocabulary, and one test — the
   door's own — speaks the protocol.

## Rationale

**Why the door rather than three files.** A protocol library is a dependency on
*vocabulary*, not only on types. Spreading it means the domain's shape is the library's
shape, and every place that reads the domain has to be revisited when the library
changes — including the tests, which is where the cost shows up first.

**Why translate the variants that carry nothing.** A door that dropped them would have to
decide at the door what a reader needs to see, and the door is the one place that knows
the protocol and not the reader. Translating them costs one extra step and buys a
decision table that is written in the application's words — and therefore checkable.

**Why the door's translation is exhaustive.** The point of the rule is that an SDK
upgrade is a single-file diff. An exhaustive `when` makes that true by construction: the
compiler names the new variant, in the one file, instead of the behaviour changing
quietly somewhere else.

**Why the door keeps its own test.** The door is the one place where a mistake is
invisible from above: everything above it sees the application's vocabulary and cannot
tell that the translation dropped something. So the door is tested against the protocol
it speaks — one test file, and the only one in the repository that imports the SDK.

## Consequences

- `AcpClientSessionOperations.kt` is gone; its application's half lives in
  `AcpPermission.kt`, and its protocol half is inside the door.
- `AcpEventAdapter` no longer knows the protocol: it reads `AcpSessionUpdate` and
  `AcpStopReason`, and its table is checked without the SDK.
- The external-agent ViewModel's test no longer imports the SDK, and its fake agent
  asks for permission in the application's vocabulary.
- The door is `internal` where a test needs it (`SdkSessionOperations`,
  `SessionUpdate.toDomain()`), so the one test that speaks the protocol can reach it.
- The plan's structural rule is now true, and the amendment is recorded in the plan
  itself rather than by leaving the two to contradict each other.
- The domain vocabulary grew two files (`AcpSessionUpdate.kt`, `AcpPermission.kt`).
  That is the price of the door: the protocol's shape is now stated twice — once in the
  SDK, once in the application — and the translation is where the two are reconciled.

## Links

- ADR `2026-10-23-acp-client-and-agent-activity` — the decision behind the external agent
- ADR `2026-10-25-the-version-we-can-compare` — the protocol version the door compares
- ADR `2026-10-25-the-turn-is-one-model-call` — the same principle on the built-in side:
  one call, one vocabulary, one owner
- `specs/014-acp-client-agent-activity/plan.md` — the structural rule, as amended
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClient.kt` — the door
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpSessionUpdate.kt` —
  the vocabulary the door translates into
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpPermission.kt` — the
  permission port
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpSdkBoundaryTest.kt` —
  the door's own test, the only test that speaks the protocol
