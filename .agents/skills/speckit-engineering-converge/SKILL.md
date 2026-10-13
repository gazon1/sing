---
name: speckit-engineering-converge
description: 'Independently compare spec.md, model.md, tasks.md and the actual code, then append a Divergences section to results.md. Ninth step of Engineering SDD; an independent second reading that reports only and edits nothing.'
disable-model-invocation: true
---

# Engineering SDD — Converge

## User Input

```text
$ARGUMENTS
```

`$ARGUMENTS` carries `slug` and `request`.

## Target

**Read first, every time:** `.specify/templates/engineering/conventions.md`.

- Read: `spec.md`, `model.md`, `design.md`, `tasks.md`, `results.md`, and the actual code.
- Write: **appends** `## Divergences` to `openspec/changes/<slug>/results.md`

## Goal

Answer one question: **does the code now do what the approved documents say it does?**

You are the second reader. `verify` ran the tests and knows what passed; you start from the
artifacts and the code and compare them independently. That independence is the reason this is a
separate step — an agent that just wrote the code is not a credible check on that code.

If you only agree with everything, you have not checked anything. Say so plainly rather than
manufacturing agreement.

### Append `## Divergences`

If `results.md` has no `## Divergences` section, append one. Never rewrite what is above it —
`verify`'s record of what actually ran is evidence and stays exactly as written.

```markdown
## Divergences

Added by speckit-engineering-converge.

### D-01 — <severity> — <one-line description>
- **Spec says:** <cite AC-NN or INV-NN, and the file:line of the requirement>
- **Code does:** <cite the file:line that contradicts it>
- **Impact:** <what a user or a future agent would get wrong>
- **Suggested resolution:** <and explicitly: not applied here>

### No divergence found in:
- <the areas you checked and found consistent, so the reader knows what was covered>
```

Severities: **critical** (a stated invariant is false, or data can be lost or corrupted),
**major** (required behavior is missing or wrong), **minor** (documented intent and reality differ
without behavioral consequence).

### What to check

- **Every `AC-NN`** — is it actually implemented, and does a named test cover it?
- **Every `INV-NN`** — is it true of the code, or only of the plan?
- **Every transition in `model.md`** — does the code take this transition, under this guard?
  A guard the code never evaluates is a divergence.
- **Every counterexample** — was a test written for it? An unrun counterexample is a known-unfixed
  risk, and belongs here.
- **Every `FAIL` / `NOT_RUN` / `BLOCKED` in `results.md`** — still outstanding?
- **Deliberate omissions** — something the plan chose not to handle. Naming it is a divergence
  only if the spec claimed it was handled.

Distinguish clearly between *the spec is wrong* and *the code is wrong*. They need opposite
resolutions and the reader cannot guess which they are facing.

## Forbidden

- Editing **anything**. Not `spec.md`, not `model.md`, not `design.md`, not `tasks.md`, not
  `results.md`'s existing content, not a single line of application code. You are a report, and the
  report's credibility depends on having changed nothing.
- Rewriting or removing a `FAIL` recorded by `verify`. A failure that inconvenient for someone is
  still a failure.
- Declaring consistency without opening the code. A document that says the implementation matches
  is worth nothing; a claim about what the code does, with a `file:line`, is worth reading.
- Inventing divergences to look thorough. An empty `## Divergences` with an explicit list of what
  you checked is a valid, honest result.
- Proposing a fix and applying it. Record the divergence; leave the resolution to the user.

## Done When

- `results.md` has a `## Divergences` section, either with findings or with an explicit statement
  of what was checked and found consistent.
- Every `AC-NN`, `INV-NN` and significant transition has been compared against the code.
- Nothing outside the appended section has been modified — confirm with `git status` before
  reporting done.