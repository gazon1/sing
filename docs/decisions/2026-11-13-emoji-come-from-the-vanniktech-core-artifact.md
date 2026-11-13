---
title: "Emoji come from the Vanniktech Emoji core artifact through its common API"
date: 2026-11-13
status: accepted
tags: ["emoji", "build", "kmp", "dependencies"]
---

## Context

The parent issue #2917 replaces the app's hand-written emoji infrastructure with
the Kotlin Multiplatform library Vanniktech Emoji. Today that infrastructure is a
20-entry `COMMON_EMOJIS` list in the icon picker plus `String.visibleLength()`;
there is no catalog, no category and no search. #2918 is the gate: prove a stable
published version resolves from `shared/commonMain` for both Android and JVM
Desktop before any call site moves.

The project compiles with Kotlin 2.4.10, Gradle 9.9.0, AGP 9.5.1 and the JVM
toolchain pinned to 21; Android sits at `minSdk` 26.

## Idea

- `com.vanniktech:emoji` 0.24.1 — the newest stable release (2026-03-23), which
  publishes `common`, `androidJvm` and `jvm` variants.
- `com.vanniktech:emoji` 0.23.0 — the previous stable line, still on Java 11.
- A provider artifact (`emoji-google`, `emoji-androidx-emoji2`) instead of the
  core, to get the catalog in one step.

## Decision

The version catalog pins `com.vanniktech:emoji:0.24.1` (Apache-2.0) and `:shared`
declares it as an `implementation` dependency of `commonMain`. No call site is
migrated: `core/emoji/EmojiLibraryApi.kt` only references the common API, and
issue #2919 replaces it with the catalog and search the picker reads.

## Rationale

0.24.1 is the newest release and its floors are met: the JVM and Android jars are
Java 21 class files (the project's toolchain is 21) and `minSdk` 23 is below the
project's 26. Both variants are published, and the metadata jar's package
`com.vanniktech.emoji` is what `commonMain` compiles against.

A provider artifact is what supplies actual emoji data, but neither it nor the
core publishes a provider class in `commonMain`: `GoogleEmojiProvider` lives in
the artifact's `androidMain`/`jvmMain`. Installing one is therefore a platform
concern and belongs to #2919, not to the dependency check.

## Consequences

- `commonMain` resolves the dependency and both `:shared:compileAndroidMain` and
  `:shared:compileKotlinJvm` compile against it; this is the proof #2918 records.
- The Android variant is a full AAR: it carries the library's legacy View widgets
  (`EmojiPopup`, `EmojiEditText`, …), its resources and `appcompat`, `cardview`,
  `recyclerview` and `com.vanniktech:ui-android`. Using only the common API does
  not remove them from the Android build, so the app must keep drawing its own
  Compose picker and never instantiate those widgets.
- `EmojiManager.categories()` and `EmojiManager.findEmoji()` are `internal`; common
  code cannot read the installed provider's catalog from the manager. The public
  routes to the data are `SearchEmojiManager.search(query)` and holding the
  `EmojiProvider` that was installed. #2919 must not plan on `categories()`.
- Provider classes are platform-only, so installing a provider needs a platform
  seam (an `expect`/`actual` with the provider in `androidMain` and `jvmMain`),
  the same shape as `platformModule()`.
- `CharSequence.emojiInformation()` is the current text API
  (`visualLength`, `isOnlyEmojis`, `emojiCount`, `emojiRanges`); the
  `isOnlyEmojis()` / `emojis()` / `emojisCount()` extensions are deprecated.
  `visualLength` answers "visible characters" for emoji, which is the question
  `visibleLength()` answers by code point — #2920 decides whether they are
  interchangeable; this change does not.
- Nothing installs a provider yet, so the library is not on any runtime path;
  `core/emoji/EmojiLibraryApi.kt` is a compile-time probe and #2919 removes it.
- If an Android minimum or a Java floor ever moves past 0.24.1's, 0.23.0 is the
  fallback; the version is read from the catalog, never from a literal.

## Links

- Issues: #2918, parent #2917; follow-ups #2919, #2920, #2921, #2922
- Upstream: <https://github.com/vanniktech/Emoji/releases/tag/0.24.1>
- Files: `gradle/libs.versions.toml`, `shared/build.gradle.kts`,
  `shared/src/commonMain/kotlin/com/singularity/todo/core/emoji/EmojiLibraryApi.kt`
- Decisions: `2026-11-12-project-identity-is-one-icon-composable.md` — its
  "no emoji library" constraint is revisited by #2922, not by this entry.
