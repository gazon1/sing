---
title: "The Allure publication is advisory: it does not block a merge or a release"
date: 2026-11-19
status: open
tags: ["ci", "allure", "github-pages", "gates"]
---

## Context

`publish-allure` is a leaf of `ci-gate`, the single check that branch protection
requires, and `release.yml` requires a successful `ci.yml` run for the tagged
commit (`docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md`,
`docs/decisions/2026-10-07-branch-protection-is-unavailable.md`).

The chain is therefore:

```
publish-allure → ci-gate → run conclusion → release.yml
```

Everything the publication does is downstream of that chain. The publication
builds an Allure report and deploys it to GitHub Pages; nothing about it
describes the commit under test. But on 2026-11-19 the job failed — the
third-party action it used rebuilt a Docker image on the runner, and the image
downloaded the Allure CLI from Maven Central with `wget` and without a retry;
Maven Central answered HTTP 429 — and `CI gate` failed with it, and the
repository could not ship `v0.2.2` until the whole run was re-run.

The flake is upstream's. The exposure is ours.

## Idea

Options considered:

1. **Re-run the run.** What was done that day. It works until the next
   rate-limited run, and it costs a full CI cycle per flake.
2. **Make the publication non-blocking.** The job fails, stays visible in the
   run, and does not fail the run or the gate.
3. **Remove the publication from the gate's needs.** The gate would no longer
   wait for it, and the run could report green while the publication was
   still running — the gate would report on a run that has not finished.
4. **Replace the third-party action with our own build.** It removes the cause
   rather than tolerating the symptom. It is recorded separately: the change
   that owns the report build does not change the contract of the gate.

## Decision

The publication is **advisory**: it is waited for, and its failure does not
fail the run and does not fail the gate.

Two halves are needed, and one of them alone is not enough:

1. **The job is marked `continue-on-error: true`.** The job still fails and the
   failure is visible in the run, but the run's conclusion is not the
   publication's failure. Without this, `release.yml` — which looks for a
   successful `ci.yml` run of the tagged commit — sees a failed run.
2. **`ci-gate` judges the publication apart from the other leaves.** The gate
   waits for `publish-allure` and reads its result into its own variable
   `ALLURE`. A `failure` there emits `::warning::the Allure report was not
   published` and does not fail the gate. A `cancelled` publication *does*
   fail the gate: a cancelled publication is not a failure of the report, it
   is a failure of the run.

Without the second half, the first half buys nothing: a tolerated failure in
a job that the gate requires is a gate that fails, and a gate that fails is a
release that does not happen.

The other leaves keep the old judgement exactly: `success` or `skipped` pass,
anything else fails the gate. The gate reads each leaf into its own variable
instead of `join(needs.*.result, ' ')` — a flat list cannot tell a required
leaf from an advisory one, and the judgement of a leaf must be readable from
the gate's own text.

**What is not decided here:** the exact value `needs.publish-allure.result`
reports for a job marked `continue-on-error`. The gate accepts `success`,
`skipped` and `failure` for the advisory publication, so it does not depend on
that value. This ADR asserts only what is observable: the run's conclusion is
not the publication's failure, and the gate does not fail for it.

## Rationale

- The publication is a report about the run, not a verdict on the commit. A
  mirror of Maven Central that rate-limits, or a Pages deployment that does
  not answer, says nothing about the code that was tested.
- The gate is the only check that blocks merges *and* releases. Everything
  that is a leaf of the gate is therefore on the critical path of shipping,
  and a leaf has to earn that place. This one publishes a report; it does not
  verify the commit.
- A failure that is tolerated must still be visible: the job fails, the run
  shows it, and the gate names it as a warning. Silently swallowing it would
  make the report quietly disappear.
- The cancellation case is deliberately not tolerated. A cancelled publication
  usually means the run was cancelled or superseded, and that is a fact about
  the run, not about the report.
- Replacing the action (idea 4) removes this particular cause, but it does not
  remove the class: any future leaf of the gate inherits the same exposure.
  The contract of the gate is stated here, once.

## Consequences

- `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md` says
  that every leaf's result is `success`; that is amended here for the
  publication leaf, which is judged apart from the rest.
- The gate no longer reports "no failures among all leaves" as a single
  property; it judges the required leaves and the advisory leaf separately.
  The verdict is unchanged for every leaf that was already required.
- A report that could not be published is a warning in the run and a stale
  report on Pages. The links that point at the latest report are posted by a
  step that is already advisory, so a failed publication leaves the previous
  report linked — the links point at the last report that was published, not
  at the run that failed.
- The publication is still a leaf the gate waits for. The gate does not
  report on a run whose publication has not finished.

## Links

- Issue: [#3007](https://github.com/gazon1/sing/issues/3007)
- Workflow: `.github/workflows/ci.yml` (`publish-allure`, `ci-gate`),
  `.github/workflows/release.yml`
- Prior decisions: `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md`,
  `docs/decisions/2026-10-07-branch-protection-is-unavailable.md`,
  `docs/decisions/2026-11-10-allure-publication-latest-history-and-retention.md`
- Tests: `scripts/tests/test_allure_publication_workflow.py`
  (`AdvisoryPublicationTest`, `GateScriptTest`)
