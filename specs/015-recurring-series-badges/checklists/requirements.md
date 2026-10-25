# Specification Quality Checklist: Repeat: материализация будущих вхождений и бейджи в строке списка

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-25
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Validation Notes

**Iteration 1 — review findings and resolutions**

| Item | Finding | Resolution |
|---|---|---|
| No implementation details | The requirements section names no language, framework, storage engine or API. The appendix «Evidence» names source files, but it is diagnostic context for the reviewer, not a requirement, and no requirement depends on it. | Kept as an explicitly labelled appendix. |
| Written for non-technical stakeholders | The body is written in user-facing language; the terms «серия», «вхождение», «горизонт» are defined in «Terminology». | Terms defined up front. |
| Requirements are testable and unambiguous | FR-006 originally read «Набор дат серии», which also covers closed past occurrences and is therefore not a function of rule and horizon alone. | Narrowed to «Набор будущих дат серии». |
| Requirements are testable and unambiguous | FR-005 contained an English phrase («MUST hold the same future dates») left over from drafting. | Rewritten in the document's language. |
| All acceptance scenarios are defined | Three user stories, 18 acceptance scenarios, 9 edge cases. Part A's user story covers creation, completion, horizon advance, rule change, refusal and work-field edit. | No gaps found. |
| Scope is clearly bounded | The user asked for three things; the spec delivers exactly three parts plus the legacy-data and calendar consequences that those three would otherwise break silently. | «Out of scope» names the excluded work explicitly, including the unused per-row badge slot (Overdue/Pinned/Completed/NoDate), the task detail screen, and full calendar-export policy. |
| Success criteria are measurable | SC-001…SC-010 all carry counts, percentages or a before/after comparison. SC-003 and SC-004 state the current baseline (0 %) so the target is falsifiable. | Baseline recorded in the criterion. |
| Dependencies and assumptions identified | The horizon (14 days), the two caps (10 future / 30 total), the badge placement and the reuse of the existing series model are stated as assumptions with their justification. | Documented in «Assumptions». |

**Decisions resolved with the user before drafting** (no residual ambiguity):

1. Future occurrences are **materialised as separate rows** rather than the current row moving forward.
2. The repeat and project badges live in the **list row only**; the task detail screen keeps its editable attribute rows.

**Residual risks carried into planning** (not spec defects):

- The horizon, the two caps and the calendar-export rule are product choices this spec makes explicit; the plan may propose alternatives, but changing them changes FR-007…FR-009 and FR-042.
- The repository contains an existing test that asserts the defective behaviour (rolled-forward task stays closed). That test's expectation is superseded by FR-004 and FR-036 and must be rewritten rather than preserved.
- `AGENTS.md` requires an ADR for architectural decisions above one week of effort. Part A changes how a recurring task is represented, so an ADR is expected during planning.

---

## Re-validation after the amendments

The specification was amended five times after this checklist was first written — twice
by the Phase 0 research, and three times by independent design review. The checklist was
re-run against the amended specification; it passes, and the record of what changed is
below.

**Amendments that changed what the specification requires**

| Amendment | Why | Where it is recorded |
|---|---|---|
| The window is anchored at the **current occurrence**, not at today (FR-006, FR-007) | A horizon measured from today makes the row set a function of the calendar day, which needs a scheduled write to notice. | spec Change log 2026-10-25 (first amendment) |
| The base decides the anchor date, and "from completion" is the default (FR-043, FR-046) | `FROM_COMPLETION` measures from the close, and it is the picker's default. | spec Change log 2026-10-25 (second amendment) |
| The ceiling rotates rather than refuses (FR-009, FR-045) | A refusal would stop a daily task recurring after thirty completions. | spec Change log 2026-10-25 (first amendment) |
| A rule that has run out **closes** the occurrence and archives nothing (FR-047) | Today it is soft-deleted; a closed occurrence is already out of the active list. | spec Change log 2026-10-25 (third amendment) |
| The rule loses when it meets picked dates (FR-048) | The answer the create screen already gives the user. | spec Change log 2026-10-25 (third and fourth amendments) |
| The repair covers the active profile at the next launch with that profile active (FR-034) | No profile-switch trigger is designed, and a promise without a trigger is a promise nobody keeps. | spec Change log 2026-10-25 (fourth amendment) |
| An occurrence that leaves a recurring series loses the rule (FR-049) | The base carries a capability (`task-series`) that owns the series model, and an operation the change had not considered: taking one occurrence out of its series. | spec Change log 2026-10-25 (fifth amendment) |
| Two kinds of series are not joined into one series (FR-050) | The same capability's join would otherwise mix a rule and hand-picked dates in one series. | spec Change log 2026-10-25 (fifth amendment) |

**Amendments that removed a contradiction rather than added a requirement**

| Requirement | What contradicted it | Resolution |
|---|---|---|
| FR-005 | the "from completion" shift moves the dates | count-based: the series holds **no fewer** future dates, not the *same* ones |
| FR-010 | FR-044 and FR-008 — a daily rule fills the cap inside the horizon | the first date past the horizon is materialised **while the cap leaves room** |
| FR-042 | the Out-of-scope paragraph still stated the superseded calendar policy | the paragraph now pins only FR-042 |
| SC-010 | the old "the number of calendar events does not grow" | the number equals the number of open occurrences |
| User Story 1, scenario 3 | the anchored model — the window does not advance with the day | the series grows when an occurrence is consumed |

**The checklist, re-run**

| Item | Verdict | Note |
|---|---|---|
| No implementation details | PASS | The requirements name no language, framework, storage engine or API. The «Evidence» appendix names source files as diagnostic context for the reviewer, and no requirement depends on it. |
| Focused on user value | PASS | Every requirement is about what the user sees, gets or is told. |
| Written for non-technical stakeholders | PASS | «Terminology» defines серия, вхождение, горизонт and the two badge kinds. |
| All mandatory sections completed | PASS | User Scenarios & Testing, Requirements, Key Entities, Success Criteria, Assumptions. |
| No [NEEDS CLARIFICATION] markers remain | PASS | The two decisions the user was asked for were taken before the specification was written; the rest were settled by research and review. |
| Requirements are testable and unambiguous | PASS | 50 requirements, each defined once; every one has a scenario in the OpenSpec delta or a check in the quickstart. |
| Success criteria are measurable | PASS | SC-001…SC-010 carry counts, percentages or before/after comparisons; SC-003 and SC-004 state the current baseline (0 %). |
| Success criteria are technology-agnostic | PASS | No criterion names a tool, a store or a framework. |
| All acceptance scenarios are defined | PASS | Three user stories and 20 acceptance scenarios in the specification; 37 scenarios in the OpenSpec delta. |
| Edge cases are identified | PASS | Nine edge cases, plus the boundary cases the reviews found (a daily rule at the cap, a rule at its end date, a series with no closed row to rotate). |
| Scope is clearly bounded | PASS | «Out of scope» names the excluded work, including the unused per-row badge slot, the task detail screen, and the full calendar-export policy. |
| Dependencies and assumptions identified | PASS | The horizon, the two caps, the badge placement, the per-profile repair scope and the reuse of the existing series model are stated with their justification. |
| All functional requirements have clear acceptance criteria | PASS | Every FR maps to a scenario in the delta or a check in the quickstart. |
| User scenarios cover primary flows | PASS | Creation, completion, refusal, rule change, rule removal, exhaustion, repair, and both badges. |
| Feature meets the measurable outcomes | PASS | SC-001…SC-010 are each reachable from the requirements that implement them. |
| No implementation details leak into the specification | PASS | The implementation detail lives in `research.md`, the contracts and the ADR — where the plan phase belongs. |
