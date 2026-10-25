---
change: the-row-keeps-what-the-document-does-not-say-for-every-type
verdict: pass
date: 2026-10-29
pr: 1113
reviewed: a0391cc210e0cd1c8da286b67956e6a701791758
documents: sha256:720b625fd8d996864448598c9f23da45af739c259ad50094178b85c2c3d7e3b1
---

# The row keeps what the document does not say, for every type

## The change

The rule of #1026 — a document is applied against the row that is already on
the device, the document wins wherever it carries a value, and a field the
document does not carry is not reset by its arrival — was written for the task
and implemented for the task alone. The merge was a function of `Task`, and the
writer had one path that used it and five that decoded the document on their
own. A note document without `bodyMarkdown` wrote a note with no body; a tag
document without `groupId` wrote a tag that belongs to no group; a time entry
document without `note` wrote an entry with no note. The row was written and
the pull reported success, so the deletion was invisible.

This change generalises the rule: the merge speaks JSON and knows no types,
and the writer has one path for every type that is synced but the project.

## The review

Two rounds, by a separate agent that was given the head and the documents
rather than the history of writing them.

**Round one: fail**, with four findings.

1. The change had no record in `.specify/issues/` — the registry gate fails on
   a change the repository does not know about. Fixed: the record was written
   by `scripts/openspec-import-all.py`.
2. The sibling decision's verdict went stale, and this change had no verdict
   of its own. The sibling's digest moved because this branch edited the
   sibling's proposal — a link to the file that was renamed. The sibling's
   verdict is re-issued in the same diff, and this file is the verdict of this
   change.
3. The test about the row of another profile was vacuous: it seeded only the
   *other* profile's row, and the merge never reads another profile's row, so
   the assertion held whether the check was there or not. Rewritten: the test
   now holds *this* profile's row under the id the foreign document names, and
   the foreign document carries no body. With the check taken out of
   `mergedRow`, that test fails — it is a control now, not a decoration.
4. The KDoc of the writer said that nothing in it reads the signed-in
   account, and it named the wire key `user_id` where the code reads
   `userId`. The account *is* read now — to decide whether the row that is
   already here may be read into the document — and the comment says so.

**Round two: pass**, with one non-blocking nit: the ADR and the proposal said
"every type that is synced", and the project is the one type where the rule
does not hold. The sentence now says "every type that is synced but the
project", which is what the rest of both documents already said.

## Why this verdict is re-issued

The contract of this change moved after the review was made: the wording of
the sentence the review asked about changed, and the decision that carries it
was renamed, and with them the digest. A verdict that speaks for a contract
that is not there any more speaks for nothing, so the verdict is re-issued
with the digest the contract carries now.

The `reviewed:` field names the commit the review read — `a0391cc2`, the
head of the branch when the reviews were made. Two things came after it: the
two sentences about "every type" and the two comments the second review found
untrue, which the reviews asked for; and the rename of the decision, which is
mechanical and moves the file into the end of the corpus of decisions — the
corpus is measured in the order of the names, and a decision that sorts inside
the measured prefix moves the prefix and the numbers the record states.

The branch was also rebased onto a newer `main` after the reviews, so the
commit the reviews read has the same content and a new name.

The `pr:` field names #1113 — the pull request where the contract moved. A
re-issue names the pull request it is about, and this one is about that pull
request.

## What the review checked

- the merge per type: an absent key keeps the row's value, a value the
  document carries wins, a null value is a claim, and the parts that live
  outside the row stay out of the merge's business;
- the task path: unchanged in observable behaviour, `TaskDocument.tags` and
  `blockedBy` stay null when the document does not carry the key;
- the profile boundary: the writer's owner check and the repositories' scoped
  reads agree, because both read the same `ProfileAwareCurrentUser`; a
  document with no owner is treated as this profile's, which is the behaviour
  the task had before this change and which the ADR and REQ-OS-030 both state;
- the port: `TimeTrackingRepository.get(entryId, userId)` — the shape that
  port's caller-trust verbs ask for, additive, both implementations updated,
  the DAO query scoped, one caller;
- the doubles: `FakeTagGroupRepository.get` now scopes its read the way
  production does, because a double that reads across profiles lets a test
  pass against the fake and fail against production;
- the documents: the ADR, the proposal, the spec and the registry record
  against the code, and the exclusion of the project type — whose inheritance
  lives outside the row and whose write path is `replaceInheritedTagGroups` —
  which both the ADR and the proposal state outright.

## What the review did not settle, and what was measured here

- The **project** type is not covered, on purpose. A merge that spoke about
  the inheritance would have to change the project's write path, and that is a
  change of its own.
- The **parts** that live outside the row are not covered either: their claims
  are the sibling change's business, and the merge leaves their keys alone so
  that both readings can hold.
- The reviewer did not run Gradle. It was measured here instead: the full JVM
  suite of the module passes at the head of this change (439 classes), the new
  tests pass, and `TaskDocumentWriteTest` — which is tagged `slow` and is not
  part of the default run — passes as well (16 tests).

## The proof that it holds

The new tests were checked with the merge turned off: five of the eleven
tests of `DocumentMergeForEveryTypeTest` fail without it — the note's body,
the note's color, the tag's group, the time entry's note, and the tag group
document that does not decode at all. The six that pass either way are the
guards, and the file says which is which.

The profile boundary has its own control: with `belongsToThisProfile` taken
out of `mergedRow`, the cross-profile test fails and it is the only one that
does.

See ADR `2026-10-29-the-row-keeps-what-the-document-does-not-say-for-every-type`.
