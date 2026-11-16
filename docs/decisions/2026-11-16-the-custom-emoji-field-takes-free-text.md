---
title: "The custom emoji field takes free text; the value is trimmed and kept verbatim"
date: 2026-11-16
status: accepted
tags: [projects, ui, emoji]
---

# The custom emoji field takes free text

## Context

The project icon picker offers the emoji catalog (#2919) and, below it, a free-text
field labelled "Custom emoji". The field was the one part of the picker nobody had
written down: it took whatever was typed, trimmed it, and — if anything was left —
handed it to `onPick` as an `Emoji`. No validation, no normalization, no catalog
lookup, and no test anywhere: the field had no test tag, so neither a Compose test nor
a Maestro flow could address it.

Issue #2932 asked for the intended contract to be documented and the paths to be
verified — and, explicitly, not to invent a stricter rule than the code has.

## Idea

1. Make the field accept only a single emoji, validated against the catalog.
   Rejected: it is a stricter rule the code never had, and it would reject values the
   catalog does not list — including composed sequences a user pasted.
2. Normalize the input (NFC) so "the same" emoji has one spelling.
   Rejected: normalization rewrites the string. The persisted value would no longer be
   the string that was typed, and the round-trip the issue asks for is exactly that
   string.
3. Document what the field does today, and pin it: trim, then keep.

## Decision

**The custom field takes free text. The value is the typed text with the whitespace at
both ends removed; everything else is kept exactly as it was typed.**

- Blank input (empty, or nothing but whitespace) picks **nothing**: the sheet stays
  open, the field keeps its text, nothing is stored and nothing is remembered in the
  recent-emoji history.
- Any non-blank input becomes the icon — a single emoji, a composed sequence (ZWJ
  person, family, flag, skin-tone modifier, variation selector, keycap), plain text, or
  a sequence no catalog lists.
- The value is **not** validated, **not** normalized and **not** looked up. The picker
  never converts one representation into another, which is the same rule the icon
  itself follows (`2026-11-14-project-icons-are-emoji-or-nothing`): a non-blank string
  is a value.

The rule lives in one place, `customEmojiFromInput` (`feature/projects/domain/logic/`),
so it can be asserted without a UI. The sheet calls it and does nothing else.

The field and its "Add" button carry test tags (`project_icon_custom_field`,
`project_icon_custom_add`), which is what made the custom entry addressable at all.

## Rationale

**The value is the user's, not the library's.** The picker draws a catalog, but the
field is the escape hatch for the emoji the catalog does not have, for a text label, and
for a legacy key that a column still holds. A validation step would have to know the
difference between "not an emoji" and "not in the catalog", and the second one is not a
defect — it is the reason the field exists.

**A composed sequence is one string.** `🧑‍💻` is five code points. A parser that counts
code points, splits a family into two entries or normalizes it into a different
spelling all change what the user typed, and the change is invisible until an icon
displays as the wrong glyph in a document that was exported to another device. Keeping
the string opaque is the only way the round-trip is exact.

**No home-grown Unicode parser.** The emoji helpers in this repository are deliberately
not a parser (`2026-11-13-the-emoji-helpers-have-no-library-equivalent`): `visibleLength`
counts code points, the import CLI reads a legacy hex format, and neither pretends to
know what a grapheme is. Adding a parser to validate one field would be a second
Unicode implementation with a second set of bugs.

**Blank is not a value.** There is no glyph to store and no glyph to fall back from.
Picking nothing is deterministic, and it is what the code already did — the test pins
it rather than changing it.

## Consequences

- The custom entry is covered: `CustomEmojiInputTest` (shared) asserts the rule —
  blank, whitespace, ordinary text, a single emoji, trimming, and the composed
  sequences of the shared fixture — and `IconPickerCustomEmojiTest` (desktop) asserts
  the binding: what the field holds is what the button hands back.
- The Android custom entry is covered by `Maestro/flows/projects/05-custom-emoji.yaml`:
  it types a ZWJ sequence into the field on a device, through the platform
  keyboard, and checks that the project draws it. How the value is *stored* is
  the question the JVM tiers answer — the picker applies the pick to the open
  project, and the write happens on the editor's save.
- The persistence boundaries are unchanged and already pinned
  (`ProjectIconBoundaryTest`, `ProjectDocumentWriteTest`, `BackupRoundTripTest`): the
  icon is a Unicode string in Room, in the backup DTO and in the sync document, and
  nothing converts it.
- The recent-emoji history already stores opaque strings with an escaping scheme
  (`2026-11-13-the-recently-used-emoji-live-in-the-user-settings`), which is what a
  free-text field needs. A custom value with a separator character in it round-trips
  through the history unchanged.
- The picker keeps its current Compose input UI. Nothing in the audit found a defect
  in it.
- A value that is not an emoji is a value: the icon is drawn as one grapheme, clipped,
  on one line (`ProjectIcon`), so a long text value is truncated on screen — the stored
  value is still the whole string.

## Links

- Issue: #2932 (parent #2917; coordinates with #2919, #2922, #2931)
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/domain/logic/CustomEmojiInput.kt`,
  `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/components/IconPickerSheet.kt`
- Tests: `shared/src/commonTest/.../feature/projects/domain/logic/CustomEmojiInputTest.kt`,
  `desktopApp/src/jvmTest/.../feature/projects/IconPickerCustomEmojiTest.kt`,
  `Maestro/flows/projects/05-custom-emoji.yaml`
- Decisions: `2026-11-14-project-icons-are-emoji-or-nothing`,
  `2026-11-13-the-emoji-helpers-have-no-library-equivalent`,
  `2026-11-13-the-recently-used-emoji-live-in-the-user-settings`,
  `2026-11-13-the-picker-installs-the-google-provider`
