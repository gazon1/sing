---
title: "Every directory in the graph is qualified: Koin resolves a String by type"
date: 2026-10-24
status: accepted
slug: every-directory-in-the-graph-is-qualified
supersedes: 2026-09-07-backup-directory-via-koin-string
---

# Every directory in the graph is qualified: Koin resolves a String by type

## Context

The backup feature did not work, and the failures did not look like one bug. They looked like four:

- creating a backup failed with `EROFS` — a read-only filesystem;
- the error was swallowed: the screen showed nothing and said nothing;
- the list of backups contained a **log archive**;
- pushing a backup to the cloud did nothing at all.

Three of those four come from one place. The platform modules published their directories as plain
strings:

```kotlin
single<String> { get<Context>().filesDir.absolutePath + "/backups" }
single<String> { get<Context>().filesDir.absolutePath + "/logs" }
```

Two definitions of the same type, without a qualifier, share one key. Koin resolves a duplicate by
**overriding** the first with the second, and `allowOverride` defaults to true — so the override
happens silently, and `get<String>()` returns the *logs* directory.

Everything that asked for "the directory" got the log directory:

| What asked | What it got |
|---|---|
| the backup repository | the log directory |
| the log bundle exporter | the log directory |
| the attachment storage | the log directory |

That is why a log archive appeared under "Local backups", and why attachments were written into the
logs folder.

There is a fourth thing in the same shape. `AttachmentStorage` took its directory as a plain
`String`, and it was bound with `factoryOf(::AttachmentStorage)` — which resolves **every**
constructor parameter *by type*. So the attachments were written wherever the unqualified `String`
pointed, which was the log directory. Nothing said so.

## Decision

**Every directory in the graph carries a qualifier, and every consumer names the one it wants.**

```kotlin
val BackupDirQualifier = named("backup_dir")
val LogDirQualifier = named("log_dir")
val AttachmentDirQualifier = named("attachment_dir")
```

The platform modules publish the three, and the consumers ask:

```kotlin
single<BackupRepository> {
    BackupRepositoryImpl(
        ...
        backupDir = get(BackupDirQualifier),
    )
}

factory { AttachmentStorage(fs = get(), dir = get(AttachmentDirQualifier)) }
```

Two rules follow from this:

1. **A directory is never a bare `String` in a constructor.** If a class needs a path, the path
   arrives under a qualifier. A bare `String` parameter is resolved by type, and a type is not
   specific enough to mean "a directory".
2. **The backup repository owns the backup directory.** The caller passes a *file name*; the
   repository joins it to the directory it owns. A path is rejected rather than followed, so no
   caller can aim the archive at the filesystem root — which is what turned a typo into `EROFS`
   instead of a loud, explainable failure.

## Consequences

**Qualifying the strings broke the graph, and the device said so.** The unqualified `String` was
load-bearing in a way nobody had noticed: `factoryOf(::AttachmentStorage)` was resolving it. The
moment both directories became qualified, the attachment factory had nothing to resolve and the
graph stopped building — the Backup screen killed the app with
`NoDefinitionFoundException: No definition found for type 'java.lang.String'`.

This is the argument for the change rather than against it: the dependency was real, it was wrong,
and nothing in the build said so. The fix names the directory explicitly.

**The parity test had the hole.** `TestPlatformModuleParityTest` compares the production platform
module with the test one — and it *excluded* `String`, because the two modules used to bind
different ones. It compared simple type names, so three qualified `String` definitions collapsed
into one entry anyway. The test module kept a single unqualified `String` while production had
none, and the graph asked for "whatever `String` is there".

The parity check now compares the directories **by qualifier**, and the test module binds the same
three. `AndroidGraphResolutionTest` resolves `AttachmentStorage` and `BackupRepository` from the
real Android graph, so a missing directory fails there rather than on a phone.

**What is still unverified.** The backup directory and the attachment directory are now the paths
the `FileProvider` publishes (`files/backups`, `files/attachments`), which is what makes "Save to
file" able to hand out a URI at all.

## The bucket is a separate kind of setting

The upload to the cloud needs three things in the Supabase project, and only one of them is
obvious:

1. the project itself — URL and anon key, both at build time;
2. a bucket named `backups`;
3. **policies on `storage.objects` that let a signed-in user write into it.**

The third is the one that is easy to miss. `storage.objects` has row level security on, and a
project with no policies on it denies every insert with "new row violates row-level security
policy" — a message that names the database and says nothing about the setting that is missing.
The bucket has to exist *and* be writable; creating the bucket is not enough.

`supabase/migrations/2026-10-24-backup_storage_bucket.sql` does both, and it is idempotent so it
can be applied to a project that already has the bucket.

The policies are scoped to the bucket **and** to the caller's own uid. The path of an archive is
`"<user id>/<file name>"` and a user id is `"<profile id>/<auth uid>"` for every install that uses
a profile other than the default one, so the policy reads the **last** folder of the path — which is
the uid in both shapes — rather than the first one, which is the profile id and something the server
has never heard of. A policy that checked only the bucket would let any signed-in account read and
delete another account's archives as soon as it could guess the path, and the path starts with the
account's own uid. The path keeps the archives apart on the client; the policy keeps them apart on
the server.

`BackupBucketPolicyTest` reads the migration and fails when a policy on the bucket is not scoped to
the uid — including a positive control, so a rule that cannot fail is caught as well.
