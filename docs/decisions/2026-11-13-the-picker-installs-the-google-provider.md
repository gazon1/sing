---
title: "The icon picker installs the Google provider and reads its catalog and search"
date: 2026-11-13
status: accepted
tags: ["emoji", "projects", "compose", "kmp"]
---

## Context

Issue #2919 replaces the picker's hand-written `COMMON_EMOJIS` list and its own search
with the Vanniktech Emoji data. #2918 pinned the core artifact and recorded two
facts that decide the shape of this change:

- the core publishes the API but no emoji data — a provider artifact supplies it;
- `GoogleEmojiProvider` is published only in the provider artifact's `androidMain`
  and `jvmMain`, never in `commonMain`.

`EmojiManager.categories()` is `internal`, so a common caller cannot read the
installed provider's categories from the manager. The public routes to the data
are the installed `EmojiProvider` itself and `SearchEmojiManager.search`.

## Idea

1. Keep the core-only dependency and hand-maintain a list of a few hundred
   emoji in the picker. Rejected: the issue's whole point, and the list would
   drift from the provider.
2. Add the provider dependency and create its provider through an
   `expect`/`actual` seam, then install it once and read categories and search
   from what the library installed.
3. Mirror the provider's category data into an app-owned catalog so common code
   never names a library type. Rejected: the issue forbids a second catalog, and
   the library types are already multiplatform.

## Decision

`libs.vanniktech.emoji.google` is declared next to the core in `commonMain`.
`core/emoji/EmojiCatalog.kt` holds an `EmojiProvider` created by
`internal expect fun createEmojiProvider()`, installs it through `EmojiManager`
on first use, and exposes `categories` (the provider's own array order) and
`search(query)` (`SearchEmojiManager`, mapped to `Emoji`).

Both actuals return `GoogleEmojiProvider()` — the class name is the same on
Android and JVM, so the seam exists for the package's source sets, not for
different behaviour.

`IconPickerSheet` draws the catalog in a bounded lazy grid with one heading per
category, and swaps it for the library's search results when the query is not
blank. An empty result shows a "no emoji match" line. Recent emoji and custom
emoji entry are unchanged.

## Rationale

**The seam is the provider's, not the app's.** The library's data is already the
contract: `EmojiProvider`, `EmojiCategory` and `Emoji` are common interfaces. A
project port wrapping them would be a second model whose only job is to rename
the first. The `expect`/`actual` function is the smallest way to reach the one
class the library keeps platform-only, and it follows `createHaptic()` and
`createHttpClient()`.

**One install, one catalog.** `EmojiManager.install` also builds the text scan
used by `emojiInformation`, and `SearchEmojiManager` reads the same installation.
A second index would be a search that disagrees with the text analysis.

**The provider's order is the category order.** `GoogleEmojiProvider` lists
Smileys, Animals, Food, Activities, Travel, Objects, Symbols, Flags; that is
deterministic and readable. There is no app-level ordering to maintain.

**The library's two-character floor is kept, not hidden.** `SearchEmojiManager`
returns nothing for a query shorter than two characters. The picker shows its
no-match state for that query rather than inventing a prefix search the library
does not have.

**The picker stays storage-free.** The catalog is a process-wide library
installation, not injected state. The sheet still draws what it is given for the
history and picks what the user taps; only the static list moved to the library.

## Consequences

- The picker offers the provider's full catalog on Android and Desktop; the
  hand-written `COMMON_EMOJIS` constant is gone.
- `core/emoji/EmojiLibraryApi.kt`, the compile-time probe from #2918, is removed:
  the real call sites now prove the same thing.
- The persisted project icon is still the emoji's Unicode string; nothing about
  project data or the recent-emoji format changes.
- The Android build carries `emoji-google-android`'s sprite resources and the
  Android provider's legacy View dependencies. The app keeps drawing its own
  Compose picker and never instantiates `EmojiPopup`, `EmojiView` or
  `EmojiTextView`.
- The first opening of the picker pays for `EmojiManager.install` (the text
  scan over the whole catalog). It is a one-time, process-wide cost.
- Tests: `EmojiCatalogTest` pins category order, empty query, a shortcode match
  and no-match; `IconPickerRecentEmojiTest` pins the picker's browse, search and
  no-match states.

## Links

- Issues: #2919, depends on #2918, parent #2917; recently-used stays with #2868
- Upstream: <https://github.com/vanniktech/Emoji/releases/tag/0.24.1>
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/core/emoji/EmojiCatalog.kt`,
  `shared/src/jvmMain/kotlin/com/singularity/todo/core/emoji/EmojiProviderFactory.jvm.kt`,
  `shared/src/androidMain/kotlin/com/singularity/todo/core/emoji/EmojiProviderFactory.android.kt`,
  `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/components/IconPickerSheet.kt`
- Decisions: `2026-11-13-emoji-come-from-the-vanniktech-core-artifact.md`,
  `2026-11-13-the-recently-used-emoji-live-in-the-user-settings.md`
