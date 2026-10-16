# Feature Specification: Skip-Rule Identity Integrity

**Feature Branch**: `gazon1/noddy`

**Created**: 2026-10-15

**Status**: Draft

**Input**: User description: "Напиши спеку со спецификацией которая предотвратит в будущем похожую ошибку" — a specification that prevents a class of defect discovered on 2026-10-15, when probing the gate registry exhausted ~50 GB of RAM on the dev machine.

## Incident this spec exists to prevent

`scripts/check-gate-liveness.py` ("gates are alive") verifies that every gate in the registry can actually run, by running each one. Expensive gates were exempted by comparing the registry **command text** against a prefix list:

```python
SLOW_PREFIXES = ('./gradlew', 'gradlew', './just', 'just ', 'bash scripts/ci/', ...)
def is_slow(name, cmd):
    return any(cmd.startswith(p) for p in SLOW_PREFIXES)
```

The `sgate` refactor rewrote every registry command from `./gradlew ...` / `python3 scripts/check-x.py` to `sgate <subcommand>`. **No command matched a prefix any more.** The exemption stopped firing, with no error, no warning, and no change in any reported number that a reader watches.

The gate then actually ran `declared dependencies are used`, which resolves to `scripts/check-dependency-usage.py:302` and shells out to `./gradlew :shared:printResolvedArtifacts` — a cold Kotlin daemon per probe. Result: ~50 GB of RAM consumed on a 62 GB machine, and four gates reported `timeout` rather than `skip`.

The same class is live elsewhere in the tree: `check-publication-hygiene.py:206` and `:245` exempt paths by prefix and by `scripts/check-` filename shape; `check-doc-dead-refs.py:760` exempts backticked symbols with `startswith("shared/")`; `check-script-conventions.py:50` detects a delegating wrapper by inspecting the literal text of its single line.

## The defect class

A rule that decides **skip / exempt / already-covered / ignore** for some item is keyed on the **text** of that item — a command string, a path prefix, a filename shape — rather than on the item's **resolved identity**: which registered thing it is, what it actually dispatches to, which script really runs.

The two representations drift apart whenever the spelling changes, and nothing in the system is watching for that. The rule keeps compiling, keeps running, keeps reporting a number. It just stops meaning anything.

Both directions are silent, and they are opposite failures:

- **Under-matching** — the rule stops firing. The thing it was protecting now runs when it must not. This is the 2026-10-15 incident: exemptions evaporated, heavy tools ran, RAM was exhausted, and the gate reported `timeout` — a word that points at the gate rather than at the cause.
- **Over-matching** — the rule fires too widely. It silently exempts things it must not, and the gate reports green while covering less than it appears to. Writing the fix naively reproduces this: matching the bare substring `./gradlew` also matches a docstring (`check-test-task-inputs.py:13`), a `print()` that *suggests* a command (`check-baseline-ratchet.py:237`), and a README fixture (`check-publication-hygiene.py:387`) — three cheap gates would have been reported as heavyweight skips.

This class is uniquely corrosive here because the gates exist precisely to convert silent failure into a red verdict. A defect that disables a skip rule inside a gate disables the mechanism at the point where it is most needed.

### User Scenarios & Testing *(mandatory)*

### User Story 1 - A refactor cannot silently void a skip rule (Priority: P1)

A developer changes how gates are invoked — a new dispatch layer, a renamed tool, a moved script. Every skip and exemption rule in the tree keeps working, or the change fails loudly. It is never possible for a rule to quietly stop applying and leave the suite reporting its usual numbers.

**Why this priority**: This is the incident itself. Everything else is a refinement of it, and without this the suite's other guarantees are void — the failure mode is invisible precisely when the system is most load-bearing.

**Independent Test**: Change the spelling of every registry command in the tree, then run the full gate suite. The set of skipped items MUST be identical to before the change, and each skip MUST be individually re-derivable from a declared source of truth. Fully testable by renaming, with no implementation knowledge required to read the result.

**Acceptance Scenarios**:

1. **Given** a suite where 3 of 49 gates are skipped as heavyweight, **When** the invocation syntax of all 49 commands is changed, **Then** exactly 3 gates remain skipped, and they are the same 3.
2. **Given** a skip rule whose key no longer resolves for any registered item, **When** the suite runs, **Then** it fails with a named diagnostic, not with silence and not with a reduced count that reads as normal.
3. **Given** a skip rule that matches every registered item, **When** the suite runs, **Then** it fails, because a rule that exempts everything is indistinguishable from a rule that is broken.

---

### User Story 2 - Every skip is visible to a reader who did not write it (Priority: P2)

A developer running the suite can see, without reading source, which items were skipped and why. A skip that nobody can see is indistinguishable from a skip that never happened — which is how the 2026-10-15 incident stayed invisible for as long as it did.

**Why this priority**: Detection after the fact depends entirely on someone reading the output. The incident reached a machine's memory ceiling before anyone saw a number that was wrong.

**Independent Test**: Run the suite and count skipped items from its output alone, with no access to the source. The count MUST match the rules actually in force.

**Acceptance Scenarios**:

1. **Given** a run in which gates are skipped, **When** the output is read, **Then** each skipped item is named and the reason is stated.
2. **Given** a run in which nothing is skipped, **When** the output is read, **Then** it states that explicitly, rather than saying nothing and leaving the reader to infer it.

---

### User Story 3 - A text marker proves behaviour, not resemblance (Priority: P3)

When a rule infers behaviour from source text — "does this script invoke the build tool?" — the marker is anchored to the syntactic form that constitutes an invocation, and the near-misses it deliberately rejects are written down beside it.

**Why this priority**: This is how the fix for the incident was nearly written wrong. Lower priority than P1 and P2 because it concerns the quality of an individual rule rather than the class-wide mechanism, but it is the difference between a fix that holds and one that trades one silent failure for another.

**Independent Test**: For each text-anchored rule, plant the near-miss forms named in its documentation and confirm the rule does not fire on them.

**Acceptance Scenarios**:

1. **Given** a script that mentions the build tool in a docstring, a printed suggestion, and a test fixture, **When** the rule inspects it, **Then** the rule reports it as cheap.
2. **Given** a script that actually invokes the build tool, **When** the rule inspects it, **Then** the rule reports it as heavy.

---

### Edge Cases

- **A rule with no resolvable source of truth.** The registry entry it depends on may be absent, or may fail to load. Resolution failure MUST NOT be read as "no match" — the incident's fix deliberately falls back to probing, and that fallback must be reported, not silent.
- **A self-matching rule.** A rule's own source contains its own marker, because the marker is written down in the file that tests for it. Observed in practice: the liveness filter matched its own source. Benign only where the item is independently excluded; otherwise it must be handled.
- **Two gates sharing one underlying script.** Skipping by resolved identity exempts both, which may not be intended if only one is expensive. Identity must therefore be keyed on the gate, with the script as the resolution path — not on the script alone.
- **A marker that is correct today and reworded tomorrow.** Anchoring to a specific syntactic form fixes the current class but not the next rewording. This is why P1's positive control exists independently of any particular marker.
- **An empty rule set.** A rule that currently matches nothing must either be removed or reported as inert, so that "matches nothing" is distinguishable from "never runs".

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A rule that decides skip, exempt, already-covered, or ignore MUST key on the item's resolved identity rather than on the spelling of its command, path, or filename. Resolution MUST go through the declared source of truth that already owns the item.
- **FR-002**: Where a resolved-identity source of truth exists for the class of item, the rule MUST use it. A text-matching rule is permitted only where no identity source exists, and MUST document that absence next to the rule.
- **FR-003**: Every skip rule MUST have a positive control covering **both** directions: a case that must be skipped and is, and a case that must not be skipped and is not. A control covering only the first direction permits the over-matching failure of User Story 3.
- **FR-004**: Every skip decision MUST be reported in the run output — the item named and the reason given — including when the set is empty. Unreported skips are treated as a defect.
- **FR-005**: When a skip rule's key fails to resolve for any registered item, the run MUST fail with a diagnostic naming the rule. Resolution failure MUST NOT be treated as "no match".
- **FR-006**: A rule whose key matches **every** item in its scope MUST fail the run. Universal exemption is indistinguishable from a broken rule and must not be reported as healthy.
- **FR-007**: A rule that infers behaviour from source text MUST anchor its marker to the syntactic form that constitutes the behaviour, and MUST document, beside the marker, the near-miss forms it rejects and why.
- **FR-008**: A refactor that changes how items are invoked, without changing which items exist or which are expensive, MUST leave the set of skipped items unchanged. Any deviation is a defect in the rules, not an expected consequence of the refactor.
- **FR-009**: The positive controls of **FR-003** MUST themselves survive the same refactor. A control that also depends on the old spelling provides no protection, because it fails to distinguish "the rule still works" from "the test no longer runs".

### Key Entities

- **Skip rule**: A decision to not apply some check to some item, expressed as a predicate over items. Carries a declared resolution source and a reason.
- **Resolved identity**: What an item actually is — which registered gate, which script it dispatches to — obtained from the source of truth that owns the item. Distinct from the item's spelling.
- **Positive control**: A test proving a skip rule fires where it must and stays silent where it must not. Required in both directions.
- **Resolution failure**: The condition where a rule cannot map an item to an identity. Never equivalent to "no match".

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Renaming the invocation syntax of every gate, with no change to which gates exist, leaves the count of skipped items identical before and after. Target: zero deviation.
- **SC-002**: Every run that skips at least one item names each skipped item and its reason in the output. Target: 100% of runs.
- **SC-003**: Every skip rule in the tree has a control that fails when the rule is deleted, and a control that fails when the rule is widened to match everything. Target: 100% of rules.
- **SC-004**: No suite run on a 62 GB development machine exceeds 16 GB of resident memory attributable to the run. Baseline before the fix: approximately 50 GB.
- **SC-005**: No suite run reports a gate as `timeout` where the gate's own definition classifies a timeout as a distinct verdict from a failure. Target: zero misclassified timeouts attributable to a skip rule that failed to apply.
- **SC-006**: A rule whose key resolves for zero items is either removed or reported as inert within one release cycle of the condition appearing. Target: 100%.

## Assumptions

- The gate registry remains the declared source of truth for gates and the script each dispatches to; this spec does not change the registry's role.
- This spec governs the rules that decide skip, exempt, already-covered, and ignore. It does not govern which checks exist, nor the checks themselves.
- Fixing the concrete instances already identified — `check-publication-hygiene.py:206,245`, `check-doc-dead-refs.py:760`, `check-script-conventions.py:50` — is in scope for planning. Migrating every text-matching expression in the tree is not; the spec constrains the class where it decides skip/exempt.
- The suffix-match form used for gating heavy commands against source text is treated as evidence of an invocation, on the basis that it corresponds to an argument list. If a future tool is invoked another way, FR-007 requires the new form be anchored and documented rather than added to the marker list unexamined.
- Existing gate entry points in CI and in the local check script are not modified by this work.