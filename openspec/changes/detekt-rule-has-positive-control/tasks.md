# Tasks — detekt-rule-has-positive-control

- [ ] `detekt-rules/` — add a check that fails when a rule defined in the
      module's main source set has no positive control anywhere in its test
      source set, and names the rule it is missing.
- [ ] Derive the rule list from the rule providers and rule classes themselves
      rather than from a hand-written list, so a new rule is covered the moment
      it is registered and cannot be forgotten by whoever registered it.
- [ ] Accept a positive control from any suite in the test source set. Do **not**
      require a file named after the rule: the smoke test exists precisely
      because a per-rule file is not the only arrangement, and a check that
      assumes it will report a gap that is not there.
- [ ] `detekt-rules/src/test/` — add a test for the check itself, pinning that a
      rule covered only by a shared suite is accepted.
- [ ] **Prove the check can fail.** Remove one positive control, run the check,
      and record that it exits non-zero and names the rule. Restore it and record
      the green run. Both results belong in the check's own comment — a gate
      documented only with its happy path is the artefact this requirement
      exists to prevent.
- [ ] `check.sh` — run the new check as a numbered step, and register it in
      `scripts/check-gate-wiring.py` so the sabotage check covers it. Every
      registered gate must be shown failing on a sabotaged input.
- [ ] Verify the step lands in the documented position: adding a step renumbers
      the tail, and a gate that reports a stale step number is the same defect
      class as a gate reporting a stale verdict.
- [ ] Record the measured count of rules and positive controls beside the check,
      with the date and the command that produced it. A count that cannot be
      re-derived is a number that will be quoted long after it stops being true.
- [x] **Record the gate-side follow-up, and do not fold it into this change.**
      A gate's control is a sabotaged input that must fail; a rule's is a test
      that makes the rule fire. Same property, inverse artefact, different check.
      The property is now enforced: `check-gate-wiring.py` Part F derives the
      registry of positive controls from the places gates are actually invoked
      (`check.sh`, the workflows, and the `just` recipes), so a gate with no
      control is a finding rather than an absence. Measured 2026-10-05: 19
      registered gates, 17 with a measured control and 2 exempt with a reason
      each. See ADR `2026-10-05-positive-control-registry-is-derived`.
      An earlier version of this task named "the audit table in ADR
      `2026-10-05-gate-audit-text-shape-vs-fact`, thirteen gates" as an existing
      input. **That table was never written** — the ADR records the method and
      the four repairs it produced, and has never contained a single table row.
      The reference was to work that did not exist, and anyone taking this task
      would have spent the first hour looking for it. Part F replaced the need
      for it: the registry is now derived and checked, so the table would have
      been a snapshot of a list the gate maintains on its own.
