---
name: singularity-todo-workflow-evals
description: Run workflow evals to measure agent quality — run tasks, compare against baseline, report results.
---

# Workflow Evals

## When to use

- Before and after significant workflow changes (new skills, new ADRs, new patterns)
- To measure whether agents follow canonical patterns
- To identify regression in agent quality

## Prerequisites

- Python 3 with `pyyaml` installed
- Git repo with clean working tree
- All tasks defined in `evals/tasks/`

## Step-by-step

### Step 1 — Run all evals

```bash
python3 scripts/run-evals.py --task all --json > /tmp/evals-results.json
```

### Step 2 — Review results

```bash
# Human-readable
python3 scripts/run-evals.py --task all

# JSON for programmatic analysis
python3 scripts/run-evals.py --task all --json
```

### Step 3 — Compare with baseline

```bash
# Compare current results against baseline
python3 scripts/run-evals.py --task all --json | \
  python3 -c "import json,sys; cur=json.load(sys.stdin); bas=json.load(open('evals/baseline.json')); print('Baseline:', bas['summary']); print('Current:', {t['task']: t['grade']['grade'] for t in cur})"
```

### Step 4 — Investigate failures

For each failed task:
1. Read the task definition in `evals/tasks/<name>.yaml`
2. Run the agent with the task
3. Compare the output against the expected outcome
4. File an issue or ADR if the failure reveals a missing skill or pattern

## Task files

Tasks live in `evals/tasks/*.yaml`. Each task has:

```yaml
name: task-slug           # unique identifier
description: One line describing what the task tests

task: |                   # the prompt given to the agent
  Instructions for the agent...

constraints:              # what the agent must not break
  - Constraint 1
  - Constraint 2

expected:                 # what a correct answer looks like
  - Expected outcome 1
  - Expected outcome 2
```

## Adding a new task

1. Create `evals/tasks/<name>.yaml` with the format above
2. Run `python3 scripts/run-evals.py --task <name>` to verify it loads
3. Add to `evals/baseline.json` with `grade: pass`
4. Commit

## Grading criteria

| Criterion | What it checks |
|---|---|
| `task_defined` | Task has a non-empty `task:` field |
| `constraints_defined` | Task has a `constraints:` list with at least one item |
| `expected_defined` | Task has an `expected:` list with at least one item |

## Current tasks

| Task | What it tests |
|---|---|
| `scoped-fix` | Fixing a scoped state update bug in a ViewModel |
| `protected-migration` | Migrating a ViewModel to MviViewModel pattern |
| `crud-feature` | Scaffolding a new CRUD feature with canonical pattern |
| `adr-decision` | Writing an ADR with full Context/Options/Decision/Consequences |
| `skill-update` | Updating an existing skill with new content |

## Common pitfalls

1. **Task definition is too vague** — if the expected outcome can be interpreted multiple ways, the task needs to be more specific
2. **Constraints are missing** — without constraints, the agent can "pass" by doing something that breaks existing code
3. **Baseline not updated** — when a task is improved, update `evals/baseline.json` with the new expected result
