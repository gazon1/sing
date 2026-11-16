---
title: "The emoji library owns the data, the app owns the glyph"
date: 2026-11-16
status: accepted
tags: ["emoji", "unicode", "kmp", "testing"]
---

## Context

Issue #2922 closes the loop of #2917. The migration itself is done: #2919 gave the
picker the library's catalog and shortcode search, #2920 audited the app's emoji
helpers, #2931 decided the app-level `Emoji` value type, and #2934–#2936 carried
the project icon through it. What is left is what #2922 names: the boundary written
down where a reader will find it, the guardrails that still read as if the app had
no emoji library corrected, and a proof per platform that a composed sequence is
drawn whole.

Three statements were still wrong, and each one was wrong in a way that would
mislead the next reader:

- ADR `2026-11-12-project-identity-is-one-icon-composable.md` said the parent
  issue forbade "a second identity model or emoji library" and listed "no new
  dependency" as a reason. The library is here now, and the constraint it
  replaced was about a *duplicate*, not about the dependency itself.
- ADR `2026-11-13-emoji-come-from-the-vanniktech-core-artifact.md` described the
  library's `visualLength` as answering "visible characters" for emoji. It
  counts each sequence the installed catalog recognizes as one and charges
  everything else by UTF-16 length; it is not a general grapheme counter.
- `String.visibleLength()` documented itself as "Number of visible characters in
  this string". It counts code points: `🇬🇧` is 2, `🧑‍💻` is 3, `👨‍👩‍👧‍👦` is 7.

The audit behind #2920 had already measured the library's data, and #2922
measured it again, on both platforms, with the pixels.

## Idea

- One entry that states the boundary — who owns the emoji data, who owns the
  glyph — and corrects the statements that survived the migration.
- Record what the migration left in the app: the narrow `Emoji` value type, the
  custom field's contract, and the helpers that stayed with their real
  semantics.
- Say how a platform proves its own rendering, and why one platform's proof says
  nothing about the other.

## Decision

**The Vanniktech library owns the shared emoji data and operations. The app owns
its domain values, its persistence and its rendering.**

| Concern | Owner |
|---|---|
| Catalog, categories, shortcode search, `emojiInformation()` | Vanniktech (`shared/commonMain`) |
| The project's icon value | the app — `Emoji` in `core-domain` |
| Persistence (Room, backup, sync wire format) | the app — plain strings, unchanged |
| Drawing the glyph (picker chips, `ProjectIcon`) | the app — Compose Multiplatform |

Concretely:

1. **No second emoji infrastructure in the app.** The app adds no catalog, no
   search index, no parser and no validator of its own; `EmojiCatalog` is the
   seam that reads the installed provider. The library's Android View widgets
   (`EmojiPopup`, `EmojiView`) never appear in shared code — the picker is the
   app's own Compose sheet on both platforms.

2. **The app-level `Emoji` value type stays narrow** (decided in #2931,
   implemented by #2934): a string wrapper for the *project icon* API only. It
   holds no catalog, parses nothing and validates nothing; it preserves the
   exact Unicode string. Task and profile emoji fields stay strings.

3. **The custom field takes any text, trimmed.** The picker's free-text field
   accepts any non-blank text as the project's icon. The text is **trimmed** —
   the outer whitespace is dropped, the inner whitespace is kept — and what
   survives is taken as it is: not checked against the catalog, not parsed, no
   normalization, no stripped variation selector, no split ZWJ sequence. It is
   not checked against the catalog because the provider holds some of the app's
   own forms (`🕊️`, `1️⃣`) as *variants* rather than as entries of their own,
   so "is it in the catalog?" would reject values the app itself stores. Empty
   or whitespace-only input adds nothing. #2932 owns the product decision on
   stricter validation; until then this is the behavior, and it is pinned by
   `IconPickerCustomEmojiTest` (desktop) and `IconPickerCustomInputTest`
   (Android).

4. **The retained helpers keep their real semantics.** `visibleLength()` counts
   code points — not visible characters and not grapheme clusters. The library's
   `visualLength` counts each catalog-recognized sequence as one and is not a
   universal grapheme count either. `Mapper.emojiCodepointToIcon` parses the
   legacy import format (`1f9d1-200d-1f4bb`), which is this app's file format
   and not an emoji question. ADR
   `2026-11-13-the-emoji-helpers-have-no-library-equivalent.md` keeps the full
   reasoning; what is new here is that the semantics are now stated correctly
   everywhere they are repeated.

5. **A platform proves only itself.** Each platform's render test asserts what
   that platform can show, and the two do not assert the same things:

   - **Android** (`ProjectIconEmojiRenderTest`) asserts that the sequence
     reaches the screen as *one text node*, that the icon box has ink, and that
     no ink touches the box's border. A font that drew the sequence wider than
     the box would clip it at the edge, and that is what a sequence the font
     refuses to compose looks like. It does not check the centring.
   - **Desktop** (`ProjectIconEmojiRenderTest`) asserts the same two pixel
     rules inside the icon box, plus that the ink is centred.
   - **The picker** is checked where each platform can check it: on the
     desktop the chip paints ink, and the composed chip's ink stays on one
     line — its lower edge does not fall below the single emoji chip's — while
     a control chip of plain text does wrap in the same 40dp box, so the
     measurement can fail; on Android the sheet shows the composed sequence as
     one node, both from the recent row and from the catalog's own search
     results.

   Every desktop rule has a control beside it in the same file, so a check that
   cannot fail is not left standing on its own: the same two emoji **without**
   their joiner (`🧑💻`) are put into the same icon box and into the same chip,
   because that is the value a font that refuses to compose produces, and
   plain text too wide for the box is put beside it as a control that does not
   depend on any font's idea of an emoji.

   An Android screenshot does not establish desktop correctness, and the desktop
   check does not establish Android's: the two platforms use different fonts,
   and a font that composes a sequence on one can draw it as two glyphs on the
   other.

## Rationale

**The boundary is the thing that goes stale.** The migration was decided in
pieces — one issue for the dependency, one for the picker, one for the helpers,
one for the value type — and each piece wrote down only its own part. The
result was an ADR that forbade an emoji library, sitting next to a dependency
that *is* an emoji library. Stating the boundary once, in the decision that
survives, is what keeps the next reader from re-deriving it.

**The library's data answers the questions the app actually asks.** Measured
against the Google provider the app installs — Vanniktech Emoji 0.24.1, the
version `gradle/libs.versions.toml` pins:

| Value | Catalog entry | Variant of |
|---|---|---|
| `🧑‍💻` (imported person technologist) | one entry, shortcode `technologist` | — |
| `🇬🇧` | one entry, shortcodes `gb`, `uk`, `flag-gb` | — |
| `👨‍👩‍👧‍👦` | one entry, shortcode `man-woman-girl-boy` | — |
| `🧘🏽` (skin tone) | — | `🧘` |
| `🕊️` (variation selector) | — | `🕊` |
| `1️⃣` (keycap) | — | `1⃣` |

The catalog's size is the library's business and changes with every version it
pinned, so it is not written down here; what matters is the shape of the data,
and that shape is stable.

So a composed sequence is one emoji to the library — a family is not four
people — and the app's own stored forms are the library's forms, byte for byte.
That is what lets the picker offer them again and what lets the app promise
that a stored icon round-trips unchanged.

**A catalog miss is not a rejection.** The provider stores the skin-tone,
variation-selector and keycap forms as variants of a base entry. A value the
app stores can therefore be *outside* the catalog's entries while being a
perfectly valid icon — the dove is the example: the app stores `🕊️`, the
catalog's entry is `🕊`. Validating a stored or typed value against the
catalog would reject the app's own values, so the app does not do it, and the
custom field's contract says so out loud.

**The rendering proof has to be per platform.** Measured on the desktop with
the project's own `ProjectIcon` at 24dp: `🧑‍💻`, `🇬🇧`, `🧘🏽`, `🕊️`, `👨‍👩‍👧‍👦`
and `1️⃣` each paint inside the box, none of them clipped at its edge, all of
them centred. The check exists because it can fail: a font that refuses to
compose a sequence draws it wider than the box, and the box cuts it.

**The proof has teeth, and that is measured too.** In the same 24dp box the
same two emoji without their joiner (`🧑💻`) are two glyphs: their ink runs
from the first pixel to the last (1 to 23 of 24), so the edge rule fires on
exactly the value the regression would produce — the composed sequence's ink
runs from 3 to 21 and touches no edge. Plain text too wide for the box
(`WWWWWWWW`) is cut at the edge as well, so the rule fires independently of
what a font does with an emoji. In the picker's 40dp chip the same split
shows up vertically: the composed sequence's ink reaches 25, a single emoji's
25, and the unjoined sequence's 31 — the second line the chip's rule forbids.
The negative control therefore fails the rule it is a control for, which is
what makes the positive check mean something.

**What the ink rule cannot catch is stated.** A font with no glyph for the
sequence draws one tofu box — ink inside the box, no clipping — and the rule
cannot tell that from a composed glyph. Both platforms' tests carry that
bound in their own words rather than implying more than they prove.

## Consequences

- `String.visibleLength()` says what it counts (code points) instead of
  claiming to count visible characters. Its behavior is unchanged, so names
  already accepted stay accepted. The question of *which* quantity a name
  limit should bound — code points, catalog-recognized emoji units, or grapheme
  clusters — stays open in #2933, together with the matching wording in
  `ProjectsDomain` and `TagDomain`.
- ADR `2026-11-12-project-identity-is-one-icon-composable.md` and ADR
  `2026-11-13-emoji-come-from-the-vanniktech-core-artifact.md` no longer
  contradict the code they describe.
- The custom field's contract is written down and pinned. #2932 can tighten it
  with a baseline in hand; until then no value the app stores is rejected by
  the field that produced it. The contract is pinned on both platforms — a
  shared composable is not a shared test, because the platform owns the text
  input and the semantics tree — and the value the field hands over is pinned
  at the editor too, so the chain from the field to the saved project has no
  untested link in it.
- Emoji literals stay legal in the source. There is no global scanner and no
  blanket ban: the values under test *are* emoji, and a test that cannot name
  them cannot assert on them.
- The recently-used emoji history stays app-owned (ADR
  `2026-11-13-the-recently-used-emoji-live-in-the-user-settings.md`,
  #2868): the library's catalog and search do not replace it, and this change
  touches neither its scope nor its storage.
- Tests: `EmojiCatalogTest` pins the composed forms in the library's data and
  the difference between the code-point count and an emoji count;
  `IconPickerCustomEmojiTest` (desktop) and `IconPickerCustomInputTest`
  (Android) pin the custom field's contract; `ProjectEditorViewModelTest` pins
  that a composed sequence is what the saved project stores (the free-text
  value is pinned one link up, by the picker's own tests on both platforms);
  `ProjectIconEmojiRenderTest` (desktop) and `ProjectIconEmojiRenderTest`
  (Android) pin the rendering per platform.

## Links

- Issues: #2922, parent #2917; siblings #2919, #2920, #2921, #2931, #2932;
  open follow-up #2933
- Decisions: `2026-11-13-emoji-come-from-the-vanniktech-core-artifact.md`
  (the dependency and its limits), `2026-11-13-the-emoji-helpers-have-no-library-equivalent.md`
  (the retained helpers and why), `2026-11-12-project-identity-is-one-icon-composable.md`
  (one icon composable), `2026-11-14-project-icons-are-emoji-or-nothing.md`
  (legacy Material keys stay text), `2026-11-13-the-recently-used-emoji-live-in-the-user-settings.md`
  (the history is the app's)
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/core/emoji/EmojiCatalog.kt`,
  `core-domain/src/commonMain/kotlin/com/singularity/todo/core/emoji/Emoji.kt`,
  `shared/src/commonMain/kotlin/com/singularity/todo/core/text/TextMetrics.kt`,
  `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/components/IconPickerSheet.kt`,
  `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/components/ProjectIcon.kt`
- Tests: `shared/src/commonTest/kotlin/com/singularity/todo/core/emoji/EmojiCatalogTest.kt`,
  `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/projects/IconPickerCustomEmojiTest.kt`,
  `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/IconPickerCustomInputTest.kt`,
  `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/projects/presentation/components/ProjectIconEmojiRenderTest.kt`,
  `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/ProjectIconEmojiRenderTest.kt`,
  `shared/src/jvmTest/kotlin/com/singularity/todo/feature/projects/ProjectEditorViewModelTest.kt`
