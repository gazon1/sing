---
title: "The recently used emoji are remembered in the user settings, and the pick does not wait for the write"
date: 2026-11-13
status: accepted
tags: ["projects", "settings", "compose"]
---

# The recently used emoji are remembered in the user settings, and the pick does not wait for the write

## Context

The project icon picker offered the same static emoji list every time it was
opened, so the emoji a person actually reaches for had to be hunted for again
and again. The picker now offers the last ten picked emoji in a "Recent"
section above the static list.

Three questions came up while this was built, and none of them has an answer
that the code alone explains.

**Where does the history live?** The feature's data layer owns repositories and
is off limits to the presentation layer — the architecture test
`ArchitectureTest.kt` bans `feature.*.presentation` from importing
`feature.*.data.*`. A store that the screen reads and the ViewModel writes is
neither of those things: it is a settings holder, and the project already has
the shape for it. `feature/whatsnew/presentation/WhatsNewPrefs.kt` reads and
writes the same `user_settings` DataStore from the presentation layer.

**How is the order kept?** DataStore's string sets do not keep one, and the
order *is* the feature: "recently used" is a queue. The list is therefore one
string, most recent first, joined by a separator.

**When is the history written?** The pick has to survive the picker being
dismissed the moment the emoji is tapped, and it has to survive a slow or
failing disk. The first version wrote the history *before* handing the pick
back, in the sheet's own coroutine scope — which made the user's tap wait for
the disk, and made the write disappear with the sheet.

## Idea

- Keep the history in the existing `user_settings` DataStore, under the
  app-wide key `projects.recent_emojis`. A store of its own would need its own
  migration, its own behaviour when the settings are cleared and its own answer
  to sign-out — for ten characters.
- Put the store in the presentation layer, beside the screen that reads it, the
  way `WhatsNewPrefs` sits beside the "what's new" notice.
- Store the list as one escaped string, and normalize it when it is read.
- Write the history from the ViewModel, after the pick was applied, through a
  queue the store owns.

## Decision

`RecentEmojiStore` lives in `feature/projects/presentation/`, is bound as a
Koin `single` and is given its own background scope
(`createBackgroundScope(loggingBackgroundFailureHandler())`), so a write
outlives the picker that started it.

`record(emoji)` is not a suspending call. It puts the emoji on an unbounded
channel; one writer coroutine in the store's scope applies the writes one
after another, in the order they were made. A failed write is logged, not
thrown.

The ViewModel applies the pick first and records it second:
`updateState { icon }` happens before `recordPickedIcon(icon)`, so a slow or
failing disk cannot delay the icon the user picked. `recordPickedIcon` records
only what `resolveProjectIcon` calls an emoji — a registry key and "no icon"
are not remembered.

The stored value is one string: the emoji, most recent first, joined by
`U+001F`, with `\` and the separator written as text (`\\`, `\u001F`) so that a
value containing them survives the round trip. The picker takes free text as a
custom emoji, so a stored value is not necessarily one glyph. Reading
normalizes: blanks dropped, duplicates dropped, order of first appearance
kept, at most ten entries.

## Rationale

**The pick does not wait for the disk.** The first version wrote the history
inside the sheet, before `onPick`. A slow write delayed the closing of the
picker; a failing one could stop the icon from being applied at all; and
dismissing the sheet cancelled the coroutine that carried the write. The
history is a convenience around the pick, not a condition of it.

**The write survives the sheet.** The scope belongs to the store, which is a
Koin `single` — it lives as long as the process. The channel keeps the order:
two picks in a row land in the order they were made, which one writer at a
time guarantees and a launch per record does not.

**The format is escaped rather than validated.** The custom-emoji field takes
any text, and `resolveProjectIcon` calls every non-registry string an emoji.
Refusing a value the picker itself accepted would be a worse contract than a
format that survives it. A JSON schema and a migration mechanism for ten
strings would be more machinery than the problem deserves.

**Reading normalizes.** What is on disk is not always what was written: an
older version kept a longer list, and a hand-edited settings file can hold
anything. The bound, the duplicates and the blanks are therefore settled on
the way in, not only on the way out.

**The store is in the presentation layer.** It is a settings holder, not a
repository: it has no port, no use case and no domain contract. The data layer
is for repositories the domain talks to; a screen reading its own settings
does not go through the domain.

## Consequences

- The history is app-wide and carries no profile id: a recently used emoji is
  a habit, not a setting of one profile. The key is
  `projects.recent_emojis`.
- Clearing the settings removes the history; the store reads a missing key as
  an empty history and the picker shows no "Recent" section.
- The stored format of a *project icon* is unchanged. The history is a
  preference of its own and does not touch what a project stores.
- The picker is free of Koin and of storage: `IconPickerSheet` draws the list
  it is given and picks what the user taps. The screen collects the history
  from the ViewModel.
- The write is asynchronous by contract. A test that wants to see the history
  after a pick has to let the writer run — `advanceUntilIdle()` does not,
  because background work is not part of "idle"; `runCurrent()` does.
- A composed sequence — a ZWJ family, a flag, a skin-tone modifier — is one
  value and stays whole: it contains neither the separator nor the escape
  character.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/RecentEmojiStore.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/domain/logic/RecentEmojiHistory.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/components/IconPickerSheet.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/ProjectsDiModule.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/projects/presentation/RecentEmojiStoreTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/projects/domain/logic/RecentEmojiHistoryTest.kt`
- `feature/whatsnew/presentation/WhatsNewPrefs.kt` — the same shape, for the same reason
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ArchitectureTest.kt` — the rule the store's placement answers
