# Coding standards

The rules a **reviewing** agent applies when it reads a diff. They are the rules
that no gate checks, because a gate can only check what has a shape.

Everything here has a machine-checked counterpart somewhere, and the counterpart
is named. A rule whose check does not exist yet is a rule the review must carry
alone — those are the ones worth reading twice.

Read `AGENTS.md` first: it is the operating manual, and this file does not
repeat it.

---

## The review reads the change, not the diff

A diff shows what changed. A review has to answer what the change *does* — and
those are different questions. The three cases that cost a reviewer the most:

1. **A change that adds a surface and wires nothing to it.** The code compiles,
   the tests pass, and nobody can reach it. The repository has a gate for this
   (`scripts/find-unwired-surfaces.py`), but the gate only knows about the
   surfaces it knows about.
2. **A change that removes a caller and leaves the callee.** The dead code is
   still exercised by its own test, so the suite stays green and the behaviour
   nobody uses stays alive.
3. **A change that makes a message say less.** The feature works; the sentence
   that tells the user what happened got shorter, and now it says nothing that
   can be acted on.

Ask, for every added public entry point: **who calls it?** If the answer is
"nobody yet", that is either a follow-up the diff names or a surface that ships
dead.

---

## Write tools propose; they do not write

A tool the assistant can call writes to a user's data. The write path in this
repository is a **proposal**: the change is held, the user reads it, the user
decides. That is the whole point of the proposal model, and it is checked by
`shared/src/jvmTest/.../feature/ai/tools/WriteToolsOnlyProposeTest.kt` — a
test, not a registry of exceptions: a file listing the exceptions is a second
thing to keep in step with the code, and the code can change without it.

A reviewing agent holds this line even where the test cannot:

- **A tool that writes directly needs a written reason.** There is no registry
  any more, so the reason is written in the change that introduces the writer:
  what it writes, and why it cannot propose yet. A new direct writer without
  that reason is a finding, and the fix is either to convert the tool or to
  write the reason down.
- **The set of direct writers must shrink.** A tool that was converted has its
  test moved to the proposing side. A set that only grows is a work queue
  nobody is working through.
- **The assistant's words must match what happened.** A tool that proposed a
  change says the change was *proposed*. It does not say the task was changed,
  and it does not say it was saved. The user has not agreed to anything yet.

---

## A gate that cannot fail is a gate that reports success forever

Every gate in this repository has a **positive control**: a mutation that makes
it fail, checked by `scripts/check-gate-wiring.py`. A gate nobody has tried to
break is a gate nobody knows can fail.

A reviewing agent applies the same standard to the diff it is reading:

- **A new gate needs a control.** A gate whose input is a committed file gets a
  sabotage of that file; a gate whose input is synthesised gets a fixture. If
  the diff adds a gate and no control, the gate is a green light wired to
  nothing.
- **A gate must be able to say no.** A gate that reports success on an empty
  input, or that swallows its own findings, is worse than no gate: it converts
  "not checked" into "checked and green".
- **A registry is checked, not written.** When a diff adds a list — a table, a
  catalogue, a set of names — ask what checks it against the code. A list that
  nothing checks is a sentence that reads like a fact.
- **A gate that derives a set from the tree proves the set is complete.** A gate
  that reads the classes, the suites or the modules out of the source and then
  checks the ones it found has proved nothing about the ones it did not find:
  the naming convention it reads, the sub-tag it ignores, the module it has no
  mapping for. The gate's set is compared with an *independent* enumeration —
  a second way of listing the same thing — and the comparison is part of the
  gate, not a note in the pull request. The slow-class rule is the shape of
  this: `unmapped_slow_classes` walks the test roots itself and names the
  slow classes that live in a root it does not map, instead of trusting a
  test to notice.

---

## A number that the tree can change is generated, not written

A count is a claim about the tree, and the tree moves. A number written by hand
in a living document is a claim that starts true and quietly stops being true:
`docs/engineering/script-tooling-audit.md` says the corpus is 1676 Kotlin
files, and the tree is now 1915 (`git ls-files '*.kt' | wc -l`). Nothing
failed — the number was true when it was written, and nobody reading it could
tell it apart from a number that was still true.

- **A count that the tree can change comes from a generator.** The repository
  already does this for the tag catalogue (`Maestro/TAGS.md` is generated and
  checked against `TestTags.kt`) and for the gate catalogue
  (`docs/tools/catalog.md`). A new count follows the same road: a generator, a
  golden file, and a gate that says when the file is stale.
- **A decision record is history; a living document is the present.** An ADR
  states what was decided at a moment, and its numbers are the numbers of that
  moment — they are not a defect when the tree moves on. A document that
  describes the tree *now* — `AGENTS.md`, `docs/architecture/`, a catalogue —
  states what is true now, and a number in it is a claim that has to be checked.
  A reviewer reads a changed number in an ADR as a record and in a living
  document as a claim.
- **The reviewer compares the numbers in the diff with the tree.** When a diff
  touches a document that states a count, the count is the first thing to
  check: a number that does not match the tree is a defect even when nothing
  else in the diff is wrong.

---

## A claim about CI names the workflow, the run and its conclusion

"I checked CI and it is green" is not evidence while the repository runs
several workflows: the one that answered "success" may have been the cache
cleanup. A session that read the first successful line of a run list reported
a green CI for a red branch, and the correction cost the reader a second pass
over the logs.

- **The claim is a whole line.** The workflow that decides, the run, and what
  it concluded. `python3 scripts/ci-verdict.py <ref>` prints exactly that line
  and exits with the conclusion — 0 for `success`, 1 for anything else, 2 when
  there is no run at all, 3 when the run list could not be read. A missing run
  is not a pass, and a list that could not be read is not a run.
- **A run id on its own is not a claim.** "Run 39888582872" says nothing about
  which workflow it was. The reviewer asks for the line the tool prints.
- **The reviewer checks the claim against the branch.** A diff that says the
  change is green names the run it means, and the run is of the branch the diff
  is on.

---

## The message says what happened

The repository's naming convention (`2026-10-21-green-means-one-thing.md`) is
one sentence: **green means one thing**. In practice:

- A message that names the cause beats a message that names the category.
  "There is no task with id `t-42`" beats "Task not found".
- A refusal says which part of the request was wrong and what the valid
  alternatives are. "That operation is not one of these: `set_due_date`,
  `set_priority`" is actionable; "invalid operation" is not.
- A failure is reported where it happened, not flattened into a generic error
  at the edge.

A reviewer checks the messages of the diff, not only the code paths — the
message is the part a user reads.

---

## A change the user cannot see is a change nobody decided about

Three rules about the same mistake: the change is lost, and the loss is silent.
A reviewer applies them to any change that writes, decides, or reads something
the user is looking at.

- **A change that cannot be applied keeps its reason next to it, on every surface
  that shows the change.** A screen that reports a failure into a channel nobody
  reads is a silent failure, and the surfaces of one feature are not one screen:
  when a change gains a way to fail, every surface that shows the change has to
  show the failure too. (The note editor emitted its errors and nobody read them:
  the change failed on one surface and succeeded on another, and the user could
  not tell which had happened.)
- **A change that writes several rows writes all of them or none of them, and the
  compensation covers the cancellation.** A cancelled coroutine is not a failed
  one: the rows written before the cancellation stay written, and if the change
  goes back to waiting, the next attempt writes them again. The compensation
  belongs where it runs on both paths — a `finally` under `NonCancellable` — and
  not only in the branch that handles a failure.
- **A value that cannot be read does not make the change disappear.** When a
  typed value is read back from storage and it no longer reads — an enum lost a
  variant, the spelling changed — the change is shown with the reason it cannot
  be read. Dropping the item is the one outcome the user cannot act on: they
  cannot turn down a change they cannot see.

Underneath all three: **the user must be able to decide about every change that
is proposed to them.** A change that vanishes, a change that fails without
saying so, and a change that is written twice are one defect wearing three
shapes.

---

## Text a person may want to copy is selectable

The convention table in
`.agents/skills/singularity-todo-selectable-text/SKILL.md` says, per file,
whether its text is `selectable`, `excluded`, or `prose`, and it is checked by
`scripts/check-selectable-text-coverage.py`.

For a diff that adds a screen or a card:

- **Text that states a fact, a title, or a message is `selectable`.** A person
  reading "Nothing to review" may want to paste it into a bug report.
- **A control's label is `excluded`** — a button's text is a gesture, not a
  message.
- **A shared component that renders selectable text makes its callers
  selectable too.** A screen that renders the shared empty state is
  `excluded` *for that text*, and the screen's own text decides the rest.

When a file changes its text, its row in that table changes with it. The table
is a claim about the code, and the gate compares the two.

---

## One implementation per question

Two answers to the same question drift. The repository has a decision for most
of them: one card for a proposal, one search implementation, one event bus
consumer, one registry per subject.

A reviewing agent watches for the version that is not a gate:

- A second card that renders a proposal, when one already renders it — the
  second copy will not get the fix.
- A second place that decides the same thing, where the two can disagree.
- A private copy of a shared component, because the shared one does not quite
  fit yet. The fit is a change to the shared one; the copy is a change to the
  truth.

---

## A path that applies a document writes all of it — and only what it carries

A document is more than the row it arrives in. A task is the row, the tag
cross-refs, the blocking edges, and the links its description implies; a note
is the row, its folder, its attachments, its outgoing links.

A path that applies a document writes every part of it. A path that applies
*part* of a document says which part, and leaves the rest alone.

And a part the document does not carry is not written at all. The wire carries
the fields the sending device knew about, so the absence of a part is the
absence of a claim — not a claim that the part is empty. A device that sends
no `tags` key says nothing about the tags, and a writer that reads the empty
list the decode produces takes the tags off the task. The difference is
carried in the type: a part of a document is nullable, and `null` means the
document says nothing about it.

A reviewing agent watches for the path that writes the row and stops:

- A write that upserts a row but not the cross-refs the row implies. The
  `@Upsert` replaces the whole row, so the parts that were not written are not
  left alone — they are reset.
- A write that takes a field it was given and writes it, when the field was
  empty because the reader does not load it. An empty tag list on a task read
  through `get()` is the absence of a claim, not a claim that there are no
  tags.
- A writer that drops a column of the row, because the domain model has no
  field for it. The column is still reset on every write.
- A repair that copies the fields of a row onto the rows around it, when the
  writer had no claim about one of them. The arrival of a row that is new on
  this device holds no tags, and a repair that copies them takes the tags off
  every other member of the series.
- A double that stores the whole task, and therefore turns an absent claim
  into a deletion. A fake that lies about the contract makes every test
  written against it a test of the fake.

The mechanical part is a gate: `TaskRowWriterTest` checks that every column of
the task row has a writer in the mapper the repository uses, and that a column
without one is declared out loud; the behaviour is `TaskDocumentWriteTest`,
and the wire level is `TaskDocumentPartsTest`.
The judgement the gate cannot make — is this path applying a document or a
part of one — is what the reviewer reads for.

---

## The spec and the code say the same thing

A spec that describes a behaviour the code does not have is worse than no
spec: it is a promise the code breaks on purpose, and the next reader believes
the promise.

A reviewing agent watches for a change that leaves the two apart:

- A scenario whose wording the implementation contradicts. Read the scenario
  against the code, not against the intent.
- A requirement that says "scoped by X" while the code scopes by Y. One of them
  is wrong, and the difference is a behaviour, not a wording.

The fix is in the same commit as the code: the spec is corrected to say what
the code does, or the code is changed to do what the spec says. "Later" is how
the two drift apart in the first place.

---

## A rename reaches every surface that names the old thing

A rename is a change with more than one edit, and the edits that are missing are
the ones nobody notices — because the missing surface is a sentence in a
document, or a label in a registry, or a path in a script that only fires in a
branch of the workflow.

- **Every surface that names the old name changes with it.** A command, a file
  path, a label, a workflow branch condition, a registry entry, a generated
  catalogue — whatever carries the old string.
- **A document that asserts the old behaviour changes in the same commit.** A
  document that says "the floor is 27" after the floor moved to 29 is not stale
  documentation; it is a false claim that a reader will trust, and it outlives
  the code it described. Fixing the sentence is part of the change, not a
  follow-up for someone else to find.
- **The claim has to be checked against the world, not against the diff.** A
  reviewer asks: does this sentence still hold? Not "did this sentence change?"
  A rename that leaves the workflow's branch unreachable is a rename that
  deleted a feature.

The gate cannot see this. It can see that a file was edited; it cannot see that
a sentence elsewhere became false. That is the review's work.

---

These are checked; a reviewer verifies the check was run rather than
re-deriving them.

| Rule | Checked by |
|---|---|
| Fakes, not mocks; mocks only for outgoing calls | `AGENTS.md`, review |
| A document writes only the parts it carries; a part it does not carry is not written | `TaskDocumentPartsTest`, `TaskDocumentWriteTest`, review |
| Every test class carries `@Tag` (`fast`, or `slow` when it crosses a process boundary) | `TestTagCoverageTest` |
| No `delay()` in tests — virtual time and `awaitState` | `AGENTS.md`, review |
| ViewModels take an injected `AutoCloseableCoroutineScope`; never `viewModelScope` | detekt + review |
| No pass-through use cases | `PassThroughUseCase` detekt rule |
| No `stateIn` in a ViewModel | `AGENTS.md`, review |
| No `runBlocking` in production code | detekt + review |
| `catch (CancellationException)` before a general `catch` | detekt |
| A swallowed exception keeps its cause | detekt `SwallowedException` |
| Room schema changes bump `SCHEMA_VERSION` and add a migration | `scripts/check-room-schema-integrity.py` |
| New ports bind in both `PlatformModule.*.kt` | `scripts/check-platform-reference-ports.py` |

---

## What the reviewer reports

A finding is a **defect with a place and a consequence**, not a preference.
Three parts:

1. **Where.** `file:line`, and the symbol.
2. **What is wrong.** The consequence — what a user or a caller gets that
   they should not get.
3. **What would make it right.** The fix, or the question that has to be
   answered first.

A finding that cannot be acted on is not a finding. "Consider extracting this"
is a preference; "this function is called from two places that disagree about
the unit, so the second caller multiplies by 1000" is a finding.

And the reviewer says when the diff is fine. A review that produces findings to
prove it looked is as useless as a gate that never fails.
