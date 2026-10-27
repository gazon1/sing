# Specification Quality Checklist: Plans Timeline and Projects Menu

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-26
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

## Notes

Four decisions were taken by the user before the specification was written. They are recorded
here so that a later reader does not re-open them:

- **Calendar**: the Calendar leaves the bottom navigation bar and becomes an entry of the app menu
  — the bottom sheet on Android, the navigation drawer on Desktop. The Calendar screen itself does
  not change. The menu is its only new entry point; the specification deliberately does **not** add
  a second route from a timeline section header, because the user named the menu as the way in.
- **Granularity**: day, week and month, with horizons of 30 days, 12 weeks and 12 months. The set
  of granularities is open — a year level is a later addition, and it is listed as out of scope.
- **Project tree**: an expandable tree in its own Projects section of the menu, children indented
  under their parent, tapping a node opens the project.
- **Upcoming**: the existing Upcoming view stays as it is. Plans is a separate, sequential
  timeline beside it.

Two decisions were taken by the author because the description did not state them, and both are
recorded in the specification's Assumptions section rather than left implicit:

- **Overdue and undated work**: overdue tasks lead the timeline in their own section; undated
  tasks stay out of it. Both follow the app's existing convention, where overdue is the first
  section of every bucket-based view.
- **The full project list**: the tree is the primary way into projects, but the existing list —
  with its search, sorting, closed projects and project review — stays reachable from an entry in
  that same menu section, so that moving the list out of the tab bar removes a surface without
  removing capabilities. If the intent was for the list to disappear entirely, **FR-025**,
  **User Story 3 scenario 7** and the scenario **MENU-06** are the three places to strike.

The specification keeps the platform names "Android" and "Desktop" on purpose: the project's
constitution fixes the platform set, and a requirement that says "every platform" is vaguer than
one that names them. No language, framework, storage technology or API appears in the text.

`PLANS-*`, `MENU-*` and `NAV-*` in the scenario matrix are the automated carriers for this
feature; each functional requirement is claimed by at least one of them, with the exception of
the ones that restate existing behaviour and are covered by the regression scenarios.
