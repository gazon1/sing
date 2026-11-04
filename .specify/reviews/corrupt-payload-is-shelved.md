---
change: corrupt-payload-is-shelved
verdict: pass
date: 2026-11-04
pr: https://github.com/gazon1/sing/pull/1422
reviewed: 41f129b8d
documents: sha256:3026a120ee5cfae81cc8e098c96ae5bbe718a7e61e66a0614a18efdb40f8a7d2
---

## What was reviewed

The change: `openspec/changes/corrupt-payload-is-shelved/` (proposal, spec delta on
`offline-sync` REQ-OS-032, tasks), the ADR
`docs/decisions/2026-11-04-a-corrupt-payload-is-skipped-and-shelved.md`, and the code it
describes — `PullEventHandler` (the classification), `PullPhase` (`recordCorruptEvent`,
`trimInboundShelf`), `SyncDeadLetter` (the inbound key, `insertInboundIfAbsent`,
`bumpInboundDelivery`, `pruneInboundTo`, the prefix-filtered `delete`), and the two fake
DAOs the tests run against.

`documents:` is the digest of the change's contract documents, recomputed by
`python3 scripts/check-review-verdicts.py --digest corrupt-payload-is-shelved`. It is the
value the gate compares against, and it is what makes this verdict a statement about the
documents in the diff rather than about a description of them.

## What this verdict is, and is not

It records the second pass: the findings from the first pass, and what was done about
each. The gate run is **not** part of it — this verdict was written in a session whose
sandbox refuses Gradle, so `:shared:jvmTest` and `detekt` were not run here. CI runs them
on this tree, and `proposal.md` and `tasks.md` say the same thing rather than claiming a
green run that did not happen.

## The findings and their dispositions

- **Unearned verification claim** — fixed in `proposal.md`. The `## Verification` section
  no longer says `:shared:jvmTest` and `detekt` are green; it says, in the same terms as
  `tasks.md`, that neither was run here and that CI carries them.
- **REQ-OS-032's fifth scenario had no named test** — closed. The failure path is pinned
  by `PullPhaseTest` — *a shelf that cannot be written does not fail the pull*, which
  asserts `PhaseResult.Ok`, `dropped = 1` and `lastLsn = 10` against a fake whose
  `insertInboundIfAbsent` throws. The `INSERT_IGNORED` → `bumpInboundDelivery` branch is
  pinned by *the same corrupt event delivered again is one row, not two*, which now
  asserts `attempts == 2` **and** that the first sighting's `createdAt` / `failedAt` are
  unchanged — a plain `IGNORE` with no bump fails that assertion.
- **No review verdict** — this file.
- **Stale KDoc on `corruptEventId`** — `SyncDeadLetter.kt` no longer cites
  `insert`/replace as the pull path's statement; it describes the ignored insert, the
  bump, and what an owner-less key would actually do (swallow the second account's
  record), which is the reason the owner is in the key.
- **False `AppliedButUnusable` contract** — `PullPhase.kt` now states that only the
  *absent* payload reaches that arm, that a present-but-unreadable payload is `Corrupt`
  because `PullEventHandler` returns `Skipped(corrupt = true)`, and that a new
  non-corrupt skip reason lands in `AppliedButUnusable` unless the classifier says the
  bytes were unreadable.
- **Fake/real divergence on the `pull:` filter** — confirmed and fixed in both fakes.
  `delete` in `shared/.../test/fakes/FakeAppDatabase.kt` and in
  `SyncEngineFakes.FakeSyncDeadLetterDao` now refuses an inbound key and returns 0, as
  `SyncDeadLetterDao.delete`'s `AND patch_id NOT LIKE 'pull:%'` does.

## The decision

Skip *and* record, rather than either alone: the cursor has to advance because the same
bytes arrive on every delivery, and the record is the only trace of a remote change this
device dropped. The reasoning is in the ADR and in the spec delta, and it is unchanged by
this pass.
