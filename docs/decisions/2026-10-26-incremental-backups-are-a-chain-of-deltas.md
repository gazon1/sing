---
title: "Incremental backups are a chain of sealed deltas inside one container"
date: 2026-10-26
status: accepted
tags: [backup, crypto, cloud]
---

# Incremental backups are a chain of sealed deltas inside one container

## Context

A backup is a full snapshot: `BackupExporter` reads every row of the account, serializes
the whole payload, seals it and uploads it. The ADR that defined the format said so out
loud — "No incremental backup — every export is a full snapshot" — and that was a
reasonable price when a backup was a button a person pressed now and then.

It stops being reasonable when the backup is on a schedule. A cron job that re-uploads
the whole account every hour spends the network, the battery and the bucket on bytes that
have not changed, and the larger the account the worse the ratio — the archive of an
account with attachments is tens of megabytes, and the hourly change is a handful of rows.
The point of a scheduled backup is that it is cheap enough to run often; a full snapshot
per run is the opposite of that.

The pieces for a cheaper one already exist, and they exist in the sync feature:

- a **cursor** — `SyncStateRepository` keeps `lastLsn`, the position in the server's
  event feed this device has already seen, and the pull loop reads forward from it;
- a **shadow** — `sync_shadow` holds, per entity, `confirmed_json` (what the server is
  known to hold), `in_flight_json` and `server_version`;
- a **patch builder** — `SyncPatchBuilder` already computes the difference between an
  entity's current state and the shadow.

So the question is not "can we compute a delta" — the app already does, for sync. The
question is what a delta *is* when it has to be sealed, stored in a bucket and opened
again by a device that may not be the one that wrote it.

And that question has a hard constraint the sync feature does not: **the archive is
encrypted with AES-256-GCM, and a GCM stream cannot be appended to.** The tag authenticates
the whole ciphertext as one unit; a new delta cannot be bolted onto a sealed archive
without re-sealing everything, which is exactly the full snapshot the incremental backup
was meant to avoid.

## Idea

Three shapes were on the table.

1. **A chain of sealed deltas inside one container.** The container gains a version 2. A v2
   archive is a base — the first full snapshot — followed by zero or more *entries*, each
   an independently sealed delta carrying the cursor it applies at. A restore reads the
   base, then applies the entries in order. Each entry has its own nonce and its own tag,
   so "sealed" still means "the person's password opened it".
2. **A manifest plus blobs in the bucket.** The bucket holds a small encrypted manifest and
   a set of content-addressed blobs; a backup writes only the blobs whose hash is new. This
   deduplicates across backups and across accounts, but it turns the backup feature into a
   second storage engine: it needs a blob index, a garbage collector for orphaned blobs,
   and a policy about who may read a blob that two accounts share.
3. **Nothing — keep full snapshots, make the schedule coarse.** Upload the whole archive
   nightly instead of hourly. Cheap to build, and it keeps the format as it is, but it
   concedes the goal: the backup is fast only because it is rare, and a person editing
   through the day is backed up by last night's state.

## Decision

**An incremental backup is a chain of independently sealed deltas inside one container, and
the container's version is bumped to 2.**

The container keeps a version byte precisely so that a change of *meaning* is a new version
and every later build still reads the old ones — the rule the format's own ADR fixed. This
is the first test of that rule: version 1 stays exactly as it is and stays readable; version
2 is what an incremental archive is, and a build that reads v2 reads v1 as well.

That promise has a mechanical consequence the first draft of this record missed: the reader
cannot check `version != VERSION`. It has to know the **set** of versions it reads, route v1
to the shape v1 always had and v2 to the chain — so `VERSION` becomes a set with a "write
this one" member, not a single constant, and the refusal names the version the archive has
against the versions this build knows.

The shape:

```
magic (4) | version=2 (1) | salt (16) | nonce (12) | head (12)
          | entry*: index (4) | length (4) | nonce (12) | ciphertext + tag
          | base ciphertext + tag            (to the end of the stream)
```

**The base is last, and that is a consequence of streaming, not a preference.** A
streamed base cannot state its own sealed length before it is sealed, and the head
has to be written — and authenticated — before anything else. Everything that *is*
length-framed (the entries) can be measured first; the base cannot, so it runs to
the end of the stream, where the cipher's own end-of-stream tag already says where
it stops. The first draft of this record had the base first; implementing it is
what showed the framing could not work that way.

- **the head** is a fixed, *authenticated* field inside the base's covered data: the number
  of entries in the chain and their total length. It is what makes a **truncated chain**
  detectable. Per-entry GCM with an index inside the frame catches a reordered or inserted
  entry, but dropping entries leaves a shorter chain whose every surviving entry still opens,
  and the reader would restore an earlier state believing it is the current one. The head is
  the one place the chain's length is bound into the base's tag, so dropping entries makes
  the chain disagree with its own head and the archive is refused by name.
- **the base** is the full archive at the moment the chain started, sealed with the header as
  its authenticated data, and written last.
- **an entry** is a sealed delta — the patches since the entry before it, stamped with the
  cursor they apply at — carrying its own nonce and its own tag. Its authenticated data is
  the header **prefix** (magic, version, salt, nonce) plus its **index**: the prefix binds
  the entry to this archive, and the index catches a swap. The head cannot be in that data,
  because the head is computed from the entries' sealed lengths. An entry whose index or
  length is wrong is refused as a **broken chain**, by name. An entry whose tag does not
  match is reported as the **password** — the same ambiguity version 1 has always had: a
  tag that fails is a wrong key or damaged bytes, and the cipher cannot say which. The
  chain does not make that worse, and this record does not pretend it fixed it.

**The device key is a version 3 concern, not a v2 field.** The key that lets a
scheduled export run without a person (below) is wrapped under the password and has
to travel in the container, so it was tempting to say it lives in the v2 header. It
does not, and the first version of this record was wrong to say so: v2 was written
to buckets before any key block existed, so a v2 container has no such block and a
reader cannot expect one. Adding it is a **new version**, by the same rule that
made v2 a version: a new kind of bytes is a new meaning. The rule stands — a
version is never reused and old ones stay readable — so the key block arrives as
version 3, and v2 archives keep opening exactly as they are.

**The cursor is the archive's own, not the sync engine's.** The chain records where in the
*backup* it stands — which is a fact the backup feature owns. It may be read from the same
monotonic clock the sync feed uses, but it is written into the archive by the export, and
the next export diffs against the last entry the archive actually carries. A backup that
leans on `sync_state.lastLsn` directly would let a profile switch or a cleared sync state
silently move the backup's own position, and the failure mode is the worst one: a delta that
claims to follow a base it does not follow.

**The base is re-sealed on a bound.** A chain that only ever grows is a chain that never
shrinks: a restore reads base plus every delta, and a year of deltas is a restore that is
slower than the full snapshot it replaced. So the export starts a fresh base when the chain
passes a bound — a count of entries, or a size — and the old chain is the previous archive.
The bound is a constant of the feature, not a knob; the first number is chosen when the
first chain is measured.

**The background key is a key the person does not type.** Everything above assumes the
export runs with the password in hand, because today the export *is* the person pressing a
button. A scheduled export has no person. Three options:

1. **Store the password** — rejected. It is the one thing the encryption ADR refuses, and
   for good reason: a stored password is a copy of the key, and the whole point is that
   there is no second copy.
2. **A separate, device-held key for scheduled backups** — chosen. A random 256-bit key
   generated on first use and kept in the platform keystore (the same `SecureStoragePort`
   the session already uses), never derived from the password and never sent anywhere. The
   archive is sealed with *this* key, and the key travels with the archive in a form only
   the password can open: a small header, sealed under the password, wrapping the device
   key. So the password is asked **once per device**, and the scheduled runs use the device
   key.
3. **No scheduled export; incremental only on demand** — kept as the fallback. If the
   device key cannot be stored safely on a platform, that platform gets no schedule and
   the incremental export stays a button.

The chosen key arrangement is a new container concern, so it is a **version 3** concern in
the container's own terms if it needs its own bytes; if it can be expressed as a field
inside the v2 header, it is part of v2. The decision is recorded here so the version bump
is made once, deliberately, and not discovered later in the middle of an implementation.

## Rationale

**The chain keeps "sealed" meaning one thing.** Every entry is an ordinary GCM ciphertext
with its own nonce and tag, opened by the same port. There is no new cryptography, no new
key for the common case, and a restore that can open one entry can open all of them. Option
2 would have made the archive a storage engine with its own index and its own garbage, and
a backup feature that grows a blob store is a feature with two jobs and one owner.

**The cursor lives with the archive.** Reusing `sync_state.lastLsn` looks free and is not:
the backup would be reading the sync feature's bookkeeping as if it were its own, and the
two would drift the first time a profile switch or an erase moves one and not the other. The
backup's position is a fact about the backup, and it belongs in the backup.

**A fresh base is not a failure of the incremental design; it is part of it.** The goal is
a cheap *common* run, not a restore that is always a chain. Bounding the chain keeps the
restore bounded too, and it means a corrupt entry costs at most the chain since the last
base rather than the whole archive.

**The device key is the honest way to have a schedule.** The password is the only secret
that cannot be stored, because storing it removes the property the encryption exists for.
A *separate* key that is stored *and wrapped by the password* keeps the property for the
case that matters — a bucket read by someone who is not the person — while letting the
schedule run without a person. The password is asked once, on the device, to unwrap the key;
after that the key is as protected as the keystore, which is as protected as the device.

## Consequences

- The container learns a **set** of readable versions, not one constant: it routes v1 to the
  v1 shape and v2 to the chain, and refuses anything else by name with both numbers. Bumping
  `VERSION` alone would break v1 readability, which is the promise this record exists to keep.
- `EncryptedArchive` gains a chain reader and a chain writer: open(base, entries…) and
  seal(base, entry…). The whole-archive and streaming shapes both have to learn it.
- The archive's manifest and payload gain the cursor the chain stands at; the payload
  `schemaVersion` moves with the data model as before, and the container version moves
  only with the envelope.
- `BackupExporter` learns to read only what changed since the last entry — a filter over
  the same DAOs it reads today, keyed by the archive's own cursor, not by `sync_state`.
- A restore that finds a broken entry refuses the archive by name; it does **not** apply
  the entries before the break and call the result restored.
- A scheduled export needs the device key path: a key generated on first use, kept in
  `SecureStoragePort`, and wrapped in the archive under the password. Until that exists,
  the schedule stays off and the incremental export stays a button.
- The chain's length bound is a constant, chosen from the first measured chain, not a
  setting a person can raise until the restore is unusable.
- Attachment bytes are the elephant: today they are copied whole into the archive, and a
  delta that re-sends a changed attachment is a delta as large as the attachment. The first
  incremental archive covers the entity payload; attachments move to the blob option (or
  stay whole) in a decision of their own, and the bound above is what keeps that decision
  from being urgent.

## Links

- `docs/decisions/2026-10-25-the-archive-format-has-versions.md` — the rule this is the first test of: a version is a meaning, and an old one stays readable.
- `docs/decisions/2026-10-25-the-cloud-archive-is-encrypted-with-the-users-password.md` — why the key arrangement is constrained the way it is.
- `docs/decisions/2026-09-18-backup-format.md` — the format that named "no incremental backup" as a known cost.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/EncryptedArchive.kt` — the container and its version byte.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupExporter.kt` — where the delta filter would live.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncShadow.kt` — the shadow this deliberately does not borrow.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/PullPhase.kt` — the cursor the sync feature keeps, and the reason the backup keeps its own.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/security/SecureStoragePort.kt` — where the device key would live.
