# the-gates-run-on-every-machine

Issue: — · Related: `docs/decisions/2026-10-20-a-waivers-premise-is-checked-not-asserted.md`

Status: **accepted**
Tags: **gates tooling uv waiver**

## What

Make the gate suite runnable on a machine that has never run it, and make a
waiver's premise a fact rather than a sentence.

Two failures, one shape: **a gate that cannot run looks exactly like a gate
that has nothing to report.**

1. **The tooling.** The gate suite shells out to six things: the local `sgate`
   package, `junitparser`, `lychee`, `yamllint`, `shellcheck` and
   `actionlint`. A missing `lychee` failed the "documentation links resolve"
   gate with a traceback. A missing `sgate` install made the gate scripts'
   own unit tests fail with `ModuleNotFoundError` — a failure that reads like
   a broken test rather than a missing install. Four different local install
   recipes had failed before this one, each on its own terms.

   Now there is **one** recipe, `scripts/ci/setup-local-tools.sh`, which uses
   **uv** — the same installer CI uses — and puts everything in one venv:

   - the local `scripts/src` package, installed **editable**;
   - `junitparser`;
   - the pinned `lychee-bin==0.24.2`, `yamllint==1.38.0`,
     `shellcheck-py==0.11.0.1`;
   - the pinned `actionlint` v1.7.7 release binary.

   The package must be *installed*, not merely on `PYTHONPATH`: the gate
   scripts' own unit tests run the scripts with a minimal environment
   (`env={'PATH': ...}`), so a package reachable only through `PYTHONPATH` is
   invisible to them.

   And a missing tool is now **named**, not survived:
   `scripts/ci/preflight-gate-tools.sh` runs before the gate suite, reports
   what is missing, prints the fix command, and exits non-zero. A gate that
   cannot run is a loud failure, never a skip.

2. **The waiver.** One gate is red on a clean tree and is therefore waived
   from the clean-run guard: `detekt-rule-coverage-floor`. Its waiver says
   the task (`:detekt-rules:koverVerify`) runs on **both** surfaces a step
   later — `check.sh` step 8b and CI's `Kover verify (detekt-rules)` step.
   That sentence was a comment. Nothing checked it. Rename the CI step, or
   drop the task from it, and the waiver keeps waiving while the
   compensating pass it was granted for is gone — which is exactly how a
   permanently-red gate becomes noise nobody reads.

   Now the premise is a gate. `scripts/check-waiver-references.py` checks that

   - every `tracked_by` reference resolves — and for a workflow, the anchor
     must name a **step**, not merely occur in the file. `Kover verify
     (detekt-rules)` occurs three times in `ci.yml`: twice in comments
     explaining why the step is where it is, and once as the step itself. A
     substring check would accept the comments and pass a waiver whose
     compensating pass had been deleted.
   - the compensating pass is still there: the task runs in the anchored
     step's `run:` block **and** in `check.sh`.
   - an expiry date that has passed is reported.
   - the registry and the wiring twin agree about which controls are waived.

   The clean-run Gradle pass itself is **not** added. It costs a full
   configuration cycle on every run of the wiring gate, on both surfaces, to
   learn something the two surfaces already establish a step later. The
   premise of the waiver is sound; what was missing was the check that the
   premise still holds.

## What not to do

- **Do not make a missing tool a skip.** A gate that cannot run and a gate
  with nothing to report must not look the same. The preflight fails loudly
  and names the fix.
- **Do not install into the system interpreter.** Four recipes failed that
  way. A venv on `PATH` is the arrangement CI uses, so a local run and a CI
  run of the same gate agree.
- **Do not put the local package on `PYTHONPATH` instead of installing it.**
  The unit tests run with a minimal environment and would not see it.
- **Do not add the clean-run Gradle pass.** The premise is checked; the pass
  is not needed.
- **Do not accept a substring as an anchor.** A comment that repeats a step
  name is not the step.

## Consequences

- A new machine runs one command, puts one venv on `PATH`, and the gate suite
  works.
- A missing tool is reported before the suite starts, with the command that
  fixes it.
- A waiver cannot quietly outlive its premise.
- The gate that checks the waivers has its own positive control: renaming the
  anchored CI step makes it fail.
