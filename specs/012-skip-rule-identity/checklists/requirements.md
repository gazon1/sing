# Specification Quality Checklist: Skip-Rule Identity Integrity

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-15
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — requirements are stated as obligations ("MUST key on resolved identity"), not as code. Function and file names appear only in the Incident section, as evidence of what happened, which is the section's purpose.
- [x] Focused on user value and business needs — the value is that a refactor cannot silently void a safety rule, and that a machine's memory ceiling is not the thing that discovers it.
- [x] Written for non-technical stakeholders — the defect class is explained as "the spelling changed and the rule quietly stopped applying", with the two failure directions contrasted.
- [x] All mandatory sections completed — User Scenarios, Edge Cases, Requirements, Key Entities, Success Criteria, Assumptions.

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain — none were raised. Every judgment call was resolvable from the incident evidence and is recorded in Assumptions.
- [x] Requirements are testable and unambiguous — FR-003, FR-005, FR-006 and FR-008 each name the observable condition that decides pass/fail.
- [x] Success criteria are measurable — SC-001 (zero deviation), SC-003 and SC-006 (100% of), SC-004 (16 GB against a 50 GB baseline).
- [x] Success criteria are technology-agnostic — SC-004 and SC-005 reference resident memory and run verdicts, which are observable outcomes; neither names a tool or language.
- [x] All acceptance scenarios are defined — 9 scenarios across 3 stories, each with Given/When/Then.
- [x] Edge cases are identified — 5, each drawn from something observed during the incident or its fix: absent source of truth, self-matching rule, shared underlying script, future rewording, empty rule set.
- [x] Scope is clearly bounded — the Assumptions section states what is out: the registry's role is unchanged, the checks themselves are unchanged, CI and check.sh entry points are not modified, and migrating every text-matching expression is excluded.
- [x] Dependencies and assumptions identified — registry-as-source-of-truth, existing entry points preserved, the invocation-anchoring assumption stated with its reason and its expiry condition.

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria — FR-003 and FR-008 are directly testable by renaming; FR-005 and FR-006 by breaking the key.
- [x] User scenarios cover primary flows — the primary flow is a refactor (P1); visibility and marker anchoring are the two ways the class recurred.
- [x] Feature meets measurable outcomes defined in Success Criteria — SC-001 is directly checkable by the rename experiment already used to validate the fix.
- [x] No implementation details leak into specification — the one place a concrete mechanism appears (argument-list anchoring) is confined to Assumptions and framed as a bounded assumption with a stated expiry condition, per FR-007.

## Notes

**Failing items**: none. All 16 items pass.

**Scope boundary recorded deliberately.** The Incident section names four live instances of the class beyond the one that was fixed: `check-publication-hygiene.py:206` and `:245`, `check-doc-dead-refs.py:760`, `check-script-conventions.py:50`. These are listed as evidence the class recurs and are named for planning, but Assumptions excludes migrating every text-matching expression. If planning finds those four are cheap to migrate, the assumption can be revisited; if not, they stay as accepted, documented exceptions.

**Deliberately not required.** FR-003 asks for controls in both directions, which is stricter than the class strictly demands — a one-directional control passes the incident. It is included because writing the incident's fix naively trades an under-matching failure for an over-matching one, and only the two-directional control distinguishes them. Reviewers who consider this over-scoped should note that removing it re-admits the over-matching failure explicitly, rather than eliminating it.

**Relationship to existing work.** FR-008 and SC-001 describe the experiment used to validate the incident fix on 2026-10-15: rename every registry command's invocation syntax, confirm the skipped set is unchanged. That experiment is specified here as a permanent, repeatable acceptance test rather than a one-off verification.