---
title: "A project icon is an emoji or nothing; there is no second representation"
date: 2026-11-14
status: accepted
tags: [projects, ui, emoji]
---

# A project icon is an emoji or nothing

## Context

`projects.icon` was a nullable string that could mean three things at once: a key into
`ProjectIconRegistry`'s 18 Material icons (`"Work"`, `"FolderSpecial"`), an arbitrary
emoji, or nothing. `resolveProjectIcon` classified the string and `ProjectIcon` branched
on the result; the picker drew both a Material row and the emoji catalog, so the same
project could be picked as an icon and rendered as a key.

Issue #2934 removed the first leg of that triangle by typing the field as `Emoji?` in the
domain. The presentation layer kept the resolver and the registry, so the string form and
the Material keys survived below the type — including a hand-written `key → emoji` map in
the picker that translated one representation into the other at the point of selection.

A second representation needs a reason. There is none: the picker has offered the
library's full emoji catalog since #2919, which is strictly larger than the 18 icons the
registry held, and the registry's own ADR budgeted 18 icons only because a hand-maintained
list is expensive to grow.

## Idea

1. Keep the resolver and the registry, and map Material keys to emoji at render time.
   Rejected: that is the compatibility path the issue forbids, and it keeps two spellings
   of one icon alive for no user-visible gain.
2. Migrate stored Material keys to emoji. Rejected: there is no correct mapping
   (`"MoreHoriz"` became `"⋯"`, which is not an emoji), and the epic rules out data
   migration.
3. One representation end to end: the stored `Emoji`, or the folder fallback.

## Decision

`ProjectIcon(icon: Emoji?, …)` draws `icon?.value` when it is not blank and the folder
otherwise. It is the only renderer for a project's icon.

`ProjectIconResolver.kt` (`ResolvedProjectIcon`, `resolveProjectIcon`) and
`ProjectIconRegistry.kt` are deleted. A blank or absent value is the fallback; a non-blank
value is drawn as one grapheme. That rule is two lines inside the composable and does not
earn a type.

`IconPickerSheet` draws the recent row and the library catalog only. The Material row and
its `registryKeyToEmoji` map are gone; the picker emits `Emoji`.

The `.agents/skills/singularity-todo-icon-registry/` skill is retired: it taught the
Material-registry pattern, and its reference implementation is now the code this decision
removes. `docs/SKILLS-CATALOG.md` is regenerated.

## Rationale

**The type already says it.** Once the domain field is `Emoji?`, a resolver that inspects
the string and returns a sealed answer restates the type's own contract in a second
vocabulary — and a caller that has to branch twice for one nullable value will eventually
branch inconsistently.

**Deleting is the migration.** Nothing maps a Material key to a glyph any more, so a
legacy key in an imported database draws as its own text and falls back only when blank.
That is why the mapping is removed rather than kept as a fallback: a mapping that only
serves data nobody can produce is a path that tests cover and users never take.

**The picker was the last producer.** With the Material row removed, no code can write a
registry key, so the registry's remaining job was to render values that can no longer be
created.

## Consequences

- A project shows one glyph everywhere: menu tree, card, detail hero, editor chip, task
  project picker — the same `Emoji?` reaching the same composable.
- The folder fallback and the one-grapheme rendering (no wrap, no ellipsis, clip) are
  unchanged, including for composed sequences.
- The persisted format is unchanged: still the Unicode string in `projects.icon`.
- `ProjectIconResolverTest` is deleted with the resolver it tested. The rule it covered —
  blank means fallback, a composed sequence survives — is asserted by
  `ProjectIdentityTest` (fallback tag, stored sequence reaches the text layer) and by the
  Android `ProjectIconEmojiRenderTest` (a sequence paints one unclipped line).
  `ProjectEditorViewModelTest`'s "a registry icon is not remembered" is deleted too: no
  intent can carry a registry key any more, so the case cannot be constructed.
- A previously stored Material key draws as text until the user picks a glyph. The
  fallback is not used for it, because a non-blank value is a value.
- ADR `2026-11-12-project-identity-is-one-icon-composable` was superseded in part: its
  "shared resolution contract" is now this entry's direct rendering.

## Links

- Issue: #2935, parent #2917, depends on #2934 and #2919
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/components/ProjectIcon.kt`,
  `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/components/IconPickerSheet.kt`
- Removed: `ProjectIconResolver.kt`, `ProjectIconRegistry.kt`, `ProjectIconResolverTest.kt`,
  `.agents/skills/singularity-todo-icon-registry/`
- Decisions: `2026-11-12-project-identity-is-one-icon-composable`,
  `2026-11-13-the-picker-installs-the-google-provider`,
  `2026-11-13-the-recently-used-emoji-live-in-the-user-settings`
