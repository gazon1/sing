---
title: "Engineering SDD: a separate workflow for internal changes, with its own skills and artifact contract"
date: 2026-10-13
status: accepted
tags: ["spec-kit", "sdd", "workflows", "testing", "tooling"]
---

# Engineering SDD: a separate workflow for internal changes, with its own skills and artifact contract

## Context

`openspec-change` is the product pipeline: user stories, capability deltas, Given/When/Then
scenarios. It is the right shape for behavior a user can observe.

A second class of work does not fit it. State machines, coroutine lifecycle, cancellation, sync and
queue correctness, flaky-test diagnosis, and refactors whose behavior must be preserved are invisible
to users — but their correctness is not. They are characterized by behavior that appears only under
a *specific sequence* of events, a failure, a cancellation, or a restart.

Today that work is done by hand in a chat with an agent: the agent investigates, the human pokes at
it in the transcript, states get modeled in prose, and the reasoning that mattered dies with the
session. Nothing survives to the next agent, and there is no artifact that says which invariants
must hold or how anyone knows they do.

Three things were verified about the environment before choosing a mechanism:

1. **Spec Kit 1.1.3 supports what this needs natively** — local workflows, `gate`, `if`, `shell`,
   `command`, `enum` inputs, and `specify workflow info` to validate before running. No extension,
   no fork, no engine.
2. **The stock `/speckit.*` commands cannot be reused here.** They resolve `FEATURE_DIR` to
   `specs/<NNN>-<slug>/` (`create-new-feature.sh:423`, `common.sh:188-230`), while engineering
   artifacts belong in `openspec/changes/<slug>/`. `speckit.converge` reads `FEATURE_DIR/spec.md`
   and aborts if it is missing. Mixing them splits one change across two trees — the same defect
   that makes `check-speckit-issue-registry.py` report 577 errors in 633 files.
3. **A `shell` step is not a safe place for user input.** `ShellStep` expands `{{ ... }}` and then
   runs the result with `shell=True`, so free text in a `run:` block *is* executed shell. The
   existing `openspec-change`, `adr-create` and `adr-batch` workflows have 12 such sites, and
   `adr-gate.yml:115` passes an unsanitized pull-request title into one of them.

## Idea

A second workflow, `engineering-sdd`, with its own skills, its own artifact contract, and its own
two human gates — sharing the `openspec/changes/` tree and the existing issue registry conventions,
and nothing else.

## Decision

**Add `.specify/workflows/engineering-sdd/workflow.yml` as a local workflow**, registered in
`workflow-registry.json` with `source: local`.

**Own all eight steps.** `explore`, `specify`, `model`, `verify-design`, `plan`, `implement`,
`verify`, `converge` — each a skill at `.agents/skills/speckit-engineering-<name>/SKILL.md`, invoked
as `command: speckit.engineering.<name>`. **No `speckit.plan` / `speckit.tasks` /
`speckit.implement` / `speckit.converge`**, so nothing depends on `FEATURE_DIR` and every artifact
lands in one directory.

**Eight artifacts, one concern each**, in `openspec/changes/<slug>/`: `investigation.md`,
`spec.md`, `model.md`, `verification.md`, `design.md`, `tasks.md`, `results.md`, plus
`.openspec.yaml`. `design.md` is the plan — there is no separate `plan.md`. Acceptance criteria live
in `spec.md`, their strategy in `verification.md`, their outcome in `results.md`.

**Bind every `AC-NN` to a resolvable test id** (`TestClass::testFunction`) plus the command that
runs it. The test does not exist when the spec is written; naming it anyway is what makes the
binding checkable later instead of aspirational. No standard format does this — spec-kit's own
traceability is editorial — so this is a deliberate gap we fill.

**Two gates, not three.** `review-spec` confirms what must be true (problem, invariants, model,
verification strategy, blocking unknowns); `review-plan` confirms how to build it. Investigation is
reviewed *at* `review-spec` rather than at a gate of its own — a third interruption would buy a
decision nobody makes separately.

**Keep `verify-design` and `verify` apart, and `converge` apart from both.** Designing tests before
code and running them after are different epistemics; merging them lets evidence be retro-fitted.
`converge` is a second reading of artifacts against code by an agent that did not write the code —
that independence is the entire reason it is a separate subprocess.

**Keep `model` mandatory but allow `Not applicable`.** The step always writes `model.md`, which must
contain either a real transition table or a concrete justification for having no states. A
fabricated machine is worse than an honest omission.

**Interpolate no user text into any `run:` block.** `slug`, `title` and `request` reach agent steps
only through `command:` `args` (a single argv element, no shell). `depth` is an `enum`.
`assert-artifacts` takes no interpolation at all: it discovers the change directory from the newest
`investigation.md`, re-validates the slug against `^[a-z0-9]+(-[a-z0-9]+)*$` before building any
path, and fails loudly when it finds two candidates.

**Do not write the issue registry in 0.1.0.** The validator requires `version`, `type`, `slug`,
`status` and an existing `openspec_path`, and the corpus is already 577/633 failing. The final step
prints the template and the validation command. An unvalidated writer pollutes the corpus rather
than helping it.

**Ship no validator, and change no CI.** A gate should be written against documents that have
survived real use.

## Rationale

- **A separate workflow, not a preset.** The two pipelines ask different questions. A preset that
  overrides templates for one of them makes "which process is this?" harder to answer, not easier.
- **Own skills rather than reuse.** Reusing `speckit.*` would mean either duplicating artifacts into
  `specs/NNN-*/` — recreating the split that already produces 577 registry errors — or abandoning
  `openspec/changes/`, which is where the corpus, the traceability gates and
  `openspec validate --strict` already live.
- **Markdown artifacts, not a new format.** The point is that the reasoning survives, not that a new
  parser exists. `.specify/templates/engineering/` keeps the shapes explicit without a generator.
- **Test-id binding costs nothing now and is the precondition for every later check.** It is the
  cheapest item in the plan with the largest payoff: a future `check-acceptance-bindings.py` can fail
  on a dangling `AC-NN` *or* on a test referenced by no criterion, which nothing mainstream does.
- **The transition table's columns mirror jqwik's state-machine model** (`Action`, precondition,
  `Transformer.mutate`, `withInvariant`). That is deliberate: the table stays prose for 0.1.0, but
  fixing the shape now makes a later move to property-based testing mechanical rather than a
  rewrite. Two constraints travel with it — jqwik provides **no virtual time**, so coroutine
  pipelines keep using `advanceUntilIdle()`; and jqwik must be pinned to **1.9.3**, because 1.10
  added an Anti-AI Usage Clause and 1.10.0 was withdrawn.

## Consequences

- **Good.** Internal engineering work produces the same durable, reviewable trail product work
  already does. Fact and hypothesis stop sharing a paragraph. "Tests exist" is separated from
  "tests can fail" — the latter is PITest's job, and the artifact now says which one it claims.
- **Good.** `engineering-sdd` is immune to the injection class by construction, and
  `docs/spec-kit-guide.md` now records the `shell=True` trust boundary so the next workflow does
  not rediscover it.
- **Good.** Eight skill files are independently reviewable and independently gated by
  `check-skill-frontmatter.sh`.
- **Cost.** Eight subprocesses per deep run. `quick` reduces documentation depth, not subprocess
  count — that is deliberate, since cutting subprocesses is exactly what would drop the checks that
  matter. Optimize from measurement, not from the plan.
- **Cost.** Eight skills duplicate the shared rules. `.specify/templates/engineering/conventions.md`
  is the single home; each skill opens with "read this first". If that indirection proves unreliable
  in practice, inline it.
- **Cost.** `verify` and `converge` read the same artifacts twice. That is the price of independence.
  Merge only after measuring real runs.
- **Cost.** `.specify/**` is absent from the `changes` paths-filter in `ci.yml`, so a workflow-only
  PR does not trigger the `static` job. Noted, not fixed here.

## Revisit when

- `verify` and `converge` have run several times and the double read has not paid for itself.
- The first automated validator lands — the natural first checks are `AC-NN` ↔ test-id binding in
  both directions, and `model.md` carrying either a transition table or a justification.
- A second engine consumes these artifacts and needs `model.md` in a parseable form (YAML/TSV),
  which is the moment the table stops being prose.
- The sibling defects are fixed and `openspec-change` becomes runnable again — at which point
  reconsider whether `engineering-sdd` should reuse its `design.md`/`tasks.md` steps.

## Links

- `.specify/workflows/engineering-sdd/workflow.yml`
- `docs/engineering-sdd.md` — developer guide
- `.specify/templates/engineering/conventions.md` — the shared contract every skill opens with
- `.agents/skills/speckit-engineering-*/SKILL.md` — the eight steps
- `docs/decisions/2026-10-04-measurement-integrity.md` — why "the gate ran" is not evidence
- `docs/decisions/2026-10-03-kotlinx-coroutines-debug.md` — the diagnostics `explore` reads first