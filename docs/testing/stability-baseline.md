# E2E Stability Baseline

Establishes a stability baseline for the Maestro E2E smoke suite by running three consecutive
smoke runs and recording per-flow success rates and persistent failures.

## Procedure

1. Trigger three consecutive smoke runs on the same SHA:
   ```bash
   # Run 1
   gh workflow run e2e.yml -f suite=smoke --ref <sha>
   # Wait for completion, note run ID

   # Run 2
   gh workflow run e2e.yml -f suite=smoke --ref <sha>
   # Wait for completion, note run ID

   # Run 3
   gh workflow run e2e.yml -f suite=smoke --ref <sha>
   # Wait for completion, note run ID
   ```

2. Collect JUnit results from each run:
   ```bash
   # Download artifacts from each run
   gh run download <run1-id> -n "maestro-junit-smoke-1-<run-id>"
   gh run download <run2-id> -n "maestro-junit-smoke-1-<run-id>"
   gh run download <run3-id> -n "maestro-junit-smoke-1-<run-id>"
   ```

3. Parse results to build the table below.

## Results Template

| Flow | Run 1 | Run 2 | Run 3 | Total runs | Success rate | Persistent? |
|------|--------|--------|--------|------------|--------------|------------|
| auth/signin/SignInFlow | PASS/FAIL | PASS/FAIL | PASS/FAIL | 0/3 | 0% | Yes/No |
| auth/signup/SignUpFlow | PASS/FAIL | PASS/FAIL | PASS/FAIL | 0/3 | 0% | Yes/No |
| agenda/AgendaFlow | PASS/FAIL | PASS/FAIL | PASS/FAIL | 0/3 | 0% | Yes/No |
| tasks/TaskFlow | PASS/FAIL | PASS/FAIL | PASS/FAIL | 0/3 | 0% | Yes/No |
| ... | ... | ... | ... | ... | ... | ... |

**Duration column:** Average wall-clock time across the three runs (HH:MM:SS).

**Per-flow success rate:** `passes / 3 * 100%`

**Persistent failure:** A flow that fails in all 3 runs is a persistent failure (likely a real bug
or environment issue). A flow that fails in 1-2 runs is flaky (likely a timing or state issue).

## Interpretation

- **All 3 passes:** Flow is stable.
- **2-3 passes:** Flow is mostly stable; investigate failures for timing/state issues.
- **1-2 passes:** Flow is flaky; investigate root cause.
- **0 passes:** Persistent failure; investigate immediately.

## Links

- `#724` — Baseline stability issue
- `e2e.yml` — Maestro E2E workflow
- `Maestro/flows/smoke/` — Smoke suite flows
