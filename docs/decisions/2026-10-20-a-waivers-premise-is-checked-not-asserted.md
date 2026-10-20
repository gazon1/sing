---
status: accepted
date: 2026-10-20
---

# A waiver's premise is checked, not asserted

## Context

One gate in this repository is red on a clean tree: `detekt-rule-coverage-floor`.
Its positive control raises the branch-coverage floor in
`detekt-rules/build.gradle.kts` and requires the Gradle task to fail — but
sabotage proves nothing when the gate is already failing, so the control is
**waived from the clean-run guard** (`needs_clean_run=False`).

A waiver is not free. It is paid for with a promise about where the
compensating pass happens, and the promise for this one was written down in a
comment:

> both surfaces execute the very same task on the very same tree a step later
> — check.sh step 8b (koverLog + koverVerify) and ci.yml's "Kover verify
> (detekt-rules)".

Nothing checked it. So the failure mode is silent and one-sided: rename the CI
step, or delete the `:detekt-rules:koverVerify` line from it, and the waiver
keeps waiving — while the compensating pass it was granted *for* no longer
exists. The gate stays red, the waiver stays, and nobody notices, because a
permanently-red gate is exactly the thing a waiver exists to stop being.

The same is true of the reference itself. `tracked_by` is a pointer — to a
file, and to an anchor inside it. A pointer that no longer resolves is a
pointer to nothing, and it is the only thing the waiver has instead of a date.

## Idea

Check the premise instead of asserting it. Do not pay for the clean-run pass.

## Decision

A new gate, `scripts/check-waiver-references.py`, holds the premise to
account.

- **Every reference resolves.** A `tracked_by` of the form `path#anchor` names
  a file that exists and an anchor that is in it. For a workflow, the anchor
  must name a **step**, not merely occur in the file: `Kover verify
  (detekt-rules)` appears three times in `ci.yml` — twice in comments
  explaining why the step is where it is, and once as the step itself. A
  substring check would accept the comments and pass a waiver whose
  compensating pass had been deleted.
- **The compensating pass is still there.** For a control that proves a Gradle
  task, the task must run in the anchored step's `run:` block **and** in
  `check.sh` — the two surfaces the waiver's reason names.
- **An expiry that has passed is reported.** A waiver with a date is a promise
  with an end.
- **The two copies agree.** The registry declares the waiver;
  `scripts/check-gate-wiring.py` carries the hand-written twin that runs. If one
  copy waives the clean-run guard and the other does not, the guard is applied
  inconsistently and neither copy notices.

The clean-run Gradle pass itself is **not** added. It would cost a full
configuration cycle on every run of the wiring gate, on both surfaces, to
learn something the two surfaces already establish a step later.

## Rationale

The obvious alternative — add the clean-run pass — was rejected on cost, and
the cost argument was already written down where the waiver was granted. A
configuration cycle per run of a gate that runs on every commit is a real
price, and it buys a fact the two surfaces already publish.

The other alternative — "just re-read the comment" — is what the repository
was doing, and the failure mode above is why that is not enough. A promise
that only a human checks rots on the schedule of human attention.

Checking the premise is cheaper *and* stronger: it fails the moment the CI
step is renamed, whereas the clean-run pass would still be green (the task
still fails when sabotaged) while the premise it was granted for had quietly
disappeared.

## Consequences

- A waiver cannot outlive its premise. The gate that holds it has its own
  positive control: renaming the anchored CI step makes it fail.
- Every control carrying a `tracked_by` is checked, not only the waived one —
  a reference that no longer resolves is dead weight everywhere it appears.
- The premise check is a *static* check: it reads the workflow and the local
  script. It does not run the task, and it does not claim to.
- The waiver stays, and the pass it avoided stays avoided. What changed is that
  the waiver now says something that can be false in a way the gates can see.

## Links

- `openspec/changes/the-gates-run-on-every-machine/`
- `scripts/check-waiver-references.py`
- `scripts/src/sgate/commands/_registry.py` — `GATE_CONTROLS`
- `docs/decisions/2026-10-04-measurement-integrity.md`
