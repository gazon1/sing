# Specification Quality Checklist: Pull-to-refresh: синхронизация и перенос завершённого в архив

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

Validation run on 2026-10-25 against the drafted specification, after the three scope decisions were confirmed with the requester:

| Decision | Confirmed answer | Where it landed in the spec |
|---|---|---|
| Which screens carry the gesture | Data and detail screens; forms, sign-in and configuration screens excluded | «Охват» — two explicit screen lists plus the rule that separates them; FR-001, FR-002 |
| Is the archive step part of the gesture | Yes, everywhere the gesture exists | FR-011, FR-016; User Story 2 |
| What happens when the sync fails | Archive locally, report the network problem separately | FR-007, FR-017; User Story 3 |

Three issues were found and corrected during validation:

1. **FR-010 was not testable as first drafted.** It forbade a refresh from overwriting unsaved content, but the gesture is absent from every screen where the user writes — so on the screens the feature covers, the requirement could never be exercised and a test of it had no way to fail. It now states the invariant and names its reason (на экранах, где пользователь пишет, обновление поэтому и не предлагается), and the **Requirement traceability** table below points it at the «Охват» rule that carries it.
2. **FR-019 had no owner.** The requirement that the existing «archive all completed» action on the archive screen survives was stated in the requirements list but had no scenario and no section that owned it. The «Охват» section now says so in its own right, and a «Существующее действие архива» block in the requirements groups it with the related requirements, so it reads as a deliberate decision rather than an orphan.
3. **The screen scope was too loose.** The first wording ("списки, детали, меню") could not be checked against a screen added later. The «Охват» section now enumerates the six bottom-bar tabs, the seven menu destinations and the detail screens by name, and enumerates the excluded screens in five named groups (input, sign-in and account protection, configuration, attachment viewing, service and modal surfaces), plus the rule that separates the two lists.

A later correction, made while the screen inventory was verified against the working tree during planning: the scope list named two screens that do not exist — «задачи проекта» and «просмотр события календаря». There is no separate tasks-by-project screen (the project detail shows up to five tasks inline, and the destination `TasksByProject` is declared but has neither a screen nor a navigation entry), and the calendar has no separate event-detail screen — the month and day views are the calendar tab itself. Both were removed from the scope list, so the specification now names only screens a user can actually reach.

Two scope boundaries were chosen rather than inherited, and are stated as such:

- **The archive step is scoped to tasks.** Notes, projects and tags are not moved. Stated in the Assumptions section and in «Out of scope».
- **The archive change must reach the server within the same gesture** (FR-015). Without it, the gesture would archive locally and leave the server holding the un-archived copy, and the next pull would restore every task. FR-015 is the requirement that prevents the gesture from reintroducing that defect.

One further item was confirmed by inspection: **no [NEEDS CLARIFICATION] markers remain**. The requester's brief resolved all three open decisions — screen scope, the archive coupling, and the offline behaviour — with stated defaults, and each became a requirement rather than a marker.

## Requirement traceability

| Requirement | Owner |
|---|---|
| FR-001 | «Охват» — «Экраны, где обновление жестом есть» |
| FR-002 | «Охват» — «Экраны, где обновления нет» |
| FR-003 | US1 scenario 3; US4 scenarios 1–3 |
| FR-004 | US1 scenario 4 |
| FR-005 | US3 scenario 5; Edge case «Пользователь ушёл с экрана, пока обновление шло» |
| FR-006 | US1 scenario 1; US4 scenario 1 |
| FR-007 | US3 scenario 4 |
| FR-008 | US4 scenario 2 |
| FR-009 | US3 scenario 3 |
| FR-010 | «Охват» — the gesture is absent wherever the user is writing |
| FR-011 | US2 scenarios 1, 4 |
| FR-012 | US2 scenario 2 |
| FR-013 | US2 scenario 3 |
| FR-014 | US2 scenario 4 |
| FR-015 | SC-005; US4 scenario 1 |
| FR-016 | US1 scenario 2; SC-003 |
| FR-017 | US3 scenarios 1–2 |
| FR-018 | Edge cases «Ничего завершённого нет» and «Нечего отправлять и нечего получать» |
| FR-019 | «Охват» — «Существующее действие архива» |

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`.
- The specification covers one feature: a gesture that performs two steps (archive, then synchronise). The two steps share one indicator and one outcome report, so they are not separable into two features without duplicating the gesture.
- The screen inventory was taken from the working tree: the six bottom-bar tabs and the menu destinations are declared in the navigation destinations file, and the detail screens were read from the feature packages that own them.
- No branch was created for this specification — the repository's extension configuration registers no branch-creating hook for this command. The `Feature Branch` field records the intended name; the specification directory is `specs/015-pull-refresh-sync-archive`.
