---
change: the-row-keeps-what-the-document-does-not-say
verdict: pass
date: 2026-10-24
pr: 1048
reviewed: 78f720a3e1d34bbc5f811e594ba7207fce41c72e
documents: sha256:534432dc44ee02a15a693a03bca06a55c8f91a5ea584e1b70d6ecdb301565d05
---

## What was reviewed

The whole change: `openspec/changes/2026-10-23-the-row-keeps-what-the-document-does-not-say/` —
the proposal, the spec delta and the tasks — against the code it describes, against
`docs/decisions/2026-10-23-the-row-keeps-what-the-document-does-not-say.md`, and against the
two amendments outside the change:
`docs/decisions/2026-10-23-a-document-says-only-what-it-carries.md`, the sibling decision
about the parts that live outside the row, and
`docs/decisions/2026-10-06-a-transient-failure-is-not-a-permanent-one.md`, the decision that
explains why the pull path has no `Conflict` arm — a sentence this change made false by
reading the row against the document, and that the last two rounds had to put back in order.

Eight rounds, by separate agents, each given the head under review and the contracts, not
the history of writing it.

| round | verdict | what it found |
|---|---|---|
| 1 | fail | a dead reference to a decision that did not exist; the ADR's promise about the wire names was not true; the prose in `SyncEngine.kt` still said "no field-level merge"; the spec delta did not cover the other profile; the documents overstate what the merge does to the parts |
| 2 | fail | the branch had been rewritten between the rounds; the claims-mechanism was tried and reverted, and the review said why |
| 3 | fail | the ADR asserted that a server document carries the bookkeeping keys, which the repository's own allow-list does not support; the sibling ADR still said the row's own absences are not covered; the requirement id collided with another pending change's `REQ-OS-028`; the decision list skipped a number; the lost-race consequence described only the legacy shadow; the proposal said the series rule worked "as it always did"; the raw document was decoded before the merge, so a document missing a required field failed there |
| 4 | pass | no blocking finding; the merge reads the *current* profile's row, so a document of another profile could be filled from this profile's row; a document missing a required field is now a partial write, which no document said |
| 5 | pass | the two findings of round four are closed |
| 6 | pass | the prose of the test that guards the `Conflict` arm still said the path performs no field-level merge — a sentence that became false when the row started being read against the document — and the KDoc of `ApplyOutcome` and `PullSummary` had to say the same thing in the words the code now uses |
| 7 | fail | the decision of 2026-10-06 still said the pull path performs no field-level merge; "the missing merge" had become ambiguous now that a merge exists |
| 8 | pass | the paragraph round seven rewrote still named members that had been removed a day later by `7582a27f`, and said the removal "is not done here" |

## What the review measured

- **The positive control.** `mergeWithLocalRow` neutered — the document returned unchanged
  even when a local row exists — and the same command again: 811 tests, 7 failed, at all
  three levels the change claims to cover: the unit test (3 of 9), the path-level test
  (3 of 7) and the real-database test (1 of 16). Everything else stayed green, so the
  control isolates the merge.
- **The gates.** `check-doc-dead-refs.py --strict` — 0 new; `check-adr-references.py` —
  0 new; `openspec validate --all --strict` — 105/105; the gate suite — 811 tests, 0
  failures; `detekt` — 0 findings.
- **The sibling's contract is untouched.** The digest of `the-document-says-only-what-it-carries`
  is `sha256:d56ea125…`, identical to the value in that change's own verdict file: the
  amendment of its ADR did not move its contract, and its verdict gate is intact.
- **The owner check (round five).** With the check removed, exactly one test fails — the
  one that says a document of another profile is not filled from this profile's row. The
  comparison uses the same identity the repository writes and compares with
  (`currentUser.scopedUserId.value`), so the merge's decision agrees with the branch the
  repository will take in every case.
- **The claims of the amended paragraph (round eight).** Every one of them was checked
  against the code rather than against the decision: `ApplyOutcome` has three arms,
  `PullSummary` carries no conflict count, `PullStep.Done` is a `data object`, nothing
  constructs a `Conflict`, and `7582a27f` — dated the day after the decision of 2026-10-06 —
  is the commit that removed the arm and the field. The targeted test class was run at that
  head: 3 tests, 0 failures.

## What the review did not settle

- The live server's behaviour. The review is of the client's rule, and the ADR no longer
  claims anything about what a device sends.
- The cross-profile case of round four was derived from the code, not reproduced end to
  end: it needs a legacy empty-`profileId` event, or a profile switch inside a push.
- The full JVM population was not run; the host was busy and the two targeted sets cover
  the sync and tasks packages plus the new tests at all three levels.
- Two observations that are not defects are left in the report: the framing of the owner
  check in the review brief, and the fact that a document which identifies nobody is read
  against this profile's row (which is the partial-write rule the ADR now states).
- The sibling change `the-document-says-only-what-it-carries` still states the old
  behaviour in its own contract documents — `proposal.md` says a document that carries no
  series id "still writes the row without one". That is this change's subject, but the
  files are the sibling's: editing `proposal.md` moves the sibling's digest and is its
  owner's call to re-issue its verdict. The sibling's digest is unmoved, so its verdict
  still speaks for its contract.
- Three pieces of drift in `main` that this change does not own and did not touch:
  `docs/architecture/sync.md` still shows a `ConflictResolver` that does not exist; the
  decision of 2026-10-05 still says the sync layer has no per-field conflict policy; and
  the frontmatter of the decision of 2026-10-06 names itself as the decision that
  supersedes it.


## The verdict

Pass. The change does what it says: a task document is read against the row that is
already on the device, the document wins wherever it speaks, and a field it is silent
about is neither reset nor cleared. The merge is on the only path that applies a task
document, the parts that live outside the row keep the rule of the sibling decision, and
every document of the change says what the code does.
