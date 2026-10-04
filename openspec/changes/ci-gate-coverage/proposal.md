# ci-gate-coverage

Issues: #116, #118 · Related: #95, #113

## What

Close the gap between a gate that exists and a gate that runs, and give every
numeric budget exactly one number.

Two requirements, because they are the same defect at two scales: a check that
nobody invokes, and a limit that has two owners.

## Why

`test-execution-integrity` exists because 16 of 218 test classes could be
unselected and CI stayed green for months. REQ-4 added unit tests for every
script under `scripts/`. Both are correct, and both sit upstream of the thing
that is actually missing.

Four gate scripts can fail and are never executed. Measured at `953b802e`:

| Script | In a workflow | In `check.sh` |
|---|---|---|
| `check-adr-references.py` | 0 | 0 |
| `check_adr_status.py` | 0 | 0 |
| `check_skill_frontmatter.py` | 0 | 0 |
| `check-detekt-registrations.sh` | 0 | 1 |

`check.sh` is itself run by no workflow — `grep -rn "check\.sh" .github/workflows/`
returns six hits, all inside `#` comments describing what CI does instead. So the
last row is a zero as well, reached through a file no job opens.

The fourth one matters most. `check-detekt-registrations.sh` is the only check
that every custom detekt rule is actually registered in `META-INF/services` and
active in `config/detekt/detekt.yml`. Two no-op rules shipped in PR #31 before it
was wired; the registration check is what would have caught them, and it is the
check that does not run.

REQ-4's tests prove each gate detects its own failure mode. They cannot prove
anything about the code, because nothing calls the gate on the code.

## The second requirement, and why it is not a variant of the first

`DIGEST.md` is measured against two limits:

```
scripts/check-doc-sizes.py:29         DIGEST_MAX = 1250  # matches MAX_DIGEST_LINES in refresh-decisions-digest.py
.github/workflows/docs-audit.yml:69   echo "Warning: DIGEST.md exceeds 1500 lines"
```

1250 exits non-zero and runs blocking. 1500 is an `echo` that cannot fail at any
size. The file measures 1196 lines today, so 250 lines can accumulate with every
surface green and the only mention being a warning in the one workflow that is
advisory by construction (#95).

This is worth a requirement of its own rather than a line in REQ-9 because the fix
differs. REQ-9 is about invocation. REQ-10 is about there being nothing to
disagree with: one number, named as a constant, referenced rather than restated.

## What not to do

- Do not add a "run the gate scripts" step that itself has no test. A coverage
  check for gate invocation, written without a test that removes an invocation
  and watches it fail, is the same defect one level up.
- Do not raise 1250 to 1500 to make the warning stop. The disagreement is the
  defect; reconciling in the direction that loosens the gate resolves it by
  removing the gate.
- Do not treat a gate as covered because `check.sh` runs it locally. `check.sh` is
  the loop people run on their own machine; the requirement is about CI, because
  CI is the surface that reports to branch protection.
