---
title: "The emoji helpers have no library equivalent, so they stay"
date: 2026-11-13
status: accepted
tags: ["emoji", "unicode", "refactor"]
---

## Context

Issue #2920 asks to replace hand-written emoji parsing, lookup, classification and
validation with Vanniktech Emoji wherever the library is semantically equivalent,
and to classify every existing helper as replaced, retained with a reason, or
removed as dead code. #2918 pinned the library at 0.24.1 and recorded its common
API; this entry is the audit that #2920 asks for.

## Idea

The audit found three emoji-shaped helpers, and each was compared with the
library's common API. The library's text API is `CharSequence.emojiInformation()`
(`visualLength`, `isOnlyEmojis`, `emojiCount`, `emojiRanges`), plus the catalog
and search that #2919 owns.

## Decision

**Nothing in the tree is replaced by the library in this issue, because nothing
in the tree has an equivalent there.** The three emoji-shaped helpers stay, each
for a different reason:

| Helper | Verdict | Why |
|---|---|---|
| `String.visibleLength()` (`core/text/TextMetrics.kt`) | **Retained** | Counts Unicode code points — not visible characters and not grapheme clusters. A different question from `visualLength`, which counts each recognized emoji sequence as one. |
| `recentEmoji` history (`RecentEmojiHistory.kt`, `RecentEmojiStore.kt`) | **Retained** | Persists the user's picks as opaque strings in the app's own DataStore. The library's `RecentEmoji` is a platform-only persistence port, not a common one. |
| `emojiCodepointToIcon` (`import-cli/.../Mapper.kt`) | **Retained** | Parses the legacy import format (`1f9d1-200d-1f4bb`). That is this app's file format, not an emoji question, and the library has no parser for it. |

Nothing was removed as dead code: every helper has a live caller.

Two further things look emoji-related and are deliberately untouched:

- `Mapper.charCount` (`import-cli`) is `s.length` for a legacy note's stored
  metadata. It is a count the importer copies through, not a rule the app
  applies, and changing it would change an imported value — which #2917
  forbids.
- `COMMON_EMOJIS` in `IconPickerSheet.kt` is a hand-maintained *catalog*, not a
  helper, and #2919 replaces it with the library's catalog and search.
  `core/emoji/EmojiLibraryApi.kt` is #2918's compile-time probe; #2919 deletes
  it.

## Rationale

**`visibleLength` counts code points, while the name limits' documentation says
something else.** `visibleLength()` is `codePointCount(0, length)`: `🎉` is 1,
`🇬🇧` is 2, `🧑‍💻` is 3, `👨‍👩‍👧‍👦` is 7. `ProjectsDomain.MAX_NAME_LENGTH` and
`TagDomain.MAX_NAME_LENGTH` describe their limits as counted in "visible
characters" and say "an emoji counts once here". That holds for `🎉` and fails
for a flag or a ZWJ family: the limit is read by people as characters, but it is
enforced over code points. The discrepancy is real and is *not* resolved here.

`visualLength` is a different count again: every sequence the installed catalog
recognizes as an emoji counts as one (`emojiInformation()` subtracts the length
of each emoji range and adds the number of ranges), while every other character
is charged by its UTF-16 length. It is not a general Unicode grapheme counter —
it knows only the provider's catalog.

| String | `visibleLength()` | `visualLength` |
|---|---|---|
| 25 flags | 50 | 25 |
| 50 flags | 100 | 50 |

A name of 50 flags measures 100 code points today and is rejected against the
50-character limit; the emoji-aware count would call it 50 and accept it.

**Which of the three a name limit should bound is an open product decision, not
a conclusion of this audit.** Code points, catalog-recognized emoji units, and
grapheme clusters each give a different answer for a flag, a ZWJ family, a
skin-tone modifier and a combining mark. This issue only refuses to change the
semantics silently or to force-fit the library's count; the choice, and the
matching wording in `ProjectsDomain` and `TagDomain`, belongs to a separate task
(#2933). Until then `visibleLength` keeps its behavior, so names already
accepted stay accepted.

**`visualLength` needs an installed provider, and common code cannot assume
one.** It calls `EmojiManager.findAllEmojis`, which calls `verifyInstalled()`
first and throws `IllegalStateException("Please install an EmojiProvider …")`
when nothing is installed. No provider ships in the metadata jar's `commonMain`
— the providers (`GoogleEmojiProvider`, …) live in a platform source set
(recorded in #2918's ADR). #2919's picker installs one lazily, on first access
to its catalog, so a call from a domain helper is safe only after that access
has happened in the same process — an ordering the helper does not control.
Replacing `visibleLength` would turn a pure count into one that can throw or
change its answer depending on what the user has opened.

**The history is a preference, not an emoji operation.** `RecentEmojiStore`
writes ten strings to `user_settings` and escapes a separator so a value holding
it stays one value. The library's `RecentEmoji` interface is
`getRecentEmojis()/addEmoji(Emoji)/persist()` — an `Emoji` object, not the
user's free-text value, and its only implementations (`NoRecentEmoji`) are
platform-side. Adopting it would either change the persisted value (forbidden by
issue #2917) or wrap it in a facade (#2917 forbids that too).

**The importer parses a file format, not a glyph.** `1f9d1-200d-1f4bb` is how a
backup file spells a person technologist. Any parser of that string must split on
`-`, parse hex and join the code points; the library has no such API and would
not know this format existed.

## Consequences

- The three helpers keep their behavior and their callers; no production code
  changes in this issue.
- `TextMetricsTest` pins `visibleLength` for `""`, ASCII, an astral emoji, a ZWJ
  sequence, a family, a flag, a skin-tone modifier, a variation selector, and
  lone/unpaired surrogates. The last group is the malformed-input contract: the
  value can come from a settings file, and the count must not throw.
- `MapperTest` pins `emojiCodepointToIcon` for blank, non-hex, and out-of-range
  input, and for the single-code-point base case. The out-of-range case matters:
  `String(Character.toChars(0x110000))` throws, so the guard that drops it keeps
  one bad legacy row from failing the whole import.
- `visibleLength` stays a code-point count, so the discrepancy with the
  "visible characters" wording in `ProjectsDomain` and `TagDomain` also stays.
  #2933 decides both the count and the wording; this entry does not declare the
  current semantics correct.
- `visibleLength` is documented as *not* interchangeable with `visualLength`;
  a future change that wants emoji-aware counting must say so, and must not call
  it from code that cannot guarantee an installed provider.
- If #2919's provider seam lands and something in the app wants emoji-aware
  counting, that is a new decision with its own contract, not a swap in this
  helper.

## Links

- Issues: #2920, parent #2917; depends on #2918; siblings #2919, #2921, #2922
- Follow-up: #2933 — what a name-length limit counts (code points,
  catalog-recognized emoji units, or grapheme clusters), and the matching wording
- Decisions: `2026-11-13-emoji-come-from-the-vanniktech-core-artifact.md` (the
  dependency and its limits), `2026-10-05-single-source-name-validation.md`
  (where `visibleLength` came from), `2026-11-12-project-identity-is-one-icon-composable.md`
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/core/text/TextMetrics.kt`,
  `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/domain/logic/RecentEmojiHistory.kt`,
  `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/RecentEmojiStore.kt`,
  `import-cli/src/main/kotlin/com/singularity/todo/importer/Mapper.kt`
- Tests: `shared/src/commonTest/kotlin/com/singularity/todo/core/text/TextMetricsTest.kt`,
  `import-cli/src/test/kotlin/com/singularity/todo/importer/MapperTest.kt`
