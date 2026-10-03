---
name: singularity-todo-openspec-workflow
description: OpenSpec spec-driven workflow for this project. Covers the decision tree (behavior changes → change; architecture → +ADR), the skip_specs table, mandatory read order (code → openspec list --specs → CONTEXT.md → DIGEST.md → ADRs → relevant skills), artifact lifecycle (proposal → specs → design → tasks → apply → verify → archive), and the mandatory verify gate. Run `openspec validate --all --json --strict` after apply and before archive. Use when starting any non-trivial change to observable behavior.
---

# OpenSpec Workflow

OpenSpec is spec-driven development: explore → propose → specs → design → tasks → apply → verify → archive.

Full reference: `openspec/config.yaml` (project context, artifact rules, operation guidance).

## Decision tree

```
Is the behavior changing?
  NO  → Ordinary PR. No OpenSpec artifact needed.
  YES → Is it architecture (new port, DI pattern, data model, cross-cutting)?
    YES  → Write ADR first, then OpenSpec change
    NO   → Create OpenSpec change directly
```

## skip_specs table

When behavior does NOT change, OpenSpec is not needed:

| Situation | Why skip | What to do instead |
|---|---|---|
| Bug fix with no behavior change | Fix is the spec | Ordinary PR |
| Test-only changes | Tests are not behavior | Ordinary PR |
| Dependency/version update | No behavior change | Ordinary PR |
| Detekt/lint auto-fix | Mechanical rewrite | Ordinary PR |
| Doc-only update | No code change | Ordinary PR |
| Rename with no contract change | Renames are not behavior | Ordinary PR |
| Adding a use case that follows an existing pattern | Already described | Check existing specs first |

**Rule:** always `openspec list --specs` before writing a new proposal — if a spec already describes this behavior, write a change to modify it, not a new proposal.

## Mandatory read order

Before writing any OpenSpec artifact, read in this order:

1. **The real code** — not grep, not a skill. Read the actual source files that implement the behavior. Grep misses wiring, initialization order, and implicit dependencies.
2. **`openspec list --specs`** — what specs already exist for this area?
3. **`docs/CONTEXT.md`** — project-level constraints and conventions.
4. **`docs/decisions/DIGEST.md`** — architecture rules that already apply.
5. **Relevant ADRs** — the `Context/Decision/Rationale/Consequences` for this area.
6. **Relevant skills** — `.agents/skills/singularity-todo-*/SKILL.md`.

Agents that skipped step 1 produced plans that were wrong. The plans said "grep shows X" but grep doesn't show wiring.

## Artifact lifecycle

```
proposal  →  specs  →  design  →  tasks  →  apply  →  verify  →  archive
   │            │           │          │          │           │          │
   │            │           │          │          │           │          └── Final spec
   │            │           │          │          │           └── Mandatory gate
   │            │           │          │          └── Implement, task by task
   │            │           │          └── One task per verifiable change
   │            │           └── Only if cross-cutting / new architecture / security / rollback risk
   │            └── Observable behavior only; zero Kotlin names; SHALL/MUST requirements
   └── One coherent change; what is and is NOT in scope
```

**Design document is optional.** Required only for: cross-cutting changes, new architecture, new data models, security-sensitive changes, or rollback-risk changes. Omit for additive, low-risk changes.

**Design document is intentionally omitted for baseline-write-pipeline** — it is a proof-of-concept that OpenSpec can describe existing behavior, not a change to it.

## The verify gate

After applying all tasks, **before archiving**, run:

```bash
openspec validate --all --json --strict
```

If validation fails, fix the implementation — not the spec. A spec that describes behavior the code does not implement means the spec is wrong, not that the test is wrong.

Exit 0 with no new findings → safe to archive.

## CLI reference

```bash
openspec list --changes          # list active changes
openspec list --specs          # list existing specs
openspec show <name>            # view a change or spec
openspec change new <name>    # create a new change
openspec validate --all         # validate all (advisory)
openspec validate --all --strict  # strict: fails on gaps
openspec archive <name>        # archive a completed change
```

**`openspec/config.yaml`** contains: project context (KMP conventions, DI rules, testing patterns), artifact rules (proposal/specs/design/tasks constraints), and operation guidance (apply and archive rules).

## Verify after apply

```bash
openspec validate --all --json --strict
```

JSON output allows scripted consumption. Exit 0 = all specs satisfied.

## Common mistakes

1. **Writing a proposal before reading code.** The proposal describes the change from current behavior. If you don't know current behavior, the proposal will be wrong.
2. **Duplicating ADR rationale in specs.** The spec describes what was built; the ADR explains why. Reference the ADR, don't copy it.
3. **Naming Kotlin types in specs.** Specs describe observable behavior. If you find yourself wanting to name a class or function, the spec is too detailed.
4. **Skipping the verify gate.** `openspec validate` is not a suggestion — it is the mechanism that proves the spec matches reality.
5. **Using grep instead of reading the code.** Grep shows where a symbol is used; it doesn't show initialization order, DI wiring, or implicit dependencies. Read the code.

## Related

- `docs/decisions/2026-10-03-openspec-adoption.md` — the ADR that owns this workflow, with the full ownership matrix and consequences.
- `openspec/config.yaml` — machine-readable project constraints.
- `AGENTS.md` — `## OpenSpec` section for the agent's session-start context.
- `wayfinder` — routes agents to this skill when behavior is being changed.
