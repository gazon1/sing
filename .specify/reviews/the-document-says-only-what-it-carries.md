---
change: the-document-says-only-what-it-carries
verdict: fail
date: 2026-10-23
pr: 1029
reviewed: 38917daa7ad5554cab447e07b364e097dc794b47
documents: sha256:d56ea1259ebc37a52b13e2621c87099ed01a147162a287d28574507499c16e9e
---

## What was reviewed

The whole change: `openspec/changes/archive/2026-10-25-the-document-says-only-what-it-carries/` —
the proposal, the design, the tasks and the `tasks` spec — against the code it
describes and against
`docs/decisions/2026-10-23-a-document-says-only-what-it-carries.md`, plus the
amendment of the previous change's spec
(`openspec/changes/archive/2026-10-25-the-task-document-is-written-whole/specs/tasks/spec.md`).

The reviewers were separate agents given the delta, the ADR, the OpenSpec
change and the test files, and nothing else: no history of writing the code,
no pressure to find nothing wrong. They were asked to judge the change
against the contracts it states.

The first round was split by what each could see well: one on the write path
(the pull path, the model, the repair of the series, the wire names), one on
the checks and the specs. Both were told not to run Gradle — a build was
running in the same worktree, and a second one corrupts the shared build
directory. Neither ran one.

The second round was a single agent over the whole delta, after the findings
of the first round had been fixed. It was given the same constraints and the
same instruction to judge the change against the contracts it states.

## The reviews: FAIL

The verdict stays **fail**, because the reviews found something: that is what
this file is for. The findings were fixed in the same branch.

### Round one — the write path

No blocker. The mechanism itself was sound: the reader reads the wire keys
before the decode, the writer and the reader share one name, the conditional
writes are correct, the refusal is reachable and logged, the DI binding and
the detekt limits hold. Six should-fix and six nits were found.

1. **Should-fix — the double implemented the old semantics.** The fake stored
   the whole `Task`, whose `tags` and `blockedBy` are the decoded defaults
   when the wire carried no such key — so the fake turned an absent claim
   into a deletion, exactly the defect the change removes, and ignored
   `document.tags` / `document.blockedBy` outright. A test written against
   the fake would have failed against the fake and passed against production.

2. **Should-fix — the repair of the series turned an absent claim into a
   deletion one level out.** The repair copies the shared fields of the source
   onto the other members, and the source's tags are the ones the database
   holds. A row that is *new* on this device holds none, and the row is new
   precisely because the document arrived — so the arrival took the tags off
   every other member of the series. The ADR's promise did not hold for the
   siblings of an arrived row.

3. **Should-fix — the ADR's rationale for excluding the row was wrong.** It
   said the parts where the absence carries information are the parts that
   live outside the row "because those are the parts a device can add to the
   model later without changing the row" — and the series id and the
   recurrence rule are exactly that, and live *in* the row.

4. **Should-fix — the ADR contradicted the spec and the test shipped in the
   same commit.** The ADR said the batch repair "finds nothing to do"; the
   spec and the new test said a partial batch is repaired. One of them was
   wrong, and the project rule is that the difference is fixed in the same
   commit.

5. **Should-fix — the batch wrote the tags it was given, and the repair
   spread them.** The single-row write refuses to write the tags for a good
   reason (a task read through `get()` holds none); the batch writes them,
   and after this change the repair propagated them to the whole series. The
   hazard is latent — both callers read the series, which loads the tags —
   but the blast radius had grown.

6. **Should-fix — the amended previous spec promised more than the code
   keeps, for the links.** It said a part the document does not carry is not
   written, and listed the links among the parts — while the links are always
   written, derived from the row's description.

Nits: `TaskDocument.of` has no production caller (the ADR presented it as
one); its KDoc named only the tags hazard and not the dependencies; the
lost-race revert inherits the new semantics undocumented; the refusal log
named neither the verb nor the profile; the reader went through a redundant
alias for the model's constant; the ADR said the repository "has a logger"
when the logger is added by this change.

### Round two — the whole delta

No blocker, and the verdict PASS with six should-fix and two nits. The
mechanism, the guarded repair, the fake and the batch repair were found to be
as specified. What was still open:

1. **Should-fix — only the copy half of the repair was pinned.** Deleting the
   narrowing of the compared fields left every test green: the member's tags
   survived, but the member was written and re-stamped although the writer
   had no claim about the tags.

2. **Should-fix — the refusal had no test.** Deleting the `log.w { … }` call
   left every test green — the spec says the refusal SHALL be recorded, and
   nothing said it out loud.

3. **Should-fix — the double's non-null claim and its `blockedBy`
   preservation were unpinned.** Two mutations survived: a double that keeps
   the old tags when the document claims new ones, and a double that drops
   the dependencies the document says nothing about.

4. **Should-fix — the spec says the absence is read before the decode, and
   the code decoded first.** The information came from the raw document
   either way, but the wording is normative in four places, and the rule this
   change exists to repair is that the spec and the code say the same thing.

5. **Should-fix — the batch's claim about the tags was implicit.** The
   batch writes the tags it was given, so it has a claim, and the repair
   propagates what the batch wrote — but nothing in the code said so.

Nits: the refusal log's arrow read backwards (the task is blocked *by* the
dependency, so "A → B" read as the opposite relation); the wire-name test
was weaker than its name suggested.

## What was done about it

- The double now holds the same contract as production: a claim replaces the
  part, an absence leaves it alone. Two tests pin it — one for the claim, one
  for the absence, for the tags and for the dependencies.
- The repair takes the claim of the writer as a parameter. When the writer
  had no claim about the tags, the repair neither compares the tags nor
  copies them: `TaskSeriesInvariants.membersToRealign` gained a `fields`
  parameter, and the cross-ref write is guarded. The spec gained the
  requirement *a repair follows the claim of the writer* and its scenario.
- The ADR now says what it means: this change is about the parts that live
  outside the row; the row's own absences are the same hazard one level in
  and are tracked as #1026. The promise about the tags is qualified to the
  rows the document speaks about.
- The ADR's batch paragraph was corrected: a partial batch is the case the
  repair exists for, and it is now repaired.
- The batch's contract is written down where it is used — the port's KDoc
  says that the batch is given the tags of the whole batch and that a caller
  that reads its rows through `get()` holds none — and the ADR states the
  hazard. The repair is called with the claim spelled out.
- The previous spec now says that the links are not a claim of their own:
  they are derived from the description of the row that arrived.
- The reader reads the keys before the decode, as the spec says.
- The refusal is logged with the verb, the profile and both ids, in a
  sentence that cannot be read backwards — and a test captures the log and
  asserts the refusal is there.
- The repair's comparison half is pinned: the test asserts that the member
  of the series was not written at all, so a narrowing that only guarded the
  copy would fail.
- The wire-name test says what it checks and what the other tests check.

## Links

- ADR `docs/decisions/2026-10-23-a-document-says-only-what-it-carries.md`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/TaskDocumentPartsTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskDocumentWriteTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskSeriesWritePathTest.kt`
- Issue #1026 — the row's own absences are still deletions
