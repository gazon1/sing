<!-- GENERATED — do not edit by hand. -->
<!-- Source: infra/kiwi/scenarios/** + @DisplayName / scenario: tags in code. -->
<!-- Regenerate: just trace-coverage   Verify: just trace-coverage-check -->

# Coverage matrix

Derived from `infra/kiwi/scenarios/**` and the `@DisplayName` / `scenario:`
tags in code. It changes only when code or specs change.

| Glyph | Meaning |
|---|---|
| — | target not claimed for this scenario |
| ○ | claimed, but no automated test exists — **a hole** |
| ● | automated |
| ⊘ | scenario is deprecated — retired deliberately, not an obligation |

**4 scenarios · 2/4 claimed cells automated · 2 holes**

## feature.calendar

| Scenario | Title | android | desktop |
|---|---|---|---|
| `CAL-FILT-01` | Filter the calendar by project, tag, priority or status | ⊘ | ⊘ |

## feature.tasks

| Scenario | Title | android | desktop |
|---|---|---|---|
| `TASK-CHECK-01` | Add a checklist item to a task | — | ○ |
| `TASK-REC-01` | Create a daily recurring task | ● | ● |
| `TASK-TIME-01` | Start a timer on a task from its detail view | ○ | — |

## Holes

Claimed but not automated:

- `TASK-CHECK-01` / desktop
- `TASK-TIME-01` / android
