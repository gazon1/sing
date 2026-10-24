---
title: "The row keeps what the document does not say, for every type"
date: 2026-10-29
status: accepted
tags: [sync, database, mapping]
---

> **Superseded in part (2026-10-29):** the project is covered by this rule since
> [2026-10-29-the-project-is-read-against-the-row.md](2026-10-29-the-project-is-read-against-the-row.md) —
> the project is read against the row that is already here as well, and its
> inheritance is a claim of its own that travels with the write. The rest of this
> decision — the merge, the parts, the profile boundary — stands.

## Context

The row keeps what the document does not say — and that was true of the task
alone.

`#1026` found it: a task document is decoded into a `Task`, and the fields the
model cannot do without have defaults, so a document that carries no `seriesId`
key decoded into a task that says "this task stands alone", and the row was
written with it. The task left its series on the device that pulled it, and the
pull reported success. The fix read the document against the row that was
already here, and it did so **for the task**: the merge was a function of
`Task`, and the writer had one path that used it and five that did not.

The other five types decoded the document on its own and wrote it whole. A note
document that carried no `bodyMarkdown` wrote a note with no body. A tag
document that carried no `groupId` wrote a tag that belongs to no group. A time
entry document that carried no `note` wrote an entry with no note. The same
defect, the same silence, five times over — and the reason was not a decision:
the rule was written where the defect was found, and nobody said the rule was
about tasks.

The wire is the reason the reading matters, and it is the same reason it
mattered for the task. A document carries the fields the sending device knew
about: the allow-list says what a device sends, and it grew. A device that
predates a field sends no key for it.

## Idea

The rule is about a document and a row, and not about a task. Every type that
is synced but the project meets the row the same way, so every type that is
synced but the project reads the document the same way. The project joined them
after this decision was written: it is read against the row as well, and the
inheritance it carries is a claim of its own — see the banner.

## Decision

1. **The merge is one function, and it speaks JSON.** The row is given as the
   JSON the model writes — the same names the document uses — and the two are
   compared key by key. The document wins wherever it speaks; where it says
   nothing, the row keeps saying what it says. A rename of a wire field cannot
   leave the merge behind, because the names come from the model.

2. **Every type that is synced but the project is read against the row that is
   already here** — and the project since, with the inheritance as a claim of
   its own (see the banner).
   The writer has one path, and the path is per type only in what it reads and
   in how it decodes. A note, a tag, a tag group and a time entry are read
   against the row that is already here, exactly as a task is.

3. **The parts that live outside the row stay the caller's business.** The
   tags and the blocking edges are not columns of the task's row, so the row
   does not speak about them and the caller filters them out before the row
   reaches the merge. The merge does not know which keys are parts — that is
   per-type knowledge, and the per-type knowledge is the caller's.

4. **The row that is read is this profile's row.** A document of another
   profile is not read against anything: a row is keyed by its id alone, so a
   foreign document that names an id this device holds lands on this profile's
   row, and the row of this profile must not fill it. The read is scoped to the
   profile twice over — the writer checks the document's owner, and the
   repository scopes its read — and the two together say the same thing.

5. **A claim is still a claim.** A document that carries `bodyMarkdown: null`
   says the note has no body, and it wins. Only the *absence* of the key is an
   absence of a claim. The rule of `#1026` is unchanged; it is now stated once
   and applied everywhere.

## Rationale

**Why the merge speaks JSON and not a model.** The rule is "the document wins
where it speaks, and the row speaks where the document does not". That is a
statement about *keys*, and a key is a name in a JSON object. Spelling it over
a model would mean asking each type which of its fields is a claim about the
row — the same question, asked once per type, and answered once per type
differently. The JSON form asks it once.

**Why the parts stay with the caller.** Which keys live outside the row is a
property of the type, and the writer is where the type is known. A merge that
knew about the tags would know about the tags of the task and not about the
inherited groups of the project, and the second one is the same question with a
different answer.

**Why the profile is checked twice.** The writer's check is about the
*document*: a document of another profile is written as it says. The
repository's scoping is about the *row*: this profile's read does not find
another profile's row. Either alone leaves a hole — the writer's check alone
would still read a row that a repository does not scope, and the repository's
scoping alone would fill a foreign document from a row that happens to share
the id.

**Why the time entry's read takes the owner.** The time tracking port is
caller-trust — its verbs take the user id — and a read that reached across
profiles would let a document be filled with another profile's values. The read
takes the owner for the same reason the other repositories scope theirs.

## Consequences

- The path that applies a document reads the local row before it writes it, for
  every type but the project — the project since, with the inheritance as a
  claim of its own (see the banner). That is one extra read per applied
  document, and it is the price of knowing what the device already said.
- Nothing changes for a device that sends the whole document: every key is
  there, and the merge returns the document. The behaviour changes only where
  the document is silent — which is where the deletion was.
- A document that omits a field the model cannot do without is read against the
  row that is already here, and it is applied as far as it goes: the missing
  field takes the row's own value, and the rest of the document is applied.
  Before this change such a document failed to decode for every type except the
  task, and the pull dropped it and left the row alone.
- The time tracking port grows a read. It is the only one of the four that did
  not have one, and it is scoped to its owner because that port's other verbs
  are.
- The tag group fake now scopes its read the way production does. It did not,
  and a fake that reads across profiles would let a test pass against the fake
  and fail against production.
- The project type is not covered by this decision. Its inheritance
  (`inheritedTagGroupIds`) lives outside the row, in a cross-reference table,
  and the write path for it is a separate question: the project is written
  through `replaceInheritedTagGroups`, and a merge that spoke about the
  inheritance would have to change that write. That is its own change, and this
  decision does not pretend to cover it. The change is
  [2026-10-29-the-project-is-read-against-the-row](2026-10-29-the-project-is-read-against-the-row.md):
  the write carries the claim, and the project is read against the row.

## Links

- ADR `2026-10-23-the-row-keeps-what-the-document-does-not-say` — the rule,
  and the task it was written for
- ADR `2026-10-23-a-document-says-only-what-it-carries` — the parts that live
  outside the row
- ADR `2026-10-23-a-task-document-is-written-whole` — why the row is written
  whole
- Issue [#1026](https://github.com/gazon1/sing/issues/1026) — the row's own
  absences
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/DocumentMerge.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDocumentWriter.kt`
  — `mergedRow`, where the document meets the row
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/DocumentMergeTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/DocumentMergeForEveryTypeTest.kt`
