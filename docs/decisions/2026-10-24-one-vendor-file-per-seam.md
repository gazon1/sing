---
title: "One vendor file per seam: the SDK touches six files, each of them alone"
date: 2026-10-24
status: superseded
slug: one-vendor-file-per-seam
supersedes: null
superseded-by: 2026-10-24-the-storage-seam-is-the-bucket
---

# One vendor file per seam: the SDK touches six files, each of them alone

## Context

`VendorSdkConfinementTest` scans every production source in `commonMain` and fails when a file
outside a declared allowlist mentions `io.github.jan.supabase`. The allowlist had three entries:

- `auth/SupabaseClientProvider.kt` — owns the client;
- `auth/SupabaseAuthGateway.kt` — speaks auth;
- `sync/PostgrestSyncRpc.kt` — speaks PostgREST.

Two features added in this cycle need the SDK as well: the backup feature pushes archives to a
Storage bucket, and the Google calendar sync redeems an authorization code through an Edge
Function. Both were written into files that also carried a project interface — `RemoteBackupService`
held the port *and* the Supabase implementation in one file — which made the scan fail and, more
importantly, put the vendor's types in a file that other code reads for the interface alone.

## Decision

**A seam is a file, not a package and not a class.** One vendor-touching class per file, named
after the vendor role it plays, and the file is declared in the gate's allowlist.

The allowlist is now six entries:

| File | Vendor role |
|---|---|
| `auth/SupabaseClientProvider.kt` | owns the client |
| `auth/SupabaseAuthGateway.kt` | speaks auth |
| `sync/PostgrestSyncRpc.kt` | speaks PostgREST |
| `backup/SupabaseStorageRemoteBackupService.kt` | speaks Storage |
| `calendar_sync/auth/SupabaseGoogleTokenGateway.kt` | calls an Edge Function |
| `transport/SupabaseTransportFailure.kt` | tells its failures apart |

The sixth came with the classification of the transport failures. The SDK reports
a refused identity, a function that is not in the schema cache, a lost connection
and a server that said no as four different exception types, and the layers above
the seam saw one thing: "the call failed". Where the classification happens is a
seam question like any other — it must be a file of its own, because it names the
vendor's exception types, and it must be named for the role it plays, because
that is what the allowlist is a list of.

`RemoteBackupService` keeps the port and nothing else; the implementation moved to
`SupabaseStorageRemoteBackupService.kt`.

## Why not a hand-built request instead of the SDK

Both new seams could have avoided the vendor: the backup upload is one `POST` to the Storage
REST API, and the Edge Function call is one `POST` to `/functions/v1/…`. Writing them by hand would
have meant carrying an access token through the call and setting an `Authorization` header by hand —
and a token that expires mid-session turns into a request that arrives as a stranger. The SDK
carries the session, refreshes it, and attaches it; that is the whole reason to use it.

The cost of that choice is two more declared seams, and the gate's value is in the *declared* list
being short and each entry being explainable. Five entries, each with a sentence saying what it is
for, is still a list someone can hold in their head.

## Consequences

- The rule "one vendor class per file" is now the rule, not an accident of how the first three
  seams happened to be written. A file that needs the SDK and also holds a project interface gets
  split.
- The gate keeps its positive control, so a tightened or broken pattern still fails.
- A sixth seam needs a sentence in the table above, which is the price of the seventh.
