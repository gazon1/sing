# Tasks — the gates are this checkout

**status:** accepted

---

## Phase 1 — The checkout the gates measure

- [x] `scripts/check-gate-registries.py`: this checkout's `scripts/src` is on the
      path *before* the gate tooling is imported — the order was the bug
- [x] `scripts/ci/static-gates.sh`: every python gate runs with this checkout's
      `scripts/src` on the path
- [x] `scripts/ci/preflight-gate-tools.sh`: the check is "is the tooling *this*
      checkout's?", and a mismatch names the path it resolved to
- [x] A checkout-local environment is supported (`SINGULARITY_TOOLS_VENV`) and
      the preflight says so when the tooling is missing

## Phase 2 — The catalogue

- [x] `shared/build.gradle.kts`: a verification task exports the catalogue to a
      scratch file and compares it with the committed one
- [x] A mismatch fails with the command that regenerates the catalogue
- [x] `check.sh` runs the verification before the long test task
- [x] `scripts/render-adr-tools-section.py`: a description in a form the reader
      cannot parse is reported by the reader, naming the form
- [x] `scripts/check-skill-drift.py`: the drift message names the cause instead
      of blaming the reader

## Phase 3 — The write path

- [x] `scripts/check-ai-write-tools.py`: an unlisted direct writer fails
- [x] A listed tool that no longer writes directly fails (a stale entry)
- [x] A tool that both proposes and writes directly fails
- [x] `config/docs/ai-direct-writes.txt`: every direct writer is listed with the
      reason it writes directly
- [x] The registry is a data file, so the reasons are reviewable in a diff
- [x] A malformed registry is a failure, not an empty registry

## Phase 4 — The gate that can fail

- [x] The gate is registered in the gate registry and in the placement manifest
- [x] The shell registry and the registry document are regenerated from them
- [x] The positive control: removing one entry from the registry makes the gate
      fail
- [x] The wiring check sees the new gate as controlled

## Phase 5 — The run and the rules

- [x] `sgate run-all --summary`: one line per failed gate with the reason, one
      line for the result
- [x] `CODING_STANDARDS.md`: the judgement rules, each paired with the gate that
      enforces it
- [x] `AGENTS.md`: the checkout-safe gradle wrapper, the catalogue's origin, the
      checkout-local gate environment, the display the desktop tests need, and
      the one-background-task rule with unique log names

## Phase 6 — The record and the verification

- [x] `docs/decisions/2026-10-22-the-gates-are-this-checkout.md`
- [x] The decision digest is rebuilt
- [x] The issue registry is imported from the change
- [x] The catalogue verification runs green
- [x] The gate suite passes: every blocking gate and the advisory one
- [x] The static analysis passes
