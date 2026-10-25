# Change: close-rule-consolidation

## Why

The blocked-completion guard made the rule visible, and a visible rule has to have one
owner. It had several defects, all of them the same defect in different clothes — the
rule was enforced somewhere other than where the decision is made:

- the blocking predicate lived in two places, so the badge and the confirmation could
  disagree;
- the check ran in the view model and the write ran afterwards, so the two had a window
  between them;
- the bulk close went around the question entirely, because it called the write
  directly;
- the project's close cascade wrote several rows with nothing holding them together;
- the field that holds the blocking edges was called `dependsOn`, which is wider than
  what it holds.

## What changes

- **The write refuses.** Completing a task is refused while a blocking prerequisite is
  open, and the refusal is part of the statement rather than a check that runs before
  it. The surfaces ask the user when the write says no.
- **The bulk close asks too.** A selection that contains a blocked task closes nothing,
  and says which task is in the way.
- **The cascade is one transaction** and reads only the project's own tasks.
- **Reopening a task reopens its closed project.**
- **The field is called `blockedBy`** in the domain; the serialized name stays
  `dependsOn`.

## Impact

- Affected specs: `tasks`, `projects/project-closing`
- Affected code: `feature/tasks`, `feature/projects`, `core/database`, `core/sync`
- Schema: unchanged (no migration)
- Wire format: unchanged (the serialized name of the dependency set stays `dependsOn`)
