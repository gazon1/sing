---
change: the-project-is-read-against-the-row
verdict: pass
date: 2026-10-29
pr: 1117
reviewed: 2bc9c2fc8348a409e0f26aa972739896b7257ed4
documents: sha256:e4d28e8419360e7541f9ae690e5c9186f3d1a41a6807e49051365140cd749153
---

# The project is read against the row, and its inheritance is a claim

## The change

The rule of #1026 — a document that arrives from another device is applied
against the row that is already on this device: the document wins wherever it
carries a value, and a field the document does not carry is not reset, cleared
or defaulted by its arrival — was generalised to every synchronised type but
the project. The project was left out for a reason: its inheritance
(`inheritedTagGroupIds`) does not live on the row, it lives in a cross-reference
table, and the project is written through a path of its own. A merge that spoke
about the inheritance would have had to change that write.

This change answers that question. The write now takes a document — the
project, and the claim the document makes about the inherited groups — and the
table is replaced only when there is a claim. The project row is read against
the row that is already here, like every other type.

## The review

Two rounds, by a separate agent that was given the head and the documents
rather than the history of writing them.

**Round one: fail**, with three blocking findings and four remarks.

1. The branch did not compile. The desktop flow test called the port's old
   `upsert(Project)` while the port takes a document, and the two were in
   different commits — so the port commit alone did not build either. Fixed:
   both call sites speak about a document, and they are in the same commit as
   the port change.
2. The sibling decision still said the opposite. The decision that left the
   project out said "every type that is synced but the project", with no
   pointer to the decision that changed it. Fixed: it carries a "superseded in
   part" banner in the house format, and its sentences point at the new
   decision.
3. The KDoc of the wire name asserted an annotation the model did not have:
   `Project.inheritedTagGroupIds` carried no `@SerialName`, so the "one fact,
   one place" claim was false. Fixed: the property now carries
   `@SerialName(INHERITED_TAG_GROUP_IDS_WIRE_NAME)`, as the task's parts do,
   and the wire format is unchanged.

The remarks: the claim about the defect tests was stated more strongly than it
holds — each defect test is sensitive to exactly one mechanism, and the file
now says which; the null clause of REQ-OS-030 nominally covered a field that
cannot hold null; the invariant→gate table of `CODING_STANDARDS.md` did not
name the new test; the tasks read as if the previous change's spec file had
been edited, and now say that the requirement is modified by this change's
delta.

**Round two: pass.** All seven are closed, in the way the first round asked
for, and nothing new was found.

## The proof, and the controls that keep it honest

The new tests were checked with the mechanisms turned off, one at a time:

- the merge off — the document decoded whole, as it was before — **three of
  the six tests fail**: the description of the project, the document that
  omits the name and is a partial write rather than a dropped event, and the
  inherited groups the document says nothing about;
- the claim off — the write always replacing the table with the set it
  decoded — **one fails**: the inherited groups;
- the profile check off — **one fails**: the document of another profile.

The inheritance is asserted against the table of the production repository over
the in-memory database, and not against a fake: a double that keeps the
inheritance inside the project could not fail where production fails, and the
table is what the defect was about.

What was measured here, since the reviewer did not run Gradle: the full JVM
suite of the module passes at the head of this change (3887 tests, and the
three failures are the same three that fail on `main` and come from PR #1093);
the new tests and the inheritance test of the project pass; the desktop
navigation flow — the test the port change reached — passes in the slow
population.

## What came after the reviews

One clause: the consequence of the sibling decision now points at its banner,
which the second review noticed was missing there. And this verdict file
itself.

## Links

- ADR `2026-10-29-the-project-is-read-against-the-row`
- ADR `2026-10-29-the-row-keeps-what-the-document-does-not-say-for-every-type` —
  the rule, and the type it left out
- ADR `2026-10-23-the-row-keeps-what-the-document-does-not-say` — the rule, and
  the task it was written for
- Issue [#1026](https://github.com/gazon1/sing/issues/1026)
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/domain/model/ProjectDocument.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/ProjectDocumentWriteTest.kt`
