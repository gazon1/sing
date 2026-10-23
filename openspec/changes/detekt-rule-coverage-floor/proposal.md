# detekt-rule-coverage-floor

Issue: #98 · Backlog entry: `detekt-rule-branch-coverage-owed`

## What

Turn the measured detekt-rule coverage from a report into a floor, and close the
branch gaps that the report has been naming since it was written.

No production behaviour changes. This is verification posture only.

## Why

`:detekt-rules` has carried the Kover plugin and a `just kover-rules` recipe
since this sweep began. `just kover-rules` prints a per-rule line-coverage
report; nothing compares it to anything. `koverVerify` was deliberately left out
of `check` — a verification task with no threshold only produces noise.

That reasoning is correct right up to the point where the report becomes the
delivery mechanism. Line coverage at 92% is a number that appears in a terminal
when someone remembers to run the recipe, and the rules underneath it are the
rules that enforce every other rule in this repository. A rule with no test is a
rule that can stop matching without anything noticing.

There is a second problem, and it is the more interesting one. Every custom
detekt rule in this repository now has at least one positive test — added in
this sweep precisely so no rule can pass by never firing. Positive tests prove
the rule fires; they do not prove its branches are reachable. Several rules
guard multiple shapes, and a guard that is never exercised is indistinguishable
from a guard that is not needed. That distinction is what the coverage report
exposes and what a floor makes permanent.

## How

Set the floor below the current measurement, not at it, and record the gap
before setting it — so the floor is a ratchet and the uncovered rules are a
named list rather than a percentage nobody can act on. Then work the list.

`koverVerify` belongs in `:detekt-rules:check` once a threshold exists. It does
not belong in the root `check` yet: the root kover aggregation covers the
product modules and its floors are a separate concern with their own gate.

The floor is declared once — in `settings.gradle.kts`, since 2026-10-23, with the
module binding it — and everything
else reads it from there. Two things used to restate it: `scripts/ci/kover-verify-gate.sh`
(a shell gate that generated the XML and compared it in Python) and
`scripts/check-detekt-rule-coverage.py` (a Python parser of that XML, wired as a
Part-F fixture and as the CI summary renderer). Both are deleted, for two
different reasons that point the same way. The shell gate duplicated a task Kover
already runs natively, so the number existed in a Kotlin constant and in a shell
script and could drift between them. The Python parser read a report that only
the gate step produced, while the summary step that called it ran *before* that
step — so the job summary had been printing its own "report not found" failure
for as long as the step existed, which is the shape of a check that reports a
verdict nobody derived. What remains: `:detekt-rules:koverVerify` enforces
(ci.yml's `tests` job and `./check.sh` step 9b both name it),
`:detekt-rules:koverLog` renders the number (ci.yml's advisory summary, printed
by check.sh too), and Part C of `check-gate-wiring.py` holds the declaration
itself to a floor it can resolve to at least 60 — one number, three readers.

The half a static read cannot answer — that a real run below the bound fails
the task — is the SABOTAGE control `detekt-rule-coverage-floor` in that same
file, which raises the constant and requires Gradle to refuse. The reasoning,
including why the shell gate and the Python renderer had to go rather than be
kept in step, is recorded in
`docs/decisions/2026-10-15-kover-floor-lives-in-the-build-file.md`.
