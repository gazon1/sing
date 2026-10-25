---
title: "Two branches that mint the same requirement id are merged by hand, and a capability the archive creates is given a purpose by hand"
date: 2026-10-25
status: accepted
tags: [openspec, specs, tooling]
---

## Context

Forty-seven changes had all their tasks closed and none of them had been archived. The
archive was run over them in the order the changes were authored, and thirty-nine
archived cleanly. Eight refused.

The refusal was not about the code and not about the wording. It was about ids.

`openspec archive` refuses to archive a `MODIFIED` requirement whose block does not
carry every scenario the live spec already holds — because applying it would drop
those scenarios from the spec — and it refuses to archive an `ADDED` requirement whose
id is already taken. Both refusals fired here, and both had the same cause.

The changes had been written on parallel branches, each against a spec that did not
exist yet, and each had minted requirement ids of its own. `text-input` was never in
git at all: the capability lived only in the deltas of the branches that were
building it. Five branches then wrote the same requirement — the structural check
that verifies a rewrite contract from the shape of the code — and four of them
named it `REQ-TI-REWRITE-1`, which was already the id of a different requirement,
the sealed kind of a field's source. The fifth was the branch that added it, and
it had named it `REQ-TI-SHAPE-3`. Four branches wrote the same requirement of the
task editor, each with the requirement as it stood when that branch was written:
seven scenarios, then eight, then ten, then twelve. One branch added a
requirement about the tool that searches, and gave it the id `REQ-SEARCH-011`,
which the spec already held for the requirement that a search result shows the
matched word. One branch added a requirement about the kind of a field's source,
and gave it the id `REQ-TI-REWRITE-2`, which the spec already held for the
requirement that the bridge takes no count of replacements.

One change added four requirements to `test-execution-integrity` under the ids
`REQ-15` to `REQ-18`, and the spec already held those four ids for four
different requirements. The archive put the arriving block into the spec, and
the file ended up with two requirements under each of those ids.

One branch did something the archive could not check: it carried a
`REMOVED Requirements` block for a requirement — `REQ-TI-REWRITE-EXCEPTION` —
that no change ever added. What it meant to remove was the exception that two
other requirements stated in their own prose: that a field site may build its own
source and say so. The code says the opposite, and says so in the test that
checks it: there is no exception, the GenUI renderer's owner is the data
context, and a site that constructs the source fails the check. The removal
therefore had to be applied by hand, and the block stays in the archive as the
branch wrote it.

The second refusal had a different shape and the same cause. Archiving a change that
creates a capability writes a placeholder purpose into the new spec —
`TBD - created by archiving change <name>. Update Purpose after archive.` — and the
strict validator refuses a spec whose purpose is still a placeholder. Fourteen
capabilities were created by this archive, and every one of them arrived with the
placeholder.

## Idea

An id is a name, and two things cannot have the same name. When two branches mint the
same name for different requirements, the requirements have to be told apart — and
the one that is already in the spec keeps the name, because the spec is what the code
is read against. When two branches write the same requirement, the requirement has to
carry both of them, because a requirement that drops a scenario is a requirement that
says less than the code does.

## Decision

1. **The requirement in the spec keeps its id; a branch that meant another requirement
   is retargeted onto the one it meant.** The four branches that meant the structural
   check were retargeted from `REQ-TI-REWRITE-1` onto `REQ-TI-SHAPE-3`, which is the
   requirement that actually states it. The bodies were not merged: the body of
   `REQ-TI-REWRITE-1` — the sealed kind of a field's source — is correct and matches
   the code, so it stays where it is, and `REQ-TI-SHAPE-3` keeps its own body.

2. **A requirement that cannot keep its id gets one that is free.** The requirement
   about the kind of a field's source became `REQ-TI-SOURCE-KIND-1`, and the
   requirement about the tool that searches became `REQ-SEARCH-016`. Both ids were
   free; both requirements are the ones their branch wrote, and nothing else claims
   either name.

3. **A merged requirement carries the prose and the scenarios of every branch that
   touched it.** The requirement on the task editor carries the prose of all four
   branches and the union of their scenarios. A requirement that drops a scenario
   says less than the code does, and the archive's refusal is right to insist on the
   union.

4. **A capability the archive creates is given a purpose by hand, from the change
   that created it.** The purpose says what the capability is for, in the words of
   the proposal that introduced it. It is not a description of the requirements it
   holds — the requirements say that — but the answer to "why does this capability
   exist at all".

5. **The index of capabilities is updated in the same change that creates them.**
   `openspec/specs/MODULE-INDEX.md` names every capability that has a spec and
   every module that does not. Fourteen capabilities were added to it. Four rows
   of the table of modules that no capability covers were touched as well: three
   now name the capability that covers them — two of them also name the archived
   change that produced that capability — and one was removed, because the module
   it named is covered now.

6. **A removal that names a requirement nobody added is applied by hand, and the
   removal stays in the archive.** The block that removed `REQ-TI-REWRITE-EXCEPTION`
   meant to remove the exception that two requirements state in their own prose —
   that a field site may build its own source and say so. The code says the
   opposite, and the test that checks it says so in its own words: there is no
   exception. So the exception paragraph and its scenario were removed from
   `REQ-TI-REWRITE-3`, and the sentence and the scenario that let a site build its
   own source were removed from `REQ-TI-REGISTRY-2`. The block the branch wrote
   stays in the archived delta, because the archive is the record of what the
   branch said.

7. **A requirement that arrives with an id the spec already holds is renumbered
   to a free id, in the live spec and in the archived delta alike.** The four
   requirements that `the-next-layer-of-contracts` added to
   `test-execution-integrity` became `REQ-27` to `REQ-30`, and the archived
   delta carries the same names. The rename is recorded here, because a reader
   of the archive meets the old ids and has to be told what they became.

8. **A document that names a vocabulary the code retired is corrected — in the
   archive too, and in the proposal with it.** The requirement of `notes` that
   answers "who may rewrite this field?" named three answers: none, the value
   itself, the value and a revision token. The code has two kinds of source,
   `StaticText` and `OwnedText`, and this project's own decision says the old
   vocabulary is gone. The requirement, the delta it was applied from and the
   proposal that introduced it all named the three, so all three name the two
   now, and the proposal no longer calls the question an open one.

## Rationale

**Why the ids were not renumbered.** The obvious repair — give every colliding
requirement a fresh id — would have been a lie about the history. The spec says
`REQ-TI-SHAPE-3`; four branches wrote a block under a different name because the
person writing it had not seen the other branch. The number belongs to the
requirement, and the branch was wrong about the requirement's name, not the other way
round.

**Why the removal was applied by hand.** The archive applies a `REMOVED
Requirements` block by id, and the block named an id that no change ever added —
so the archive would have applied nothing and said nothing. The block's own
reason says what it meant: the field site that built its own source no longer
exists, the data context builds the source and hands it out whole, and the
contract has no exception left to state. That is what the code says too, and what
the test that checks it says. So the removal was carried out by hand, against the
two requirements that stated the exception, and the block stayed in the archive as
the record of what the branch said it was removing. A removal that silently
removes nothing is the same defect as a requirement that silently drops a
scenario, and it is worse: the branch's reason is right there.

**Why the bodies were not merged.** Two requirements had collided, and merging them
would have produced one requirement with two contradictory bodies. The structural
check and the sealed kind of a source are different claims about different code —
`TextFieldSource.kt` holds `sealed interface TextSource` with `StaticText` and
`OwnedText`, and the check that a field's source is one of those is a different
statement from the check that a rewrite contract has the shape it claims. Merging
them would have made the spec say both at once and mean neither.

**Why the union of scenarios, and not the last branch's.** The branches were written
in sequence on a chain, each against the requirement as it stood. The scenario sets
grew: seven, eight, ten, twelve. The last branch's block is the most complete, but
it is not the one that carries every scenario — the *first* branch is the one that
lacks one the spec holds, the screen that opens a picker from an editor's row, because
that branch was written against a spec that did not have it yet. The later branches
carry it. Dropping it would have deleted a requirement the code already implements.

**Why the purpose is written and not generated.** The placeholder is what the tool
writes when it does not know. The tool does not know what a capability is for, and
nobody should have guessed it from a requirement list: `agent-toolbox-surface` and
`gate-tooling` are both obviously about tooling and are about entirely different
things. The change that created the capability said what it was for, and that is
what the purpose says.

**Why the arriving requirements were renumbered, and the archive with them.** An id
is a handle: a later change that writes `MODIFIED Requirements: REQ-17` must have
exactly one target, or the archive applies it to a requirement nobody meant. A spec
that holds two requirements under one id has no such target, and nothing sees it —
the strict validator counts the requirements, not the uniqueness of their ids
inside a file. The natural ids were taken by different requirements, and those
requirements keep them, because the spec is what the code is read against. The
arriving requirements took the next free ids, and the archived delta was renamed
with them: a delta whose ids name nothing in the live spec is a delta that can
neither be read against the spec nor replayed.

**Why the proposal was corrected as well.** The archive is the record of what the
branch said about the *behaviour* — which requirement says what, which scenario
covers it, which shape the code has. A vocabulary the code retired is not that:
it is a statement about the code, and it stopped being true. A reader who opens
the archived proposal and finds three answers, while the delta beside it names
two and the code has two, has been handed a contradiction and no way to tell
which half to believe. The archive keeps the branch's behaviour; it does not keep
a description of the code that the code has since outgrown.

## Consequences

- Eight changes that the archive refused are archived, and the corpus of live
  capabilities holds what the code has been shipping.
- The `text-input` capability exists in git for the first time. It had lived only in
  the deltas of the branches that built it, which is why two of them could mint the
  same id without either of them seeing the other.
- The spec carries two requirements whose ids do not look like their neighbours:
  `REQ-TI-SOURCE-KIND-1` and `REQ-SEARCH-016`. The numbers are free because the ids
  that would have been natural were taken by different requirements.
- One removal had to be applied by hand, because the block that carried it named
  a requirement nobody had added. The archive cannot see that: it applies a
  removal by id, and an id that is not there removes nothing and reports
  nothing.
- The corpus of requirement ids is only unique because this repair was careful. The
  next parallel pair of branches can mint the same id again, and the archive will
  refuse again — the refusal is the tool working, and the repair is the work.
- `MODULE-INDEX.md` lists eighteen capabilities it did not list before, four of which
  were missing before this change and were not created by it.
- Four requirements of `test-execution-integrity` now answer to `REQ-27` to `REQ-30`.
  Their titles are unchanged, so a reader who knows the title still finds them; a
  reader who knows the old id does not, which is why this decision names the rename.
- One archived delta and the live spec still differ in two words:
  `background-handler-injection` wrote "a component that starts background work
  **on its own**", and the live requirement of `crash-reporting` says "a component
  that starts background work". The live spec was written by a later commit, the
  wider wording is the stronger obligation, and the archive keeps the branch's own
  words. Nothing is lost by the difference, and nothing is gained by closing it.
- The proposal, the delta and the live requirement of
  `the-editor-and-the-rewrite-from-outside` name the same two kinds of source. A
  reader who opens any one of the three finds the same answer.

## Links

- ADR `2026-10-20-an-archived-change-lives-in-the-live-spec.md` — why an archived
  change lands in the live spec
- ADR `2026-10-05-doc-gates-must-parse-structure.md` — the index of covered modules
  and why it is kept honest
- `openspec/specs/MODULE-INDEX.md` — the index this change updates
- `openspec/specs/text-input/spec.md` — the capability that was never in git
- `openspec/specs/test-execution-integrity/spec.md` — the four requirements that
  were renumbered to `REQ-27` to `REQ-30`
- `openspec/specs/notes/spec.md` — the requirement that names the two kinds of a
  field's source
- `shared/src/commonMain/kotlin/com/singularity/todo/core/text/TextFieldSource.kt` —
  the sealed source the two requirements describe
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldRewriteContractTest.kt`
  — the check that reads the shape of the code
