---
change: the-verdict-names-the-commit
verdict: fail
date: 2026-10-23
pr: https://github.com/gazon1/sing/pull/988
reviewed: a7873430855069794f64ff7cf4c8d81d7b2d9c6a
documents: sha256:65b9c060b196d965225d468963d5fcbac7a23c09996a08c03118590bdb3a17fa
---

## What was reviewed

The whole change: `openspec/changes/2026-10-23-the-verdict-names-the-commit/`
— the proposal, the spec delta and the tasks — against the code it describes:
the gate `scripts/check-review-verdicts.py`, the tool `scripts/ci-verdict.py`,
the rules in `CODING_STANDARDS.md`, the pointers in `AGENTS.md`, the ADR
`docs/decisions/2026-10-23-the-verdict-names-the-commit.md` and the three
verdict files in `.specify/reviews/`.

The reviewer was a separate agent with read-only access, given the diff of the
branch and the documents, and nothing else: no history of writing the code and
no pressure to find nothing wrong. It was asked for defects with a location,
not for a summary of the diff, and it was told to check the claims in the
documents against the code rather than to run the build.

`reviewed:` is the commit the review was made against — the head of the branch
at that moment. It is a pointer, not a claim the gate can check: the commit is
not an ancestor of `main`, because the pull request will land by squash.

`documents:` is the digest of the contract as this verdict speaks for it — the
change's proposal and its spec delta. It is **not** the digest of the contract
the reviewer saw: the spec delta moved afterwards. The reviewer found that
REQ-1 stated the same requirement twice, and the duplicate paragraph was
deleted — the rule in action, a contract that moves is re-issued in the same
diff that moves it, and this file is that re-issue. The gate recomputes the
digest and compares, so the value here is the one the tree carries now
(`python3 scripts/check-review-verdicts.py --digest the-verdict-names-the-commit`).

## The first review: FAIL

One blocking defect and six smaller ones. The blocking one was a claim of ours,
and it was the rule catching us.

1. **Blocker — the claim about the existing verdict was false.** The verdict of
   `ci-fast-only-pr-full-on-schedule` said the change's documents were
   byte-identical at the reviewed commit and in the tree, and cited a
   `git diff` that is not empty: PR #986 (`745b3afa`) added REQ-25 to the
   contract. The digest recorded was the digest of the contract as it was, and
   the contract had moved. The rule was rewritten: a verdict speaks for the
   contract it carries, and a contract that moves is re-issued in the same
   diff, where a reviewer sees both.
2. **Should-fix — the gate's remediation could not clear the gate.** It advised
   writing the digest of the documents that were reviewed, which is the value
   the gate rejects.
3. **Should-fix — the gate's note in the registry described the old format.**
4. **Should-fix — the tool had a fourth exit code** — the run list could not be
   read — that no document mentioned.
5. **Should-fix — the tool's docstring showed the workflow as `CI`**, which is
   the display name GitHub reports; the tool prints the file name it was given.
6. **Should-fix — the docstring of the digest overstated its coverage**: a
   root-level `design.md` is outside the digest, deliberately.
7. **Note — `reviewed:` is only checked for shape**, so it is a pointer and not
   a verified reference: a squash-merge takes the reviewed commit out of the
   history, and a shallow clone does not have it either.

## The second review: the fixes hold, three smaller ones, one blocker

The second pass checked the fixes against the old code and confirmed that the
blocker was genuinely closed. It found the rest:

8. **Blocker — the branch was red on its own rule.** Three verdicts carried no
   `reviewed:` and no `documents:`, and the change had no verdict of its own.
9. **Blocker — two claims in the change's own task list were false.** Task 1.5
   still asserted the disproved "byte-identical" premise, and task 5.4 was
   ticked while the file it names did not exist.
10. **Should-fix — the ADR's Decision section and `AGENTS.md` still stated the
    old rule** — "the digest of the documents the review saw" — while the
    Consequences stated the new one.
11. **Should-fix — the importer had overwritten two hand-written registry
    records** with its own lossy output: the title became the slug, the date
    became `unknown`, the ADRs and the tags were dropped.
12. **Note — a run that named no workflow was taken as the workflow asked for**,
    so a captured list without the name could report the cache cleanup as the
    build.

## The third review: three smaller ones, one blocker

The third pass verified the second round's fixes against the tree — the two
hand-written records are byte-identical to `main`'s, the importer's guard holds
even with `--replace` and the test for it fails when the guard is removed, the
tool rejects an unattributable run, and the two false claims in the task list
are gone. It found:

13. **Blocker — the branch was still red on its own rule**: the verdict of this
    change did not exist. That is what this file is.
14. **Should-fix — the ADR's Consequences still stated the old framing** —
    "the review has to be redone" — while its Decision section stated the
    re-issue rule. One rule now, in both places.
15. **Should-fix — the review skill stated the old framing** and never stated
    the re-issue rule. It is the surface a review agent follows when it writes
    a verdict for a change whose contract moved.
16. **Should-fix — the illustration of "a number the tree can change" was not
    reproducible.** It quoted a count of test classes that no rule in the
    repository produces. The illustration now quotes one number with the
    command that produces it: `docs/engineering/script-tooling-audit.md` says
    1676 Kotlin files, and the tree is now 1915 (`git ls-files '*.kt' | wc -l`).
17. **Note — REQ-1 stated the same requirement twice.** The duplicate
    paragraph is deleted.

## What was done about it

The verdict stays **fail**, because the reviews found something: that is what
this file is for. The findings were fixed in the same branch:

- The two verdicts of the task-series changes carry the two fields, and the
  digests they carry are the digests of contracts that have not moved.
- The importer leaves a record it does not own alone: a hand-written
  `.specify/issues/*.yaml` says `speckit_imported: false`, and the importer
  skips it instead of replacing it with a lossy version.
- The tool attributes a run to no workflow when the run names no workflow, and
  says there is no run of the workflow that decides.
- The rule is stated once, in the ADR's Decision section and its Consequences,
  in the spec delta, in the proposal, in the gate's docstring, in `AGENTS.md`,
  in the review skill and in the three verdict files: a verdict speaks for the
  contract it carries, and a contract that moves is re-issued in the same diff
  that moves it.
- The numbers the branch quotes are numbers a reader can reproduce.

## Links

- ADR `docs/decisions/2026-10-23-the-verdict-names-the-commit.md`
- The gate `scripts/check-review-verdicts.py`
- The tool `scripts/ci-verdict.py`
