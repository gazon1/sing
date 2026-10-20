---
title: "The shard plan is one file"
date: 2026-10-20
status: accepted
tags: [ci, maestro, tooling]
---

## Context

The nightly E2E workflow splits the Maestro suite into shards: the `full`
suite into four, the `smoke` suite into one. The split is arithmetic — the
`plan` job emits a matrix of shard indices, and each shard job runs
`run-maestro-e2e.py --shard N --shards 4`.

The runner has had `--shard` and `--shards` for a while. They are still not
enough to reproduce a red shard locally, because of the one number nobody
local has: the shard count. It lived in the workflow's `plan` job as two
literals:

```bash
shards='[1]'
[ "$suite" = full ] && shards='[1,2,3,4]'
```

A local run has to guess it, and a wrong guess does not fail — it runs
*different flows*. The shard is `index % shards` over the sorted flow list, so
shard 3 of 3 and shard 3 of 4 are different sets of flows. A reproduction
that guessed wrong comes back green and says nothing about the shard that
failed.

The same guessing applies to *which flows a suite runs*: the smoke suite
selects a subset of the tree and the full suite selects all of it, and that
selection is the runner's business (`run-maestro-e2e.py` and the module
behind it). The plan file therefore holds only the count, and asks the
runner for the rest — see the consequences for what that cost.

## Idea

- **A.** Document the number in the runbook and leave it where it is.
  Documentation drifts; the number changes when a flow is added, and a
  stale document is a wrong guess with a citation.
- **B.** Move the plan into one file that the nightly's plan job and a local
  script both read.
- **C.** Make the runner discover the shard count itself, from the flow list.
  A shard count that the runner derives cannot be wrong — but it also cannot
  be chosen. Four shards is a decision about how long the nightly may take.

## Decision

**B**, with the file `config/docs/e2e-shards.txt`:

```
full 4
smoke 1
```

- The nightly's `plan` job reads the file to build the matrix, so the number
  that decides the nightly is the number the reproduction uses.
- `scripts/e2e-shard-plan.py` reads the same file and answers the question
  the local runner has: `--suite full --shard 3` prints the flows that shard
  holds and the commands that reproduce it (build the debug APK, then run
  `run-maestro-e2e.py` with that shard and that count).
- **The script asks the runner for its flow list and its suite selection**
  (`collect_flows`, `suite_selection`) instead of re-implementing them, and
  applies the runner's shard rule. Only the count is written down twice —
  in the plan file and in the workflow that reads it — so there is one place
  where a value can still disagree, and a test checks that the workflow
  really reads the file.

The script uses the standard library only. The nightly's `plan` job runs
before any Python tooling is installed — it decides the matrix, and the
matrix is what installs the tooling — so a script that needed Click would
need the venv the job is about to decide to create.

## Rationale

- **A wrong guess is worse than no command.** The failure mode of "just try
  shard 3" is a green run that proves nothing. A reader who trusts it stops
  investigating, and the real shard stays red for another night.
- **The number is a decision, and decisions have an address.** How many shards
  the nightly uses is a statement about how long it may take and how many
  emulators it may hold. It belongs where it can be argued about, not inside
  a shell line in a workflow.
- **The suite and its flows are one fact, and the runner owns it.** Which
  flows a suite runs is the runner's business — the smoke suite is selected
  by tag, the full suite is every flow — so the plan script asks the runner
  rather than repeating it. A plan that restated the selection would be a
  second place for it to be wrong, and the disagreement would show up as a
  green reproduction of a red shard.
- **C would take away the decision.** Deriving the shard count from the flow
  list makes the nightly's parallelism a function of how many flows happen to
  exist. That is a different policy — "as parallel as the flow count
  allows" — and it is not the policy the nightly has.
- **The script has no opinion about the answer.** It reads one file and
  applies the runner's rule. It cannot become stale in the way a
  hand-written reproduction note can, because the flows it lists come from
  the same directory the runner reads.

## Consequences

- **A local reproduction is one command.** `python3 scripts/e2e-shard-plan.py
  --suite full --shard 3` says which flows that is and how to run them.
- **The nightly's matrix and the reproduction cannot drift apart**, and a test
  fails if the workflow stops reading the plan file.
- **Adding a flow can move a flow between shards.** The shard is
  `index % shards` over a sorted list, so a new flow inserted in the middle
  shifts everything after it. That was already true; what changes is that it
  is now visible — the plan prints the membership, and the nightly's own
  matrix says which shard it meant.
- **The plan file lost a column.** It once carried each suite's flow
  directory as well as its count. The runner moved the smoke suite's
  selection from a directory to a tag, and a plan that kept the directory
  would have been wrong in a way nothing noticed. The count is the only
  thing the plan owns now.
- **The plan file is an input to a workflow.** A malformed line fails the
  `plan` job loudly rather than quietly producing a matrix of one shard.

## Links

- `config/docs/e2e-shards.txt` — the plan
- `scripts/e2e-shard-plan.py` — the reader and the reproduction commands
- `scripts/tests/test_e2e_shard_plan.py` — the agreement with the runner
- `.github/workflows/e2e.yml` — the `plan` job that reads the file
