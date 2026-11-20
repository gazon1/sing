---
title: "compose-preview CLI spike on the current KMP build — PARTIAL"
date: 2026-11-20
status: accepted
tags: [compose, preview, tooling, spike]
issue: 3061
---

## Context

Issue #3061 asked whether the upstream `compose-preview` CLI
(https://github.com/yschimke/compose-ai-tools) can discover and render this
repo's existing `@Preview` functions on the current build — Kotlin 2.4.10,
Gradle 9.9.1, AGP 9.5.1, Compose Multiplatform 1.12.2, `:shared` as an
Android KMP library with an Android and a JVM target — on **both** an
Android/Robolectric and a JVM Desktop backend, without touching production
source sets or adding runtime dependencies.

The goal is a tighter visual feedback loop: change a composable, render the
existing preview, look at the PNG, fix what looks wrong. All 83 previews live
in `:shared` (`src/commonMain`), use the AndroidX `@Preview` annotation, and
reuse `PreviewSamples` / `PreviewThemed` (ADR
`docs/decisions/2026-09-06-compose-previews.md`).

Out of scope by the issue's own words: pinning the upstream Gradle plugin,
changing any Kotlin/Gradle/AGP/Compose/SDK version, rewriting previews, a
CI job, screenshot baselines, Roborazzi, or a new MCP server.

### Phase 1 — what was actually measured

CLI: **`compose-preview` 2.101.1** (upstream plugin `v2.101.0`, injected as a
runtime Gradle init script at `~/.cache/composeai/init/2.101.0/`; the installer
skips 2.102.0 as "not ready for CLI + Maven use"). No repository build file was
changed to make any of this work.

Environment check (`compose-preview doctor`): PASS with 4 warnings — plugin
auto-injected into 7 modules, Gradle 9.9.1 on JDK 25, AGP 9.5.1 officially
supported only up to JDK 21, `:androidApp` render task forks JDK 21, `:androidApp`
has no Compose BOM, `dl.google.com` answers 302.

Discovery (`compose-preview list`): **83 `@Preview` functions in `:shared`**
`commonMain`, e.g. `…feature.tasks.presentation.components.TaskCardKt.TaskCardLightPreview`,
`…core.ui.components.EmptyStateKt.EmptyStateLightPreview`,
`…feature.agenda.presentation.screen.AgendaContentKt.AgendaContentLoadedPreview`.
`:desktopApp` and `:androidApp` have no previews of their own.

**Desktop (Skia) backend — WORKS.**

```bash
compose-preview render --module :shared --preview TaskCardLightPreview
compose-preview render --module :shared --preview AgendaContentLoadedPreview
```

Gradle tasks: `:shared:composePreviewDiscover`, `:shared:composePreviewRender`
("1 capture(s) drawn on a warm renderer (no per-capture JVM fork)") — 44
actionable tasks in total, of which 8 executed.

| Preview | Module | Backend | Wall clock | Max RSS of the CLI process | Artifact |
|---|---|---|---:|---:|---|
| `TaskCardLightPreview` | `:shared` | Desktop (Skia) | 6 s warm / 48 s cold | ~310 MB | `TaskCardLightPreview-32034185.png`, 1050×164, 13.0 KB |
| `AgendaContentLoadedPreview` | `:shared` | Desktop (Skia) | 12 s | ~340 MB | `AgendaContentLoadedPreview-51b13494.png`, 1050×2100, 84.2 KB |

Both PNGs are valid, inspectable images. A failure is distinguishable from a
success: the CLI exits 0 and writes the PNG, or it exits non-zero and writes
`<name>.png.error.json` (schema `compose-preview-error/v1`) with the stack trace.

**Android (Robolectric) backend — BLOCKED, three stacked causes.**

How a KMP-Android module picks its lane (read from the upstream plugin source,
`AndroidPreviewSupport.kt`): it takes the Robolectric lane only when it **both**
opts in (`kmpAndroidRobolectric`) and declares a host-test compilation; opting in
without a host test warns and falls back to Desktop. `:shared` declares
`withHostTest { }` but does not opt in, which is why the default render above is
the Desktop lane. Plugin declaration order does not decide it.

Forced with `--gradle-arg=-PcomposePreview.kmpAndroidRobolectric=true`.

1. *Robolectric API drift.* The upstream renderer
   (`ee.schimke.composeai:renderer-android:3.28.x`, class
   `SharedNativeRuntimeLoader`) reflectively calls
   `DefaultNativeRuntimeLoader.getDeclaredMethod("maybeCopyFonts", TempDirectory.class)`.
   Verified with `javap`: Robolectric **4.17** has
   `private void maybeCopyFonts(TempDirectory)`, Robolectric **4.18** changed it
   to a no-arg `maybeCopyFonts()` → `NoSuchMethodException`. This repo pins
   `robolectric = "4.18"` (`gradle/libs.versions.toml`, used at
   `shared/build.gradle.kts` `add("androidHostTestImplementation", libs.robolectric)`).
   No upstream issue exists for this breakage yet.
2. *Missing merged R classes.* Without `isIncludeAndroidResources = true` in
   `withHostTest { }`, the render reaches Robolectric and dies with
   `NoClassDefFoundError: androidx/lifecycle/runtime/R$id`. Upstream issue #5375
   states the plugin cannot flip that flag for a module that already wrote
   `withHostTest { }` — the consumer has to.
3. *Manifest merge conflict.* With the flag on, `:shared:mergeAndroidHostTestManifest`
   fails: `activity#androidx.activity.ComponentActivity@exported` is `false`
   (from `shared/src/androidHostTest/AndroidManifest.xml`) versus `true` (from the
   injected `androidx.compose.ui:ui-test-manifest`, both 1.13.0-beta02 and 1.9.5).
   `:androidApp` fails the same way, earlier, in `mergeDebugUnitTestManifest`.

Forcing Robolectric 4.17 on the render classpath (throwaway init script in
`/tmp`, never in the repo) got the render all the way into actual Robolectric
execution, where only cause 2 remained. With cause 2 also worked around, the
render still failed — at the manifest merger, i.e. cause 3.

## Idea

Three options were weighed.

1. **Adopt the CLI as a local tool, keep the upstream renderer.** No build-file
   change, no version pin, no runtime dependency; the CLI injects its plugin
   through a runtime init script.
2. **Pin the upstream Gradle plugin** (`ee.schimke.composeai.preview`) in the
   project's build files. More reproducible for teammates, but it adds a
   version-pinned plugin to every build — which the issue explicitly puts out of
   scope unless a decision proves the CLI route insufficient.
3. **Drop the spike** and keep using Android Studio / device runs.

## Decision

**PARTIAL.** Keep the CLI route and the Desktop lane as a local, documented
workflow; record the Android/Robolectric lane as blocked rather than working
around it.

- The project-local skill `singularity-todo-compose-preview-cli` is added
  (`.agents/skills/singularity-todo-compose-preview-cli/SKILL.md`), with a
  pointer to it from the `singularity-todo-compose-overview` router and an
  entry in the regenerated `docs/SKILLS-CATALOG.md`.
- The upstream Gradle plugin is **not** pinned in any build file, and no
  Kotlin/Gradle/AGP/Compose/Robolectric version is changed.
- The Android lane stays blocked. Its three causes are recorded, not patched:
  the Robolectric 4.18 incompatibility is upstream's to fix (the renderer must
  tolerate Robolectric's method-signature change), while the `isIncludeAndroidResources`
  flag and the `ComponentActivity@exported` manifest conflict are this repo's
  to fix **in a separate decision** — both touch the shared test build, and
  neither is needed for the Desktop lane that works today.
- A GO verdict here would not authorize CI integration or screenshot baselines;
  this verdict does not authorize them either.

## Rationale

- **The Desktop lane is real and cheap.** Two existing previews rendered to
  inspectable PNGs in 6 s and 12 s with no repository change at all. That
  already shortens the feedback loop for layout, theme and spacing work, which
  is most of what a Compose edit gets wrong.
- **A green Android lane would have to be faked.** Every remaining blocker
  requires either a Robolectric downgrade (touches the existing `:shared` test
  suite), a test-build manifest edit (touches shared test infrastructure), or a
  pinned upstream plugin (explicitly out of scope). Making the spike look green
  by shipping those would hide a real upstream incompatibility behind local
  workarounds — exactly what the issue forbids.
- **PARTIAL is the honest verdict.** The issue defines PARTIAL as "one backend
  works; document the limitation and keep the working local workflow", which is
  exactly the situation.
- **Documentation over workaround.** The upstream renderer is used as-is; the
  repo-local skill is a note on top of the upstream workflow, not a fork.

## Consequences

- Agents and developers can render a `:shared` preview to PNG without Android
  Studio: `compose-preview render --module :shared --preview <Name>`.
- A Desktop render says nothing about Android-specific behaviour. Until the
  Android lane is unblocked, a shared-UI change is verified on Desktop only and
  must be reported as unverified on Android.
- The upstream CLI version (2.101.1) is a moving target; the skill documents
  the version that was validated and the update command.
- Re-running the Android lane needs upstream to fix the Robolectric 4.18
  reflective call, or a separate decision to pin Robolectric ≤ 4.17 on the
  render classpath and to resolve the `ui-test-manifest` /
  `androidHostTest` manifest conflict. That decision was deliberately not made
  here.
- No CI job was added and no build-time cost was introduced.

## Links

- Issue: https://github.com/gazon1/sing/issues/3061
- Upstream: https://github.com/yschimke/compose-ai-tools — docs at
  https://yschimke.github.io/compose-ai-tools/
- Upstream issue #5375 (KMP Robolectric lane; `isIncludeAndroidResources` must
  be set by the consumer) — https://github.com/yschimke/compose-ai-tools/issues/5375
- Upstream issues #241 / #242 / #1924 (older discovery issues; not reproduced
  here — discovery found all 83 previews)
- Skill: `.agents/skills/singularity-todo-compose-preview-cli/SKILL.md`
- Related: https://github.com/gazon1/sing/issues/2887 (screenshot regression
  testing — separate)
- Preview conventions: `docs/decisions/2026-09-06-compose-previews.md`
- Spike logs: `build/diagnostics/cp-*.log` (untracked, local only)
