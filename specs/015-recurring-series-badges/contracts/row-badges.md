# Contract: The Row Badges

What a task row in a list carries, and what each badge is allowed to claim. The row is
the surface the user reads fastest and trusts least: it is where "is this repeating?"
and "which project is this?" have to be answered without opening anything.

Declared in `commonMain` under `feature/tasks/presentation/model` and
`feature/tasks/presentation/components/list`, because the badge is a presentation
concern and no other layer may depend on it.

## The row's badge fields

```kotlin
data class TaskUi(
    // …existing fields…
    val isRecurring: Boolean = false,
    val project: String? = null,
    val projectColor: Int? = null,   // ARGB, as Project.color
)
```

`projectColor` is new. `isRecurring` already exists and is already read by the row
(`TaskRowContent.kt:87`) — it has simply never been set to anything but `false`.

## The repeat badge

| Guarantee | Requirement | How it is proved |
|---|---|---|
| It says the task repeats | FR-021, FR-024 | derived from `task.recurrence != null` at the point the row is built — the same derivation `AgendaBadgePolicy` already uses for `AgendaBadge.Recurring`. |
| It is not a separately maintained flag | FR-022 | there is no second source of truth: the row builder reads the task's own rule. |
| It is not drawn where there is no rule | FR-025 | a task without a rule derives `false`. |
| It has an accessible name | FR-023 | `Icons.Default.Repeat` with the content description "Повторяющаяся задача" — the icon and the description already exist in `TaskMetaRow.kt:65-73`. |
| It is distinguishable | FR-026 | it sits beside the due date, the project badge and the blocked marker, not in place of any of them. |
| It shows even when the row has nothing else to say | FR-021 | the meta row's two gates — `hasMeta` (`TaskRowContent.kt:81`) and `hasSomethingToShow` (`TaskMetaRow.kt:48`) — both open for `isRecurring`. Without this, a repeating task with no due date, no project, not blocked and not in a series draws no meta row at all. |

## The project badge

```kotlin
data class ProjectBadge(
    val name: String,
    val color: Int,      // ARGB
)
```

| Guarantee | Requirement | How it is proved |
|---|---|---|
| It names the project | FR-027 | the project's name, resolved once per list build. |
| It carries the project's colour | FR-028 | `Project.color` is an ARGB `Int`; the Compose conversion precedent is `Color(project.color)` (`ProjectCard.kt:59`). |
| It is absent without a project | FR-029 | the badge is absent, not empty. |
| It replaces the plain text | FR-030 | the row no longer prints `task.project ?: "Без проекта"` (`TaskRowContent.kt:86`) — today that placeholder puts the literal "Без проекта" next to the date of every agenda row. |
| A long name truncates | FR-031 | ellipsis; the due date is not pushed out of the row. |
| It reads in both themes | FR-032 | the colour is the project's; the text is the theme's. |
| The lookup is not per row | FR-033 | the name and the colour are resolved once per list build and ride on the row item. |

## How the project reaches the row

```
ProjectsRepository.observeAllIncludingClosed()   ← once per list build
        ↓
AgendaDeps.projectsRepository                   ← new dependency (the repository is already bound)
        ↓
AgendaViewModel                                 ← combine(tasks, projects)
        ↓
AgendaRowItem.project / projectColor            ← the resolved pair rides on the row item
        ↓
AgendaTaskRow → TaskUi → TaskMetaRow           ← the badge is drawn
```

**The lookup includes closed projects.** `observeAll()` reads
`WHERE user_id = :userId AND is_deleted = 0 AND closed_at IS NULL`
(`Daos.kt:843-847`), so a task whose project was closed would resolve to nothing and
the badge would silently vanish. The plan adds a lookup that includes closed projects —
a query, not a schema change.

**The precedent for resolving once per list** is `TaskUi.toTaskUi`
(`TaskUi.kt:76-94`), which takes a `projectNamesById` map and resolves the project's
name from it — a name and nothing else. The badge extends that idea with the colour,
and it resolves it in the ViewModel rather than in the composable.

## What a badge may not do

- **A badge may not claim something the row does not know.** The repeat badge is derived
  from the task's own rule; the project badge from the resolved project. Neither is a
  flag maintained by hand, and neither may be set by a caller that has not read the
  underlying data.
- **A badge may not replace information.** The repeat badge does not stand in for the
  due date, and the project badge does not stand in for the project text — the text it
  replaces is the one the row used to print.
- **A badge may not grow the row.** FR-031: the row's height does not change because of
  a badge, and the due date stays visible.

## The badge slot that is not this contract

The agenda computes a per-row `AgendaBadge` — `Overdue`, `Recurring`, `Pinned`,
`Completed`, `NoDate`, `Blocked` (`AgendaUiState.kt:100-107`) — and **never renders
it**; only the section count is drawn. That slot is a separate concern and is out of
scope for this feature (the specification's "Out of scope"). The repeat badge here does
not go through it: it is drawn by the row itself, because the row is what the user
reads.

## Accessibility

- The repeat badge carries a content description naming the repeat.
- The project badge's text is the project's name, so it is read by the screen reader as
  the name; the colour is decoration and carries no information of its own.
