---
title: "The Storage seam is the bucket, and the decisions above it are testable"
date: 2026-10-24
status: accepted
slug: the-storage-seam-is-the-bucket
supersedes: 2026-10-24-one-vendor-file-per-seam
---

# The Storage seam is the bucket, and the decisions above it are testable

## Context

`VendorSdkConfinementTest` scans every production source in `commonMain` and fails when a file
outside a declared allowlist mentions `io.github.jan.supabase`. The allowlist had six entries, and
one of them was `backup/SupabaseStorageRemoteBackupService.kt` — the file that speaks Storage and decides.

That file also made two decisions:

- **where** an archive goes: under the user's own id, so a listing for one account never shows
  another account's archives, whatever the bucket's own policies are;
- **what a failure means**: the bucket's policies are the reason a push can fail in four different
  ways, and the difference between them is the difference between "sign in again", "the bucket is
  not there", "there is no connection" and "the policy refused this write".

Both decisions were in a file that could not be tested without a bucket. There was a test on the
port and a test on the ViewModel with a fake, and the class in between — the one that decides the
path and names the failure — had none. A class that can only be exercised by talking to Supabase
Storage is a class whose decisions nobody checks.

## Idea

A seam is a file, and a file that holds the vendor's calls can hold the vendor's calls *only*.
Everything above it is a decision, and a decision is worth a test.

The bucket's three operations — put, get, list — are a narrow slice, and the service's decisions
are expressible in terms of them: a path, some bytes, a prefix. A port with those three methods is
enough to drive every branch of the service without a network.

## Decision

**The file that speaks Storage is the bucket; the service above it decides and is tested.**

| File | Vendor role |
|---|---|
| `auth/SupabaseClientProvider.kt` | owns the client |
| `auth/SupabaseAuthGateway.kt` | speaks auth |
| `sync/PostgrestSyncRpc.kt` | speaks PostgREST |
| `backup/SupabaseBackupBucket.kt` | speaks Storage |
| `calendarSync/auth/SupabaseGoogleTokenGateway.kt` | calls an Edge Function |
| `transport/SupabaseTransportFailure.kt` | tells its failures apart |

The allowlist still has six entries, and the rule is unchanged: **one vendor-touching class per
file, named after the vendor role it plays**. What changed is which file plays the Storage role.

`BackupBucket` is the new port — put, get, list, and nothing else. It takes a path and no
user id: the prefix is the service's decision, and a port that computed it would make the decision
invisible and untestable.

`SupabaseBackupBucket` keeps the vendor's calls and nothing else: it takes a path and some bytes,
and hands the vendor's failure to `SupabaseTransportFailure` — so the classification still happens
where the vendor's types are.

`RemoteBackupServiceImpl` keeps the decisions and loses the vendor: it takes a bucket and a file
system, and its own test drives it with a fake bucket that records what it was asked for.

The names follow the project's rule that a primary constructor fits on one line, and they say what
the file is: `BackupBucket` is the port, `SupabaseBackupBucket` is the seam, `RemoteBackupServiceImpl`
is the decisions.

## Rationale

**The path is the decision that can leak.** The bucket is shared by every account of the project,
so the prefix under the user's id is what keeps one person's archives out of another person's list.
That is now an assertion: the test asks the fake bucket what path the upload went to, and the
answer is `user-1/archive.zip`.

**The failure names are the decision that hides.** Four kinds of failure used to arrive as one
code. The service still names them — `backup.unauthorized`, `backup.not_found`,
`backup.network_failed`, and `backup.push_failed` for everything else — and now each of them is a
test rather than a claim.

**A fake that records is a decision made visible.** The bucket's fake answers with whatever the
test arms it with: a refused session, a missing object, a socket that gave up, a policy that said
no. The service's classification is then a pure function of what the bucket said, and it is
checked four times over.

**The seam did not move; it got narrower.** The vendor still touches exactly six files. One of
them is now a file that does one thing — talk to the bucket — and the file that used to do that
holds the decisions that were untestable.

## Consequences

- `RemoteBackupServiceImpl` no longer mentions the vendor, and the allowlist says so:
  `backup/SupabaseBackupBucket.kt` replaced it.
- A class that holds a project interface and needs the SDK gets split — and the split is not only
  about the gate: the part above the seam becomes testable, which is the point.
- `BackupBucket` takes a path and no user id, on purpose. A port that computed the prefix
  would put the decision where nobody could see it.
- The bucket's own calls are still untested — they are the vendor's REST shape. What is tested is
  what the project decides about them.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupBucket.kt` — the port.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/SupabaseBackupBucket.kt` — the seam.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/RemoteBackupService.kt` — the port and the decisions.
- `shared/src/commonTest/kotlin/com/singularity/todo/core/backup/RemoteBackupServiceImplTest.kt` — the assertions.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/VendorSdkConfinementTest.kt` — the allowlist.
- `docs/decisions/2026-10-24-one-vendor-file-per-seam.md` — the rule this record replaces.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/error/TransportFailure.kt` — the four classes of failure.
