---
title: "A device key, a password that wraps it, and one push path"
date: 2026-10-27
status: accepted
supersedes: 2026-10-26-a-scheduled-export-seals-under-a-device-key
tags: [backup, crypto, security]
---

# A device key, a password that wraps it, and one push path

## Context

The backup feature had exactly one way to write an archive into the cloud: a person
presses a button, types a password, and the archive is sealed under a key derived
from that password.

That works for a person and cannot work for a schedule. The daily backup job runs
without anyone there: it makes a local archive on the device, and that archive dies
with the device. Pushing it needs a key, and the only key the feature knew was a
password — which requires a person.

Three things were being asked of the password at once: it is the key the content is
sealed under, it is the only secret the user has to remember, and it is present at
the moment of the push. The third one is the problem.

The obvious fix — store the password so the schedule can use it — removes the
property the encryption exists for. The bucket's policies keep one account's
archives away from another account's reach, but a service-role key reads the whole
bucket, and an archive sealed under a stored password is a database dump in plain
text for anyone who gets at the device's storage. The password is the one secret
that cannot be kept, and that is exactly why it was the key.

When the device key arrived, it brought a second copy of the push with it. A
**push by hand** went through `RemoteBackupService.upload`: the service sealed the
base and the changes that follow it into one container and sent that. A **scheduled
push** went through `uploadSealed`: the repository sealed the container itself —
under the device key, with no person in the room — and then handed the finished
bytes to a second call that only moved them.

The two paths were not equal, and the difference was not the one they were supposed
to differ in. The scheduled path sealed the **snapshot alone**: `entries =
emptyList()`. The chain the export had built never reached the bucket, so the daily
backup was the size of a whole archive while the manual push was the size of a
change. The feature's whole point — that a daily backup costs a change and not an
account — was true for one of the two paths that existed.

## Idea

Separate the two roles the password was playing, and pass the **key** as a value so
that one call does the work.

The two pushes differ in exactly one thing: the key the content is sealed under. A
person has a password; a schedule has the device key and no person. Everything else
— what is sealed, in what order, into which object, under which name — is the same
work and should happen in the same place.

## Decision

### The key is a device key, and the password wraps it

- The **content** is sealed under a **device key** — a random 256-bit key generated
  on first use and kept in the platform keystore, never derived from the password
  and never sent anywhere.
- The **password** wraps that device key, once, and the wrapped key travels in the
  container's header, with its own salt.
- A person restores an archive with their password; a scheduled run writes it with
  the key. The password is asked once, when the device is authorized, and is not
  kept.
- The container is **version 3**. A version is a meaning and is never reused: a
  version-2 archive still opens under the password, a version-1 archive still
  opens, and a version-3 archive opens under the password too, because the password
  unwraps the key.

### One push path, and the key is a value

- `ArchiveSealer` is a sealed type with two cases: `UnderPassword` (the person's
  password) and `UnderDeviceKey` (the device key and the block that carries it).
- `RemoteBackupService.upload(archive, userId, sealer)` seals the base and the
  changes into one container and sends that. The service owns the sealing, the
  working file, and the wiping of the secret.
- `uploadSealed` is gone. A caller that has already sealed something has no
  business in this feature: the sealing is the archive's business, and the only two
  callers are the two pushes.
- The repository no longer holds an `EncryptedArchive`: it decides **which key** a
  push uses, and the service does the sealing.
- Both pushes carry the **chain**. The scheduled one differs in the key and in
  nothing else.

## Rationale

**The duplication was the bug.** Two copies of the same work is where the difference
crept in, and the difference that crept in was the one that made the feature
useless on the path that matters most: the daily run, the one that happens when
nobody is looking.

**A flag would have been worse.** `upload(archive, userId, alreadySealed: Boolean)`
would have put a branch in the middle of the path that does the encryption, and the
branch would have to know which of two quite different objects it was looking at.
The key is a value, so the branch is in one place — the sealing — and it is a
`when` over two cases that the type system keeps apart.

**The secret has one owner.** The call that takes the secret wipes it: the array is
filled with zeroes before the call returns, whether the call succeeded or not. A
password that stays in memory after the upload is a password that can be read out of
a heap dump, and a rule with two owners is a rule that one of them will forget.

**The chain travels both ways.** The export builds a chain; the restore applies it;
a push that carries only the base throws the chain away, and the daily backup
becomes the size of an account. The scheduled push now carries the same chain the
manual push does.

**The password is still the key of the content, and still the one secret the person
has.** The device key does not weaken that: the container carries the key wrapped
under the password, so an archive in the bucket is ciphertext to anyone who gets at
the bucket, and to anyone who gets at the device's storage.

## Consequences

- `RemoteBackupService` lost a call and gained a parameter. The port's contract
  changed, and the change is a decision worth writing down.
- `BackupRepositoryImpl` lost its `EncryptedArchive`: it names the key, and the
  service does the sealing. The DI module follows.
- `DeviceKeyStore` is a port with one implementation, `SecureStorageDeviceKeyStore`.
  The key is hex in the keystore, the block is hex in the keystore, and neither is
  derived from the password.
- `BackupRepository` has `authorizeScheduledBackup`, `isScheduledBackupAuthorized`
  and `pushScheduled`. The password is consumed by the first and never appears in
  the other two.
- The backup screen has one button and one dialog: the password is asked once, and
  the screen says afterwards that the device backs up on its own.
- The object in the bucket keeps its name in both paths — the archive's own name —
  so a push by hand and a scheduled push of the same archive write to the same
  object. The later one wins, and that is intended: it is the same backup, and two
  objects for one archive would be a copy nobody asked for.
- The scheduled push now depends on the export's bookkeeping, like the manual one.
  When the chain cannot be assembled the archive goes up alone — the same rule both
  paths follow.
- A password change does not reach the archives already in the bucket: each
  container carries the block that was wrapped when it was written. The device
  re-wraps the key for what it writes next, and the older copies keep asking for
  the older password. That is a property of "the password wraps the key", and it
  is spoken about in the screen rather than hidden.

## Links

- `docs/decisions/2026-10-27-a-backup-delta-is-a-mergeable-payload-of-rows.md` —
  what a change is, and why it is a set of rows.
- `docs/decisions/2026-10-26-incremental-backups-are-a-chain-of-deltas.md` — the
  chain the push carries.
- `docs/decisions/2026-10-26-the-backup-failures-have-three-families.md` — the
  failures the sealing reports.
