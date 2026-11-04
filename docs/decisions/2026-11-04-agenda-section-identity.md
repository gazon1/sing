# The agenda list is keyed by the section id, and the id is made unique where it is resolved

**Idea.** A saved view can hold two sections with the same display name — a
single-project selector renders as "Project", a tag selector as "Any tag: N".
The agenda's `LazyColumn` keyed its headers and rows by that name, so the second
section repeated the first section's key and the screen died with
`Key "header-Project" was already used`. Keying by `RenderedSection.id` fixes the
crash only if that id is unique, and it was not: a legacy section has no id and
`Section.effectiveId` derives one from the name, so two same-named legacy
sections still collided. The saved-view editor made it worse by minting
`"section_$count"`, a number that shrinks when a section is removed.

**Decision.** Section identity is decided in one place,
`AgendaDefinition.resolvedSections()`: it sorts by `order` and runs
`List<Section>.withUniqueIds()`, which keeps the first id and gives each later
duplicate a positional `_2`, `_3`, … suffix. The evaluator renders under those
ids, and the `+` button resolves a rendered id back to its section through the
same function, so the second same-named section's `+` still works. The editor
mints new ids with `Section.freshId`, which walks up from the current section
count until the id is free, and normalises a decoded legacy view so the ids are
persisted the next time it is saved.

The header's test tag follows the same rule: `TestTags.agendaSection(sectionId)`
is keyed by the id, so two same-named sections are separately addressable. For
the built-in presets the id happens to slug to the name, so the existing tag
values are unchanged; the statistics health views' tags move to the section id
(`agenda_section_stagnant`).

**Rationale.** Making the id unique at evaluation time, rather than making
`Section.id` non-null with a migration, keeps every already-stored blob readable
without a migration and keeps the invariant in one function. Disambiguating in
the UI would leave the `+` button without a way back to its section.

**Consequences.** `RenderedSection.id` is stable for a given definition and
order, but it is not the stored `Section.id` when the stored list contains a
duplicate; code that needs the stored section must go through
`resolvedSections()`. The header's badge is a second `Text` rather than part of
the ellipsized label, so a long section name no longer hides the task count.
