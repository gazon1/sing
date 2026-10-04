# Tasks — ci-gate-coverage

- [ ] Wire `check-adr-references.py`, `check_adr_status.py`,
      `check_skill_frontmatter.py` and `check-detekt-registrations.sh` into
      `.github/workflows/ci.yml` as blocking steps. Measure each one's exit code
      on a clean `main` first: a gate wired in and already red is a different
      task from a gate wired in and green, and the first one needs its findings
      fixed in the same commit.
- [ ] Do not reach the fourth script through `check.sh`. `check.sh` is executed by
      no workflow (`grep -rn "check\.sh" .github/workflows/` returns six comment
      hits and zero invocations), so an invocation added there satisfies nothing.
- [ ] Write the coverage check that fails when a gate script has no invocation in
      any workflow, and give it a discovered-set counter that refuses to pass on
      zero — the same shape as `scripts/check-test-task-inputs.py`. Without the
      counter the check passes vacuously when discovery breaks.
- [ ] Test the coverage check in both directions before trusting it: remove an
      invocation and watch it fail, and break discovery and watch it fail. A
      coverage gate that has only been seen passing has not been tested.
- [ ] Delete the 1500-line `echo` in `docs-audit.yml:69`, or make it derive its
      threshold from the constant in `scripts/check-doc-sizes.py`. Do not raise
      1250 to match it — the disagreement is the defect, and reconciling upward
      removes the gate rather than fixing it.
- [ ] Grep the rest of the repository for the same shape: a limit written as a
      literal in a workflow, a justfile or a doc, where a named constant already
      exists. Report what is found; do not assume this is the only one.
- [ ] Update `docs/doc-maintenance.md` and the
      `singularity-todo-quality-tools` skill to say where each gate is invoked
      from, so the next gate is wired by reading the list rather than by
      rediscovering that `check.sh` is not CI.
