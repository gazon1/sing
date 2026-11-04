---
change: corrupt-payload-is-shelved
verdict: fail
date: 2026-11-04
pr: https://github.com/gazon1/sing/pull/2248
reviewed: 7f06685958280ad85e6d7d42f0a04f51d0b45f61
documents: sha256:c21525f095ba429c228686aa0e76f6627ecc216f44ff01a2d658b03578d24372
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

## The review of the pull request: FAIL

The frontmatter above now names this review: the last one failed, so the verdict is
`fail`. The account of the first review is left above as it was written — it was true
of the commit it reviewed.

The review of pull request #2248 did not pass. It was made against the head of the
branch, `7f0668595`, and it found three things.

### 1. The recording half was optional — FIXED

`PullPhase` took the shelf as a defaulted argument, and the default was a stand-in
that logged an error for every write it was asked for. So the recording half of this
feature could be left out at a construction site and compiled away: the cursor would
advance, nothing would be shelved, and the only trace would be a log line — the
divergence #1407 is about, in a quieter shape. The change's own `tasks.md` recorded
that the omission had already happened once.

The shelf is now a **required** argument of the pull phase, with no default and no
stand-in: a construction that does not name a shelf does not compile. The stand-in
object is gone rather than left unused. The behaviour is unchanged; the omission is
no longer expressible.

### 2. The verification gate was unchecked — ANSWERED, NOT RUN

`tasks.md` said, truthfully, that `:shared:jvmTest` and `detekt` had not been run: the
session that wrote the change refused Gradle. The review's point was that the
behaviour this change adds was therefore asserted only by unexecuted tests.

The session that answered this review refused Gradle as well, so the gate is still
unrun here and CI carries it. What is different is that the two findings that did not
need a test are now mechanical: the shelf cannot be left out (finding 1), and the
classification path — `PullEventHandler` returning `Skipped(corrupt = true)`, the pull
phase's `Corrupt` arm writing the row — was read against the tests that assert it,
along with every construction site of the pull phase.

### 3. A duplicated KDoc block — FIXED

The REQ-OS-032 *a record that cannot be written* block appeared twice, verbatim,
above the same test in `PullPhaseTest`. One copy now.

## What this diff also found

- **The test sources did not compile.** `PullPhaseTest`'s helper passed a *nullable*
  shelf into a non-null argument, which is a type error, not a warning. Nothing in the
  tree had ever compiled this module, which is why the gate had never run. Fixed by
  making the helper's shelf non-null, and by naming a shelf at every construction
  site.
- **The verdict itself was malformed.** `reviewed:` named an abbreviated commit, and
  the gate wants forty hex characters; `pr:` named the issue rather than the pull
  request. Both are fixed above, and `documents:` is the digest of the contract as it
  stands after this diff — the proposal moved, so the verdict is re-issued with it.

## The decision, restated

Skip *and* record, rather than either alone: the cursor has to advance because the same
bytes arrive on every delivery, and the record is the only trace of a remote change this
device dropped. The reasoning is in the ADR and in the spec delta, and it is unchanged
by this pass.

This verdict is `fail` because the last review failed. The findings are answered in the
same diff; what is left is for a re-review to say so.
