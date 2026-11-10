---
title: "CI does not create OpenSpec changes"
date: 2026-11-09
status: accepted
tags: ["ci", "spec-kit", "tooling"]
---

# CI does not create OpenSpec changes

## Context

The `spec-create` job in `.github/workflows/ci.yml` ran the `openspec-change`
spec-kit workflow on every pull request that touched an ADR path, then committed
whatever it produced back into the branch.

The job could not do what it was written to do, and it hid that. The run of
2026-11-09 (job `spec-create`, 123 s) is in the recorded CI log:

```text
Running workflow: OpenSpec Change Workflow (openspec-change)
Error: Required input 'title' not provided.
Workflow run completed with warnings
...
No changes to commit
```

Two things had to go wrong for that to be a green job. The workflow run failed,
and `|| echo "Workflow run completed with warnings"` turned the failure into a
sentence. The commit step then found nothing to commit, because the failed run
had produced nothing.

And the failure was not the only reason the job could not work. The
`openspec-change` workflow has three `type: gate` steps — `review-proposal`,
`review-spec`, `review-design` — each with `options: [approve, reject]`. A gate
waits for a human. A CI runner has no TTY and no human, so the run cannot pass
them even when its inputs are right.

What the job did leave behind: `permissions: contents: write` on every
pull request, a push of bot-authored commits into a reviewer's branch, and 123
runner-seconds.

## Idea

- A job that cannot succeed and cannot fail is not a check; it is a cost.
- Keep the capability where it can work: the same workflow, run locally, where
  the gates have a human to answer them.

## Decision

Delete the `spec-create` job.

The workflow template stays: `.specify/workflows/openspec-change/workflow.yml`
is what the local spec-kit flow runs, and `scripts/check-review-verdicts.py`
reads it to recognise a scaffolded change (the `.openspec.yaml` marker). Only
the CI job goes.

`docs/spec-kit-guide.md` says so, in the place where it used to list the job.

## Rationale

**The measured run says the job did nothing.** It errored in its first second
and committed nothing. A job that spends two minutes to produce an error it
swallows is the definition of a surface that costs time and returns nothing.

**Fixing it properly is a feature, not a repair.** Making it work means
either auto-approving the three review gates in CI — which removes the human
from a review step — or splitting the workflow so CI runs only the scaffolding
phase and leaves the review to a person. Either is a design decision with its
own ADR, and neither is a lean fix of a red job.

**The write permission is the risk.** The job held `contents: write` on every
pull request and pushed into the reviewer's branch. Removing the job removes
that permission from the pull-request path; the tokens CI holds are not a
place for a job that does not work.

**The capability is not lost.** The intent — "a change starts as a scaffolded
proposal, a human reviews it" — is what the local workflow does, with the gates
answerable.

## Consequences

- 123 runner-seconds and a `contents: write` token leave the pull-request path.
- The `adr` path filter stays: `adr-gate` still uses it.
- A pull request no longer receives bot-authored commits in its branch.
- Nothing depended on the job: no gate names it, no other workflow needs it,
  and `scripts/check-recorded-ci-jobs.py` is one-sided — a job that is not in
  the recorded run is not a finding.

## Links

- `.github/workflows/ci.yml` — the workflow the job was in
- `.specify/workflows/openspec-change/workflow.yml` — the template that stays
- `scripts/check-review-verdicts.py` — reads the template, not the job
- `docs/spec-kit-guide.md` — the page that described the job
- `docs/decisions/2026-10-11-spec-kit-migration-plan.md` — the plan that
  introduced the job
