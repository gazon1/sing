# Tasks — the-verdict-names-the-commit

## 1. The verdict says what it reviewed

- [x] 1.1 Add `reviewed:` (the commit) and `documents:` (the digest of the
      change's contract documents) to the verdict file the gate reads, and to
      the docstring that states the format.
- [x] 1.2 Compute the digest over the change's proposal and its spec deltas —
      the contract — and not over the task list, which moves with the work.
- [x] 1.3 Recompute the digest in `check-review-verdicts.py` and report a
      change whose contract moved under its verdict, naming both digests.
- [x] 1.4 Add `--digest <slug>`, so the value the verdict records and the
      value the gate checks come from one implementation.
- [x] 1.5 Give the verdicts that already existed on `main` the two fields. For
      `ci-fast-only-pr-full-on-schedule` the contract was checked against the
      commit the review was made against and against the tree today, and the
      two are **not** the same: PR #986 added REQ-25 to the spec delta. The
      verdict carries the digest of the contract as it stands, and its prose
      says what the review covered and what arrived later. The two verdicts of
      the task-series changes carry the digest of a contract that has not
      moved.
- [x] 1.6 Say the fields and the order of writing in `AGENTS.md` and in the
      review skill, so the next session writes a verdict the gate accepts.

## 2. A CI claim is a line, not a run id

- [x] 2.1 Add `scripts/ci-verdict.py`: the workflow, the run and the
      conclusion of the commit, a branch or a pull request.
- [x] 2.2 Make the exit code the conclusion — 0 for success, 1 for anything
      else, 2 when there is no run — and make a missing run a report, not a
      pass.
- [x] 2.3 Filter the runs by the workflow that decides, on the display name
      `gh run list` reports, so a successful cache cleanup is not the claim.
- [x] 2.4 Test the reading of a run list without asking GitHub: the workflow
      that answers, the newest run that answers, a run that has not concluded,
      and a list with no run of that workflow.
- [x] 2.5 Point at the tool from `AGENTS.md`, and add it to the catalogue of
      the tools.

## 3. The rules the reviewer applies

- [x] 3.1 "A gate that derives a set from the tree proves the set is
      complete": the set is compared with an independent enumeration, and the
      comparison is part of the gate.
- [x] 3.2 "A number that the tree can change is generated, not written" — with
      the distinction that an ADR is history and a living document is the
      present.
- [x] 3.3 "A claim about CI names the workflow, the run and its conclusion".

## 4. The scanner answers to the name it is called by

- [x] 4.1 Rename `_read_tags`, `_read_tag` and `_test_classes_in` to the public
      names they are re-exported under, and say in the module's docstring why
      they were private.
- [x] 4.2 Point at `scripts/scanning.py` from `AGENTS.md`, so the entry point
      of the scanner is one line away from where the rules are.

## 5. The change is on the record

- [x] 5.1 The change, its proposal and its spec delta.
- [x] 5.2 The ADR.
- [x] 5.3 The registry record, written by the importer.
- [ ] 5.4 The verdict of the review, naming the commit and the digest. It is
      written when the review of the finished diff has a verdict to record —
      a checklist item that names a file is ticked when the file is there.

## 6. What the review found, and what was done about it

The review of the diff found one blocking defect and six smaller ones. The
blocking one was a claim of ours, and it was the rule catching us.

- [x] 6.1 **Blocking — the claim about the existing verdict was false.** The
      verdict of `ci-fast-only-pr-full-on-schedule` said the change's
      documents were byte-identical at the reviewed commit and in the tree,
      and cited a `git diff` that is not empty: PR #986 added REQ-25 to the
      contract. The digest we recorded was the digest of the contract as it
      was, and the contract had moved. The rule now says that a verdict
      speaks for the contract it carries and a contract that moves is
      re-issued — in the same diff, where a reviewer sees both. The verdict
      file carries the digest of the contract as it stands, and its prose
      says what the review covered and what arrived later.
- [x] 6.2 The gate's remediation could not clear the gate: it advised writing
      the digest of the documents that were reviewed, which is the value the
      gate rejects. It now says what clears it — re-review, and re-issue the
      verdict with the digest the change carries now.
- [x] 6.3 The gate's note in the registry still described the old format, so
      a reader who took the registry for the spec wrote a verdict the gate
      rejects. The note carries the two new fields. The registry document
      renders the purpose and the exit codes from the manifest, and it was
      regenerated from it — it changed nothing, because the note lives in the
      manifest and not in the rendered document.
- [x] 6.4 The tool has a fourth exit code — the run list could not be read —
      that no document mentioned. It is in the spec delta, the proposal, the
      ADR and the review rule now.
- [x] 6.5 The tool's docstring showed the workflow as `CI`, which is the
      display name GitHub reports; the tool prints the file name it was
      given. The example says what the tool prints.
- [x] 6.6 The docstring of the digest said "the documents of a change that
      state what it must do", which overstates it: a root-level `design.md` is
      outside the digest, deliberately. The docstring says what the contract
      is, and the spec delta says that a root-level document is outside it.
- [x] 6.7 `reviewed:` is only checked for shape, so it is a pointer and not a
      verified reference: a squash-merge takes the reviewed commit out of the
      history, and a shallow clone does not have it either. The docstring and
      the ADR no longer call it a pointer the gate follows.

## 7. What the second review found

The review of the fixed diff found two blocking defects and three smaller ones.
One of them was the rule catching us again, and one was damage this change
carried into a file it did not mean to touch.

- [x] 7.1 **Blocking — the branch was red on its own rule.** The gate this
      change adds reads every open change, and the branch had three verdicts
      without the two new fields and no verdict of its own. The two verdicts of
      the task-series changes carry the fields now, and this change's own
      verdict is written when the review of the finished diff has one to
      record.
- [x] 7.2 **Blocking — two claims in this list were false.** The first review
      had already disproved the "byte-identical documents" premise, and task
      1.5 still carried it; and task 5.4 was ticked while the file it names
      did not exist. Task 1.5 now says what was checked, and 5.4 is unticked
      until the file is there.
- [x] 7.3 The ADR's Decision section and `AGENTS.md` still stated the old
      rule — "the digest of the documents the review saw" — while the
      Consequences, the spec delta and the gate docstring stated the new one.
      Both now state the rule as it is: the digest is of the contract as the
      verdict speaks for it now, and a contract that moves is re-issued in the
      same diff.
- [x] 7.4 The importer overwrote two hand-written registry records with its
      own lossy output: the title became the slug, the date became `unknown`,
      and the ADRs and the tags were dropped. The records are restored, and
      the importer now leaves a record that says `speckit_imported: false`
      alone — a record it does not own is not its to replace.
- [x] 7.5 A run that named no workflow was taken as the one that was asked
      for, so a captured list without the name could report the cache cleanup
      as the build. An unattributable run is attributed to no workflow now:
      the tool says there is no run, and the exit code is two.
