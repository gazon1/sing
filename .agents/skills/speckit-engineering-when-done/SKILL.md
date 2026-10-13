---
name: speckit.engineering.when-done
description: "Engineering SDD WHEN DONE phase: post-implementation audit, fix criticals, record debt, update/create skills. Use after a completed engineering-sdd pass."
disable-model-invocation: true
---

## Purpose

WHEN DONE is the retrospective phase of the engineering SDD workflow. It runs after
`converge` and `assert-artifacts` and before the REPEAT gate.

Three activities, in order:

1. **Audit** — what problems, debt, or next logical steps did the completed pass surface?
2. **Fix criticals** — fix blocking bugs and critical debt immediately; record the rest.
3. **Update/create skills** — based on what was learned, update existing skills or create
   new ones that will make future passes faster and more robust.

## Inputs

| Field | Description |
|---|---|
| `slug` | kebab-case change id |
| `request` | The original request (for context) |

## Process

### 1 — Audit the completed pass

Read `openspec/changes/<slug>/results.md` and the change directory artifacts.
Identify:

- **Blocking bugs** found during implementation or verification
- **Architectural debt** observed but not addressed in this pass
- **Next logical improvement steps** that follow from this work
- **Patterns repeated across the codebase** that could be solved in one shot

Categorize each finding:

| Category | Criteria | Action |
|---|---|---|
| `critical` | blocks the current change or causes data loss | Fix immediately |
| `debt` | tech debt, readability, testability | Record in ADR |
| `skill-gap` | no skill covered this well | Update or create a skill |
| `next-step` | logical next REPEAT candidate | Surface for REPEAT gate |

### 2 — Fix criticals

Fix only `critical` findings in this step. Record non-critical findings for later.

For each fix:
- Create or update the relevant artifact in `openspec/changes/<slug>/`
- If the fix requires a new skill or skill update, note it for step 3

If a critical cannot be fixed (blocked on external factor, requires larger scope),
record it as a `blocked-finding` in `openspec/changes/<slug>/when-done.md` with the
blocking reason.

### 3 — Update/create skills

For each `skill-gap` finding from the audit:

- **Update existing skill** — if a skill exists but was not sufficient, improve it
  using the evidence from this pass. Follow the skill's existing conventions.
- **Create new skill** — if no existing skill covers the gap, author a new skill
  following the five-heading contract (`name`, `description`, `disable-model-invocation`,
  `Purpose`, `Process`, `Inputs`, `Outputs`, `Notes`).

Skills should be placed in `.agents/skills/speckit-engineering-*/` for
engineering-specific skills, or `.agents/skills/` for cross-cutting skills.

After authoring or updating a skill, run `singularity-adr validate` if an ADR was
created.

## Outputs

| Output | Location | When |
|---|---|---|
| `when-done.md` | `openspec/changes/<slug>/when-done.md` | Always |
| Updated skills | `.agents/skills/` | When a skill-gap was found |
| ADRs for debt | `docs/decisions/` | When non-critical debt was recorded |

## Audit checklist

```
## WHEN DONE Audit — {{ slug }}

### Critical bugs found (fixed)
-

### Architectural debt recorded
-

### Skill gaps found
-

### Next logical REPEAT candidates
-
```

## Notes

- **Fix criticals only.** Non-critical debt goes to ADR, not into this step.
- **Record, don't silently drop.** Every finding from the audit must appear in
  `when-done.md` or the change's `results.md`. Nothing gets lost between passes.
- **Skills are first-class artifacts.** If the team worked around a missing skill
  in this pass, the skill is the deliverable, not just the code fix.
- This skill intentionally does NOT loop or recurse. It produces one `when-done.md`
  per pass. The REPEAT gate decides whether another pass runs.
