---
title: "The cloud archive is encrypted on the device, with the user's password"
date: 2026-10-25
status: accepted
tags: [backup, security, cloud]
---

# The cloud archive is encrypted on the device, with the user's password

## Context

The cloud backup feature pushed the archive to a Supabase Storage bucket and could fetch it
back. The bucket's policies keep one account's archives out of another account's reach, and the
tables' row-level policies govern the rows — but neither governs the object store. A service-role
key reads the whole bucket, and a zip read that way is a database dump in plain text: tasks,
notes, projects, attachments, all of it, in a file anybody with the bucket's keys can open.

The archive's own content is not the only thing at stake. The archive is the *whole* account —
the one file that contains everything the app knows about a person. Everything else the app
stores is protected by a policy that the server's operator administers; the archive was the one
place where the operator's own key was enough.

## Idea

The key does not have to be the server's. If the archive is sealed **on the device**, before it
leaves, then what sits in the bucket is ciphertext and the person who pushed it is the only one
who can open it. The server never sees the key, so the server's operator — however much access
they have — cannot read what they store.

The key is the person's **password**: something they already have, something they already
remember, and something the server does not have and cannot ask for.

The cost is honest and has to be named: a forgotten password is a lost archive. There is no
recovery path, because a recovery path would be a second copy of the key somewhere else, which
is the thing this decision removes.

## Decision

**The archive is encrypted on the device, under a password the person types; the bucket holds
ciphertext and the server never sees the key.**

| Part | Where it lives | Why there |
|---|---|---|
| the container's format | `core/backup/EncryptedArchive.kt` | a project decision, and it is testable without a cipher |
| the cryptography | `core/security/ArchiveCrypto.kt` — a port, with a JVM and an Android implementation | `javax.crypto` may not be used from `commonMain` |
| the key derivation | PBKDF2-HMAC-SHA256, 210 000 iterations, a 256-bit key | a password is short and guessable; the cost is what makes guessing it expensive |
| the cipher | AES-256-GCM with a 128-bit tag | the tag is what tells a wrong password from a right one |
| the container | `magic (4) \| version (1) \| salt (16) \| nonce (12) \| ciphertext + tag` | the header says what the bytes are and how to read them |
| the authenticated data | the whole header | the salt and the nonce travel in the clear, so they must be covered by the tag |
| the password's life | asked at the push and at the restore, and nowhere else | a key that is stored is a key that can be stolen |

The object in the bucket carries the suffix `.enc`, and the local copy that comes back is a zip
again: the device holds the archive, the bucket holds the envelope.

The **name** of the object is not encrypted. A bucket listing shows the file name and its
timestamp, and it always will — the name is what the list is sorted by and what the download
addresses. What is protected is the content: the archive's rows, notes and attachments.

The **size** is not encrypted either, and it cannot be: the bucket reports how much it stores.
A listing therefore tells an operator how big the archive is — a rough answer to "how much is
in there", and nothing about what.

## Rationale

**The device is the only place the key can be.** Any scheme where the server can decrypt has the
server's operator in the trust boundary, and that operator is exactly the party the archive is
meant to be hidden from. A password that never leaves the device is the only arrangement in
which "the bucket's owner cannot read the archive" is a true sentence.

**The password is the key the person already has.** An app-generated key would have to be stored
somewhere — the keychain, the settings, the server — and every one of those is a place the key
can leak from. A password is a secret the person keeps, and asking for it is a thing the app can
do without inventing a vault.

**The container is the project's own, and lives above the cipher.** What the bytes look like, which
part is authenticated and in what order — those are decisions of this project, not of a library,
so they live in `commonMain` where a test can check them without a network, a cipher or a device.
The cryptography itself is a port, because `javax.crypto` may not be reached from `commonMain` and
because a test of the container should not pay for 210 000 key derivations.

**The header is the authenticated data.** The salt and the nonce travel in the clear — they have
to, so that the key can be derived and so that the nonce is known. If they were not covered by
the tag, they could be swapped, and the archive would open with the wrong content or would not
open at all. Covering the header is what makes the container honest about what it is.

**The password is not kept.** Not in the state, not in the settings, not in the keychain. The
array that carries it is wiped when the call that consumed it returns, so a failed upload does
not leave the secret lying in memory for the next attempt. The price — a person who has to try
again types it again — is smaller than the price of a stored key.

What the runtime keeps is the field's own text, for as long as the field lives, and the immutable
copy Compose makes of it. A secret in memory cannot be made to vanish on demand — it can only
stop being reachable — and what this decision promises is the second thing.

**A wrong password is named.** The GCM tag does not match, and that is what the app says: "the
password does not open the archive". The alternative — a generic "the download failed" — sends
the person to look at a network that is working perfectly.

**An archive from before this is refused by name.** The magic bytes say "this is an encrypted
archive", so an archive pushed before the encryption existed is answered with a sentence that
names the problem instead of a checksum mismatch.

## Consequences

- The push and the restore both ask for a password. The dialog is one, and it is asked at the
  moment of the action — never remembered between them.
- A forgotten password is a lost archive. There is no recovery, and there will not be one: a
  recovery path is a second copy of the key.
- The archive's **name** is still visible in the bucket. Someone who can list the bucket can see
  that an account has archives and when they were written — but not what is in them.
- The bucket's contents are now useless to the operator of the project, on purpose. A backup that
  the operator can read is not a backup the user controls.
- The archive that comes back from the cloud is a plain zip on the device again: the encryption
  protects the copy in the bucket, not the file the person already has.
- `BackupError` grew four cases (`NotEncrypted`, `WrongPassword`, `UnsupportedArchiveVersion`,
  `MissingPassword`), and the remote service stops re-classifying them into "the upload failed" —
  a failure a person can act on must not be flattened into one they cannot.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/EncryptedArchive.kt` — the container.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/security/ArchiveCrypto.kt` — the cryptography's port.
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/security/JvmArchiveCrypto.kt` — the desktop's implementation.
- `shared/src/androidMain/kotlin/com/singularity/todo/core/security/AndroidArchiveCrypto.kt` — Android's implementation.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/RemoteBackupService.kt` — where the archive is sealed and opened.
- `shared/src/commonTest/kotlin/com/singularity/todo/core/backup/EncryptedArchiveTest.kt` — the container's assertions.
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/security/JvmArchiveCryptoTest.kt` — the round trip against the real cipher.
- `docs/decisions/2026-10-24-the-storage-seam-is-the-bucket.md` — the seam this feature sits on.
