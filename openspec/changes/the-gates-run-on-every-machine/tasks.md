# Tasks — the-gates-run-on-every-machine

**status:** accepted

---

## Phase 1 — The tools

- [x] `scripts/ci/setup-local-tools.sh`: one uv venv, the local package
      installed editable, `junitparser`, and the pinned `lychee-bin`,
      `yamllint`, `shellcheck-py` and `actionlint`
- [x] The script refuses to run without uv, and says how to install it
- [x] `scripts/ci/preflight-gate-tools.sh`: every tool the gate suite shells
      out to, reported by name, with the fix command, exiting non-zero
- [x] `check.sh` runs the preflight before the gate suite
- [x] The skill documents the bootstrap, the venv, and why the package is
      installed rather than put on `PYTHONPATH`

## Phase 2 — The waiver

- [x] `scripts/check-waiver-references.py`: every reference resolves, and a
      workflow anchor must name a step
- [x] The compensating pass is still there: the task runs in the anchored
      step and in `check.sh`
- [x] An expiry date that has passed is reported
- [x] The registry and the wiring twin agree about which controls are waived
- [x] Registered in the static gate list as a blocking gate
- [x] The control: renaming the anchored CI step makes the gate fail
- [x] The unit tests: the resolver against synthetic trees, the premise
      against a synthetic tree, and the gate against this repository

## Phase 3 — What this change deliberately does not do

- [x] The clean-run Gradle pass is **not** added — the premise is checked
      instead, and the pass costs a configuration cycle on every run of the
      wiring gate on both surfaces
