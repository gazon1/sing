---
change: the-row-keeps-what-the-document-does-not-say
verdict: pass
date: 2026-10-24
pr: 1048
reviewed: 3332a2f8f6dfa090864b6ae1107905dc698a0a61
documents: sha256:534432dc44ee02a15a693a03bca06a55c8f91a5ea584e1b70d6ecdb301565d05
---

## What was reviewed

The whole change: `openspec/changes/2026-10-23-the-row-keeps-what-the-document-does-not-say/` —
the proposal, the spec delta and the tasks — against the code it describes, against
`docs/decisions/2026-10-23-the-row-keeps-what-the-document-does-not-say.md`, and against
the one amendment outside the change:
`docs/decisions/2026-10-23-a-document-says-only-what-it-carries.md`, the sibling decision
about the parts that live outside the row.

Five rounds, by separate agents, each given the head under review and the contracts, not
the history of writing it.

| round | verdict | what it found |
|---|---|---|
| 1 | fail | a dead reference to a decision that did not exist; the ADR's promise about the wire names was not true; the prose in `SyncEngine.kt` still said "no field-level merge"; the spec delta did not cover the other profile; the documents overstate what the merge does to the parts |
| 2 | fail | the branch had been rewritten between the rounds; the claims-mechanism was tried and reverted, and the review said why |
| 3 | fail | the ADR asserted that a server document carries the bookkeeping keys, which the repository's own allow-list does not support; the sibling ADR still said the row's own absences are not covered; the requirement id collided with another pending change's `REQ-OS-028`; the decision list skipped a number; the lost-race consequence described only the legacy shadow; the proposal said the series rule worked "as it always did"; the raw document was decoded before the merge, so a document missing a required field failed there |
| 4 | pass | no blocking finding; the merge reads the *current* profile's row, so a document of another profile could be filled from this profile's row; a document missing a required field is now a partial write, which no document said |
| 5 | pass | the two findings of round four are closed |

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

## The verdict

Pass. The change does what it says: a task document is read against the row that is
already on the device, the document wins wherever it speaks, and a field it is silent
about is neither reset nor cleared. The merge is on the only path that applies a task
document, the parts that live outside the row keep the rule of the sibling decision, and
every document of the change says what the code does.
