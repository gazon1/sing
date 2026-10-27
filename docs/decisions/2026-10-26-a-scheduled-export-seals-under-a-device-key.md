---
title: "A scheduled export seals under a device key, and the password wraps that key"
date: 2026-10-26
status: superseded
superseded-by: 2026-10-27-a-device-key-and-one-push-path
tags: [backup, crypto, security]
---

# A scheduled export seals under a device key, and the password wraps that key

## Context

The backup feature had exactly one way to write an archive into the cloud: a
person presses a button, types a password, and the archive is sealed under a key
derived from that password.

That works for a person and cannot work for a schedule. The daily backup job runs
without anyone there: it makes a local archive on the device, and that archive
dies with the device. Pushing it needs a key, and the only key the feature knew
was a password — which requires a person.

Three things were being asked of the password at once: it is the key the content
is sealed under, it is the only secret the user has to remember, and it is
present at the moment of the push. The third one is the problem.

The obvious fix — store the password so the schedule can use it — removes the
property the encryption exists for. The bucket's policies keep one account's
archives away from another account's reach, but a service-role key reads the
whole bucket, and an archive sealed under a stored password is a database dump in
plain text for anyone who gets at the device's storage. The password is the one
secret that cannot be kept, and that is exactly why it was the key.

## Idea

Separate the two roles the password was playing:

- the **content** is sealed under a **device key** — a random 256-bit key
  generated on first use and kept in the platform keystore, never derived from
  the password and never sent anywhere;
- the **password** wraps that device key, once, and the wrapped key travels in
  the container's header.

The container grows a third version for this. A version-3 container carries a
**key block** — salt, nonce and the wrapped device key — in its header, ahead of
the chain's entries. The content is sealed under the device key; the password
unwraps the key block, and the unwrapped key opens the content. The block has a
salt of its own, so it is wrapped **once per device** and not once per archive.

## Decision

**Version 3 is the device-key container**, and it is what a scheduled export
writes.

| Version | Content is sealed under | Key block |
|---|---|---|
| 1 | the password | — |
| 2 | the password | — |
| 3 | the device key | the device key, wrapped under the password |

**The device key lives in `SecureStoragePort`** — the Android keystore, the
desktop keyring — under `DeviceKeyStore`. It is not in a file next to the
archives it opens.

**The password is asked once**, when the person authorizes the device. It wraps
the device key; the wrapped block is kept; the password itself is wiped by
whoever received it, exactly as `push` and `restoreFromCloud` wipe theirs.

**The daily job pushes only when the device is authorized.** `BackupJob` asks the
repository `isScheduledBackupAuthorized()` and does nothing further when the
answer is no: a device with no key would produce an archive nobody can open, and
"an archive nobody can open" is worse than "no archive".

**The already-sealed container is uploaded as bytes.** `RemoteBackupService` gains
`uploadSealed(localPath, remoteName, userId)`: the ordinary `upload` seals the
archive under the password, so it is not usable here — the scheduled path has
already sealed it under the device key. The name in the bucket is the archive's
own name, not the name of the working file the sealed copy sits in.

**Restoring a version-3 container needs the password and nothing else.** The
password unwraps the key block; the key opens the content. This is why the
password is still the thing the person has to remember: forgetting it loses the
archives, and the price is stated rather than hidden.

## Rationale

**The device key is not a stored password.** It cannot be typed into a field by
somebody who found the phone, it is never sent anywhere, and it does not have to
be derived from anything the user chooses. What it does not give is *recovery*:
the key is the only copy of itself, and losing the device loses the archives
sealed under it. That is the honest price, and the person is told it when they
authorize.

**The block is wrapped once, not per archive.** The block travels in every
archive the device writes, so it cannot be tied to one archive's salt — a block
tied to an archive's salt would have to be re-wrapped for every export, which
is the cost the design was meant to remove. Its own salt is what lets it travel.

**The version is a meaning, so this is a new version and not a flag.** The
existing rule — a version is never reused, and every build reads every version
it shipped with — holds. A version-2 archive still opens under the password; a
version-1 archive still opens; a version-3 archive opens under the password too,
because the password unwraps the key.

**The authorization is the repository's business.** The job asks a question
("may this device push?") and does not know what a key is. The screen asks the
same question and says so in words. A UI that knew about key blocks would have
turned a decision into a dialog about cryptography.

**The already-sealed upload moves bytes and nothing else.** `upload` and
`uploadSealed` are separate calls because they are separate facts: one seals,
the other does not. Folding them into one call with a flag would have put a
branch in the middle of the path that does the encryption — and the encryption
is the part that has to be right.

## Consequences

- `DeviceKeyStore` is a port with one implementation, `SecureStorageDeviceKeyStore`.
  The key is hex in the keystore, the block is hex in the keystore, and neither
  is derived from the password.
- `BackupRepository` gains `authorizeScheduledBackup`, `isScheduledBackupAuthorized`
  and `pushScheduled`. The password is consumed by the first and never appears in
  the other two.
- The backup screen grows one button and one dialog: the password is asked once,
  and the screen says afterwards that the device backs up on its own.
- The archives the device writes are version 3 and are opened by the ordinary
  restore path with the person's password — no separate restore flow.
- Forgetting the password loses the scheduled archives, and so does losing the
  device. Both are stated in the dialog that asks for the password.

## Links

- `docs/decisions/2026-10-25-the-archive-format-has-versions.md` — the rule that a version is a meaning.
- `docs/decisions/2026-10-26-the-backup-failures-have-three-families.md` — the container's parts and the failures.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/DeviceKeyStore.kt` — the key and its block.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/ArchiveFormat.kt` — the versions and their shapes.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/work/BackupJob.kt` — the scheduled run.
