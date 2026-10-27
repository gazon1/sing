---
title: "The backup layer has three failure families, and the container has its parts in separate files"
date: 2026-10-26
status: accepted
tags: [backup, crypto, architecture]
---

# The backup layer has three failure families, and the container has its parts in separate files

## Context

Two things in the backup layer had grown into one file each.

`EncryptedArchive` was one class that held the container's **format** (which bytes
are where), its **framing** (how a change is length-prefixed), its **crypto** (which
key, which nonce, which authenticated data), its **error mapping** (what a failing
cipher means in the project's words) and its **version routing** (which shape a
container of a given version has). It was five hundred lines, and a change to any
of the five had to be reasoned about against the other four.

`BackupError` was one flat list of eleven cases: `NotEncrypted`,
`WrongPassword`, `UnsupportedArchiveVersion`, `Unusable`, `BrokenChain`,
`MissingPassword`, `UnsupportedFormatVersion`, `UnsupportedSchemaVersion`,
`SchemaTooOld`, `ChecksumMismatch`, `FileNotFound`. A caller that wanted to know
whether the **container** could not be opened, or whether the **payload inside it**
was not what it should be, had to know all eleven to tell the two apart — and the
code that did this guessed, in the direction that hurts: a payload that does not
match its checksum was reported as a broken archive, which sent the person to
their password.

## Idea

1. **Three failure families under one root.** `ArchiveEnvelopeError` for the
   container, `BackupPayloadError` for what the container carried,
   `BackupStorageError` for the device's own files.
2. **The container's parts in separate files.** The format's numbers, the framing,
   the head, the version routing, the key session and the error mapping each get
   their own file, and `EncryptedArchive` keeps the shape — what it seals and what
   it opens.

## Decision

**`BackupError` becomes a sealed root with three sealed families**, and the
container's parts move into files of their own.

| Family | What it is about |
|---|---|
| `ArchiveEnvelopeError` | the container: the bytes, the version, the password, the chain |
| `BackupPayloadError` | what the container carried: the manifest, the schema, the checksum |
| `BackupStorageError` | the device's own files: a backup that is not there |

`BackupError` stays the root, so `catch (error: BackupError)` keeps catching
everything — the remote backup service classifies failures at the seam, and that
classification must not change when the failures are reorganised. A caller that
wants to be precise says which family it means.

**The container's parts are split by what they answer:**

| File | Question it answers |
|---|---|
| `ArchiveFormat` | what does a container look like, and what are the bounds |
| `ArchiveFrame` | how is one change framed |
| `ArchiveHead` | how many changes does the chain carry |
| `ArchiveContainer` | which shape does a container of this version have |
| `ArchiveCryptoSession` | which key, and which cipher call |
| `ArchiveErrorMapping` | what a failing cipher means |
| `EncryptedArchive` | what is sealed, and what is opened |

`EncryptedArchive` keeps the public shape it had — `seal`, `open`, `sealChain`,
`openChain`, the streaming variants — and gains the version-3 variants the
scheduled export needs.

## Rationale

**The families are what a person can act on.** A wrong password, a broken
container, a payload that does not match its checksum and a file that is not on
the disk are four different conversations with the person who pressed restore.
One flat list made all four arrive as "the backup failed", and the person had to
guess which one it was.

**The root stays, so the seam does not move.** The remote service catches
`BackupError` and lets the project's own message through. Splitting the family
without keeping the root would have made that catch into three catches and
changed what the crash reporter groups by — for a reorganization, not an
improvement.

**The parts are split by what changes together.** The format's numbers change
when the container's shape changes; the framing changes when a change's length
becomes known; the routing changes when a version is added. Split along those
lines and a version-3 container is a change to `ArchiveFormat`, `ArchiveContainer`
and `EncryptedArchive` — and nothing else has to be read to know it is safe.

**The bounds are constants of the feature, not settings.** `MAX_CHAIN_ENTRIES`
and `MAX_CHAIN_BYTES` live in `ArchiveFormat` next to the numbers they bound. A
person who can raise them can make a restore unusable, and the number is worth
more as a number than as a setting.

## Consequences

- Every call site of the old flat names is migrated to a family: the payload
  failures move to `BackupPayloadError`, the file failures to
  `BackupStorageError`, the container failures to `ArchiveEnvelopeError`.
- The tests that named the old cases name the new families. A test that says
  `ArchiveEnvelopeError.BrokenChain` says which layer refused.
- The chain is refused **at export time** when it passes its bound, rather than
  being written and refused at restore time: the writer refuses to produce an
  archive this very build calls broken.
- The container's numbers are in `ArchiveFormat` and not on `EncryptedArchive`.
  The golden tests read them from there, which is the point: the golden pins
  the format, and the format lives in one file.

## Links

- `docs/decisions/2026-10-26-incremental-backups-are-a-chain-of-deltas.md` — the chain this container now carries.
- `docs/decisions/2026-10-25-the-archive-format-has-versions.md` — the rule that a version is a meaning.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/EncryptedArchive.kt` — the shape.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/ArchiveFormat.kt` — the numbers.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupError.kt` — the root and its families.
