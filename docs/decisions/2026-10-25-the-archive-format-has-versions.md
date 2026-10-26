---
title: "The archive's format has versions, and the older ones stay readable"
date: 2026-10-25
status: accepted
tags: [backup, security, cloud]
---

# The archive's format has versions, and the older ones stay readable

## Context

The encrypted archive is a container with a version byte in its header:

```
magic (4) | version (1) | salt (16) | nonce (12) | ciphertext + GCM tag
```

The version is there because the container is a format and formats change. It is
also a promise, and a promise nobody has said out loud is a promise that gets
broken quietly.

The thing the promise is about is not the file. It is that the archive outlives
the app that wrote it:

- the archive sits in a bucket, and buckets are not emptied when a build ships;
- a person restores from a phone that has not been updated in a year, from an
  archive a desktop wrote a year before that;
- the person who pushed an archive is not the person who restores it — the
  password travels, the app version does not.

So the version byte is not bookkeeping. It is the only thing that lets a build
say "I do not read this one" instead of reading it wrongly.

## Idea

A version is only worth having if there is a rule about what it means. Three
rules are enough:

1. **A version is never reused.** A byte that meant one thing once keeps
   meaning it. A format that renumbers its versions turns every old archive
   into a corrupt one, and the corruption is silent — the container still has
   the right magic, the wrong meaning.
2. **A new version has to be a new *meaning*, not a new number.** Changing the
   key derivation or the cipher is a new version; raising the iteration count is
   a new version too, because an archive sealed under the old count cannot be
   opened by a build that assumes the new one. Nothing else is.
3. **A build reads every version it can and refuses the ones it cannot, by
   name.** The refusal says which version the archive has and which the build
   knows. "The archive did not open" is not an answer a person can act on.

## Decision

**The container carries a version, the version is never reused, and a build that
cannot read a version refuses that archive by name rather than trying.**

| Rule | What it means in practice |
|---|---|
| version 1 | AES-256-GCM with a PBKDF2-HMAC-SHA256 key, header as the authenticated data |
| a new version | a change of meaning — a different cipher, a different derivation, a different header |
| an old version | still read by every later build |
| an unknown version | refused, with both numbers in the message |

**An archive written by an earlier build stays readable by every later one.**
That is the load-bearing part. It is a constraint on how the format may change,
and it is the reason a change of cipher is a version bump rather than a
replacement: replacing the cipher would make every archive in every bucket
unreadable, and the buckets are the only copy of some people's backups.

**The archive's own schema is versioned separately and stays that way.** The zip
inside carries the manifest's format version and the payload's schema version,
and those move with the data model. The container's version moves with the
*envelope* — the header, the cipher, the derivation. Conflating the two would
mean that a new column in the database could not be added without breaking
every archive ever pushed, which is exactly the coupling the two versions exist
to avoid.

## Rationale

**The archive outlives the build that wrote it.** A bucket is not emptied when
an app is updated, and a restore happens on a device whose version is nobody's
to choose. A format that only its own writer can read is a format whose
archives have an expiry date, and the person who loses is the one who trusted
the backup.

**A silent misread is worse than a refusal.** A wrong password already fails
loudly — the tag does not match. A version that means something else would not:
the container would open, the salt would be in the wrong place, and the result
would be garbage that looks like a damaged archive. Refusing by name turns that
into a sentence.

**"I do not read this one" is a real answer.** The alternative — trying, and
reporting a checksum mismatch — sends the person looking for a broken upload
instead of an old archive.

**Not every change is a version.** The iteration count is in the same category
as the cipher, not in the category as a new column: it changes what the bytes
mean. The rule is deliberately blunt, because a rule with exceptions is a rule
that gets exceptions.

## Consequences

- `BackupError.UnsupportedArchiveVersion` names both versions — the one the
  archive has and the one this build reads — and it is raised before any
  password is tried.
- The golden archive pins version 1 byte for byte, so a change to the header's
  layout cannot pass unnoticed: the golden test would have to be replaced, and
  the replacement is a decision with a name.
- Adding a field to the header is a version bump, and the old reader must keep
  working. That is the cost, and it is the point.
- The manifest's and the payload's versions keep moving with the data model; the
  container's version moves only with the envelope. A new column in the
  database does not touch it.
- The version byte is authenticated: it is part of the header, and the header is
  the authenticated data. A version cannot be rewritten in an archive without
  the tag failing, so a container cannot be talked into being another version.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/EncryptedArchive.kt` — the container, its version byte and the refusal.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/security/ArchiveCryptoParams.kt` — the parameters version 1 means.
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/ArchiveCryptoGolden.kt` — the archive that pins version 1.
- `docs/decisions/2026-10-25-the-cloud-archive-is-encrypted-with-the-users-password.md` — why the archive is sealed at all.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupFormat.kt` — the manifest's own versions, which move separately.
