# Specification Quality Checklist: External Agent in the App (ACP Client) and Agent Activity Visibility

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-23
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

Validation run on 2026-10-23 against the initial draft. Three items failed the first pass and were corrected before this checklist was finalised:

1. **No implementation details** — the draft named the storage engine in two places (the database probe requirement and the "database file is not a database" edge case). Both were rephrased as "structurally valid database file" and "the app's own bookkeeping marker". A sweep for language, framework, library, file-path, and class names now returns nothing; the only technical nouns remaining are the two protocols (ACP, MCP), which are the feature's contract rather than an implementation choice, and both are defined in the Terminology table.
2. **All functional requirements have clear acceptance criteria** — six requirements had no owning acceptance scenario (tool-registry consistency, defined startup failure, reproducible delivery-mechanism determination, refusal of file-system/terminal capability, resolution of pending permission requests on cancellation, and navigation reachability). Scenarios were extended or added (US2 scenario 8, US3 scenarios 1, 5, 11 and 12), and a **Requirement traceability** table now maps every FR to the scenarios that exercise it. Two requirements concern internal artefacts and are recorded as verified by automated check: FR-044 (lifecycle events reach the log, not the protocol stream) and FR-046 (the mobile build carries no external-agent code).
3. **Scope is clearly bounded** — the draft described the three delivery parts in the Problem section only. A **Delivery scope** table (Part A data safety, Part B activity visibility, Part C external agent, with dependencies) and an explicit **Out of scope** list were added, carrying the brief's exclusions forward verbatim in substance: app-as-agent role for an IDE, protocol v2 draft, MCP-over-ACP, in-process HTTP tool server, mobile support, changes to the tool set or proposal pipeline, external-agent cost accounting, a universal permissions system, an event bus, auto-retry of a failed turn, and a second parallel delivery mechanism.

Two further items were confirmed by inspection rather than by edit: no [NEEDS CLARIFICATION] markers remain (the brief resolved every open decision with a stated default — permission timeout 5 minutes fail-closed, default agent Gemini CLI in ACP mode, tag creation and decision-record writing kept as declared direct-write exceptions, legacy profile label deprecated but preserved), and every open decision the brief listed is reflected either in the requirements or in the Assumptions section.

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`.
- The specification covers one feature that decomposes into three independently mergeable parts (A, B, C). `/speckit-plan` may be run once for the whole feature or once per part; a separate specification directory per part can be created by re-running `/speckit-specify` with that part's scope.
- Verification against the codebase was performed before drafting: the database probe's delete-on-any-error behaviour, the manually interpolated error response, the label-only profile argument, the absence of a write classification on tool entries, and the text-only streaming response contract were each confirmed in the working tree.
