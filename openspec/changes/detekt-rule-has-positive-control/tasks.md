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
- [ ] **Record the gate-side follow-up, and do not fold it into this change.**
      A gate's control is a sabotaged input that must fail; a rule's is a test
      that makes the rule fire. Same property, inverse artefact, different check.
      The input already exists: the audit table in ADR
      `2026-10-05-gate-audit-text-shape-vs-fact`, thirteen gates, with the silent
      input found for each and whether a synthetic control exists. What is
      missing is a gate that asks which registered gates have never been
      sabotaged — the derivation-from-registration requirement this list states
      for rules, applied to `check-gate-wiring.py`'s own registry.
