---
name: speckit.engineering.planning
description: "Engineering SDD PLANNING phase: grill the open request to improve it, then synthesize a structured plan artifact. Use at the start of a new engineering-sdd pass."
disable-model-invocation: true
---

## Purpose

Two-step PLANNING phase for the engineering SDD workflow:

1. **Grill** the open request using the `grilling` skill — stress-test the plan, find
   weaknesses, improve it before any spec is written.
2. **Synthesize** the grilled plan into a structured `plan-artifact.md` using the
   `to-spec` skill — this becomes the bounded input for `engineering-sdd`.

Both steps run in the same agent call; the grilling output feeds directly into
`speckit.to-spec` as the request context.

## Process

### Step 1 — Grill the request

Load the `grilling` skill and run it on the open request. The request may come from:

- The workflow `inputs.request` (first pass: greenfield request)
- The `plan-artifact.md` produced by a prior REPEAT pass (subsequent passes:
  improvements from the previous WHEN DONE audit)

If the request is a continuation from a prior pass, read
`openspec/changes/{{ slug }}/plan-artifact.md` first and feed it as context to the
grilling skill.

The grilling skill interviews the user in rounds. Complete at least one full round
before proceeding.

### Step 2 — Synthesize into plan-artifact.md

After grilling, call `speckit.to-spec` with the grilled, improved request.
`speckit.to-spec` synthesizes without further interview.

Create (or overwrite) `openspec/changes/{{ slug }}/plan-artifact.md` with the
resulting spec. This artifact bounds the scope for the `engineering-sdd` pass
that follows.

## Inputs

| Field | Description |
|---|---|
| `slug` | kebab-case change id, e.g. `fix-sync-outbox-cancellation` |
| `request` | The user's request or the grilled/improved plan from a prior pass |
| `is_repeat` | `true` when this is a REPEAT pass; signals that a prior plan-artifact.md exists |

## Outputs

- `openspec/changes/<slug>/plan-artifact.md` — bounded scope for `engineering-sdd`

## Skill contracts

| Skill | What it does | Skill command |
|---|---|---|
| `grilling` | Stress-test the plan in rounds | `grilling` |
| `to-spec` | Synthesize into spec artifact, no interview | `to-spec` |

## Notes

- The `grilling` skill runs interactively (it interviews the user). The agent must
  complete at least one round before calling `to-spec`.
- If `is_repeat=true`, read the existing `plan-artifact.md` and feed it as context
  to `grilling` before starting the round. Frame the grilling as "given what we
  did last time, what should we do next?"
- `speckit.to-spec` deliberately does NOT interview. Pass the full grilled request
  as the synthesis context.
