---
change: the-proposal-says-what-it-is-about
verdict: pass
date: 2026-10-24
pr: 1097
reviewed: bf9383f2eedc9045144c74d613d31eabede4fba3
documents: sha256:058a85add9c95eec7ec8b8cafac8997bb6c298f486b74e6a0812af6d51d9fda4
---

## What was reviewed

Three things about a proposal were said twice, and every place where they disagreed
was a place a user could feel. The change is on
`gazon1/feat/the-proposal-says-what-it-is-about`, and the review is of the diff of the
whole change — the model, the port, the mapper, the DAO, the use cases, the three
surfaces, the fakes, the gate and the tests — against the contract the change carries
(`openspec/changes/2026-10-24-the-proposal-says-what-it-is-about/`, REQ-PROP-027…029).

Two independent review rounds were run by a separate agent: the first over the change as
written, the second over the fixes that came out of it. Every finding of both rounds
was fixed before the branch was pushed, and the second round read the fixed tree.

## What the change closes

**The kind of target was written by hand in thirteen places.** The builder's own
comment admitted the risk — *"a caller that gets the kind wrong writes a proposal the
confirmation screen will not find"* — and nothing checked the string against the
change next to it. `ProposalTargetKind` is now a type, the target declares its kind,
the builder reads it from the change, and the caller cannot write it: a change offered
the id of another kind of target is refused rather than written. All thirteen call
sites lost the line.

**A status this build does not know was read as `Pending`.** `Pending` is the one
status that says "this change can still be applied", so a row written by a build that
knew more was read as waiting, and a change that had already been applied was applied
a second time. The same column was read in two ways in two places — the mapper filled
the gap, the repository's reducer did `valueOf` and threw. There is one reader now,
it is strict, and it has a state for a name it cannot name: `Unknown(raw)`, which is
neither waiting nor decided. The change stays on the card with the reason, and the
user can still turn it down, because the compare-and-set expects the status the
change was read with.

**The status of a proposal was a column, and the surfaces believed it.** The review
screen asked the row for "pending" and then filtered the answers by the changes; when
the row and the changes disagreed, the row decided which proposals arrived and the
changes decided which were shown. The status is derived from the changes now, the
port asks the domain's question (`watchWaitingProposals`), the surfaces ask the
proposal (`isWaiting`), and the gate that already forbade a surface from naming an
item's status forbids a proposal's status as well.

## What was checked

- **The kind of target.** The four kinds carry the values the rows already carry;
  `Unknown(raw)` carries a name this build does not know; `ProposalTarget` declares
  its kind; a creation declares what it brings into being; a mixed batch is refused;
  `forTask` and `forNote` refuse a change that is about something else; the thirteen
  call sites that wrote the kind by hand do not.
- **The status.** The four known states and `Unknown(raw)`; `isPending` is exactly
  `Pending`; `isDecided` is a decision; an unknown status cannot be applied and can
  still be turned down; the name the status was found with is kept, so the row is
  written back unchanged; the reason is with the change and the refusals name it.
- **The status of a proposal.** It is derived from the changes; the stored column is
  written and not read into the domain; the SQL asks the same question the domain
  asks; the surfaces filter by `isWaiting` and none of them names a status.
- **The gate.** `ProposalLifecycleStaysInTheDomainTest` forbids a surface from naming
  a status, and its positive test says what the domain must offer in exchange — the
  rule would otherwise pass by forbidding everything.
- **The runs.** `:shared:compileTestKotlinJvm` — clean; the targeted suites
  (proposals, arch, ai, notes, tasks) — 973 tests, no failures; `:shared:jvmTest
  -Ptest.population=full` — 507 classes, 4289 tests, 0 failures; `just lint-changed`
  — no findings; `just detekt-fix` — 0 findings; `scripts/ci/static-gates.sh`; the
  Python suite — 2619 tests. The full suite and the Python suite were run again on
  the rebased tree, against the `main` this branch was pushed from.

## What was not verified

- **The compiler checks were not verified by breaking the code.** The exhaustiveness
  of the `when` over the status — every one of them has to handle `Unknown` — and the
  refusal of a change that is about another kind of target are enforced by the
  compiler, but nobody watched the build fail on a deliberately mis-routed change.
  The claim rests on the type rules, not on an observed failure.
- **A row written by a newer build was not observed on a device.** The path is
  covered by tests that write a status this build does not know and read it back, and
  by a test that turns such a change down; what was not done is a change written by a
  newer build arriving on a device running this one.
- **The stored summary column is still written.** It is a cache now and nothing reads
  it into the domain, but it is still there, and it will drift from the derived status
  until the next write. Nothing reads it, so nothing is decided by it — yet.
- **The flat reader and the version envelope.** The reader is still strict about the
  *shape* of a change and still cannot tell that a field with a default was dropped
  by a newer build. Both are follow-ups: [#1064](https://github.com/gazon1/sing/issues/1064)
  and [#1065](https://github.com/gazon1/sing/issues/1065).
