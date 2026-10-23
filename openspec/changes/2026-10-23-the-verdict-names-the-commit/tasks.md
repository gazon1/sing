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
- [x] 1.5 Give the one verdict that already existed on `main` the two fields,
      after checking that its documents are byte-identical at the reviewed
      commit and in the tree today.
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
- [x] 5.4 The verdict of the review, naming the commit and the digest.

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
      rejects. The note carries the two new fields, and the registry document
      is regenerated.
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
