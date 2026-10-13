---
name: speckit-engineering-explore
description: 'Investigate an internal engineering problem in the Singularity Todo codebase — read the real code, its tests, ADRs and coroutine dumps — and write investigation.md separating verified facts from hypotheses. First step of Engineering SDD; modifies no application code.'
disable-model-invocation: true
---

# Engineering SDD — Explore

## User Input

```text
$ARGUMENTS
```

`$ARGUMENTS` carries `slug`, `depth` and `request`.

## Target

**Read first, every time:** `.specify/templates/engineering/conventions.md`. It holds the house
rules, the mandatory pre-read chain and the change-directory layout. Do not proceed without it.

- Create: `openspec/changes/<slug>/` and `openspec/changes/<slug>/.openspec.yaml`
- Write: `openspec/changes/<slug>/investigation.md`

## Goal

Turn the request into a set of **verified facts** about how the system behaves today, plus an
explicit list of what is still a hypothesis. Produce nothing else.

### 1. Validate the slug

Before creating anything, check that `slug` matches `^[a-z0-9]+(-[a-z0-9]+)*$`.

If it does not, **stop and report the offending slug**. Do not create a directory. The slug becomes
a path segment that later workflow steps open, so it must not contain `..`, `/`, spaces or shell
metacharacters.

### 2. Create the change directory

`mkdir -p openspec/changes/<slug>/` — safe to repeat.

Write `.openspec.yaml` if it does not already exist:

```yaml
schema: spec-driven
# Engineering work usually changes no observable capability contract, so the
# marker starts here. speckit-engineering-specify removes it if the change does
# alter one, and must state its reason either way.
#
# Invariant: this marker and a specs/ directory are mutually exclusive.
skip_specs: true
```

Do not overwrite an existing `.openspec.yaml` — a later step owns it.

### 3. Investigate

Follow the pre-read chain in `conventions.md`. Then:

- Open the files the request names, and the callers and callees of what you find.
- Read the **existing tests** for those files. They are the best available evidence of intended
  behaviour, and where they disagree with the code that disagreement is itself a finding.
- Search `docs/decisions/` for ADRs that already decided part of this. Do not re-decide them.
- If this is a diagnosis, follow the repo's `debugging-investigation` skill and read
  `build/diagnostics/<TestClass>/coroutines.txt` first if it exists — a coroutine dump shows where
  the coroutine was, where it died, and what `lastObservedStackTrace` says.

### 4. Write `investigation.md`

Required sections, in order:

```
# <title>

## Request
## What the code actually does
## Reproduction
## Verified facts
## Hypotheses
## Open questions
## Constraints found
## References
```

- **What the code actually does** — every claim carries `path:line`. If you cannot cite a line,
  the claim goes under Hypotheses instead.
- **Reproduction** — the exact command you ran and what it printed. If you did not reproduce the
  symptom, write "Not reproduced" and give what you tried. Do not describe expected output as
  observed output.
- **Hypotheses** — each with an explicit *what would confirm it* and *what would refute it*.
- **Constraints found** — existing patterns the solution must respect: Koin DI rules, the
  `AutoCloseableCoroutineScope` ownership convention, the Room schema rules, the test-tag
  convention, detekt rules, anything in `AGENTS.md` that bites this area.

## Forbidden

- Editing application code, tests, Gradle files, or any file outside
  `openspec/changes/<slug>/`.
- Running anything that mutates the working tree — no formatters, no codegen, no `--fix` flags,
  no `git add`, no dependency downloads.
- Writing a cause. You are establishing what happens, not why. "It is a race in `path/to/Foo.kt`"
  is a conclusion and belongs in the spec, not here.
- Filling a section with a plausible guess to make the document look complete. "Unknown — needs a
  repro run" is a better entry than an invention.
- Silently dropping a required section. If one does not apply, keep it and say why.

## Done When

- `openspec/changes/<slug>/` exists with a `.openspec.yaml` and a non-empty `investigation.md`.
- Every claim under `## Verified facts` carries a `path:line` or a command that was run.
- No file outside `openspec/changes/<slug>/` has been modified.