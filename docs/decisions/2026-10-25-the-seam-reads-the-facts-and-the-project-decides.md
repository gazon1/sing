---
title: "The seam reads the facts; the project decides what they mean"
date: 2026-10-25
status: accepted
tags: [sync, seam, errors]
---

# The seam reads the facts; the project decides what they mean

## Context

`VendorSdkConfinementTest` fails when a file outside a six-entry allowlist mentions the
Supabase SDK. The rule is about **types**, and it is satisfied by name: a file can import the
vendor and still hold the project's decisions inside it.

That is what happened in three places, one after another:

- `SupabaseAuthGateway` decided what a failed sign-in means — a missing session, a session the
  server did not return, a token the server refused — and those meanings ended up in
  `AuthFailure`, a project file with no vendor in it;
- `SupabaseGoogleTokenGateway` carried the Edge Function's contract — its name, its three
  payloads, what its answer means — and that contract ended up in `GoogleTokenExchangeWire`;
- `PostgrestSyncRpc` still held two decisions of its own: that there is no project to call is a
  state with its own code (`sync.not_configured`), and what a refused call is called in the
  message the person reads.

The third one was left behind, and the reason is worth naming: it is small, it reads fine, and
nothing fails because of it. Nothing fails because of a *decision living in the wrong file*.

## Idea

**A seam reads; the project decides.**

What only the seam can do is read the vendor's exception: its status code, its own error code, its
words. That reading is transport, and it belongs in the transport.

What the reading *means* — which code the failure is reported under, what the message says, whether
"there is no project" is a refusal or a state — is a decision of this project, and it belongs in a
file that has no vendor in it, where a test can reach it without one.

## Decision

**The seam hands the facts over in plain types, and a project file decides what they mean.**

The facts are three, and they travel as `TransportFacts`: the status code, the server's own code,
and the server's words. No vendor's type is attached to them.

The decisions live beside them, in project files:

| Decision | Where it lives now |
|---|---|
| what a failed sign-in means | `core/auth/AuthFailure.kt` |
| what the Edge Function's answer means | `feature/calendarSync/auth/GoogleTokenExchangeWire.kt` |
| what a refused database call means | `core/sync/SyncRpcFailure.kt` |
| what a transport failure is, in four classes | `core/error/TransportFailure.kt` |

The rule is checkable: **a file in the vendor allowlist may read the vendor's exception and may
carry no decision of its own.** A code of its own, a message of its own, a branch of its own on
what the failure means — those belong in the project file that stands next to it.

## Rationale

**A decision in a vendor file is invisible to the tests that could check it.** The container's
format is proved in `commonTest` because the container lives in `commonMain`; the cryptography is
proved on the JVM because it cannot be reached from `commonTest`. A decision that lives in a vendor
file is in the same position as the cryptography — checkable only by reading it, and only by
somebody who knows the vendor.

**The allowlist is a list of files, and the files are read for their role.** Somebody opening
`PostgrestSyncRpc.kt` to see how a call is made should not have to decide, on the way, what
`sync.not_configured` means. The seam is the transport; the reading is the role.

**The decisions are shared.** The refusal of an RPC and the refusal of a backup push are told apart
by the same four classes and reported under the same shape. When the decision is a project file,
both callers read one decision. When it is a vendor file, each caller has its own.

## Consequences

- `TransportFacts` is a new type in `core/error` (`TransportFailure.kt`): three plain values, no
  vendor attached. The vendor's exception is read in `SupabaseTransportFailure` and nowhere else.
- `SyncRpcFailure` is a project file with no vendor in it, and its test runs in `commonTest` —
  without a network, without the SDK, and without a device.
- `PostgrestSyncRpc` is now only transport: it reads the facts and hands them over.
- The allowlist stays at six files. The rule about what a seam file may contain is new, and it is
  a rule a reviewer can apply by reading one file.
- The same move is available for the other seams — the bucket's failure and the client's lifecycle
  still hold decisions of their own — and it is not done yet. The line is drawn where the cost of
  leaving it starts to be paid: a decision that another caller also needs.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncRpcFailure.kt` — what a refused call means.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/PostgrestSyncRpc.kt` — the seam that reads the facts.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/error/TransportFailure.kt` — the facts, without the vendor.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/transport/SupabaseTransportFailure.kt` — where the vendor's exception is read.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthFailure.kt` — the same shape, done first.
- `docs/decisions/2026-10-24-one-vendor-file-per-seam.md` — the rule this continues.
