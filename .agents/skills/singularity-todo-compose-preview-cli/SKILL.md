---
name: singularity-todo-compose-preview-cli
status: active
description: Render existing @Preview composables to PNG with the upstream compose-preview CLI, without Android Studio and without an emulator. Use after changing Compose UI in :shared — when a visual change needs checking, when a preview should be shown to the user, or when a preview render fails. Covers CLI install and update, rendering from the repo root, where the PNG lands, the Desktop (Skia) and Android (Robolectric) lanes and their current limits, and the PreviewSamples / PreviewThemed conventions to reuse.
---

# Compose preview CLI (render previews to PNG)

`compose-preview` (upstream: https://github.com/yschimke/compose-ai-tools) renders
this repo's existing `@Preview` functions to PNG without Android Studio and without
an emulator. It is a **local developer/agent tool only** — it is not wired into CI,
it is not a screenshot-regression framework, and it adds nothing to the production
runtime classpath.

Upstream ships its own skill bundle (installed by the same installer as the CLI);
this file is the Singularity-specific note on top of that workflow. The upstream
renderer is used as-is — do not fork it.

Decision record: `docs/decisions/2026-11-20-compose-preview-cli-spike.md`.

## Install / update

The CLI is a standalone binary; it is **not** a Gradle dependency of this repo.

```bash
# upstream installer (CLI only)
bash <(curl -fsSL https://preview.coo.ee/install.sh) --cli-only

# check what is installed
compose-preview --version

# update
bash <(curl -fsSL https://preview.coo.ee/install.sh) --cli-only
```

The installer drops the binary into `~/.agents/skills/compose-preview/cli/compose-preview-<version>/bin/`
and links it into `~/.local/bin`. The version validated on this repo was **2.101.1**
(upstream skips releases it marks "not ready for CLI + Maven use" — 2.102.0 was
skipped at the time of the spike).

The CLI drives Gradle through a **runtime init script** it injects itself — nothing
in `build.gradle.kts` is changed, and no `ee.schimke.composeai` plugin is pinned in
the repo. Do not add one: the CLI route is the one this project agreed to.

## Render from the repo root

```bash
# what exists (83 previews in :shared)
compose-preview list

# one preview by function name
compose-preview render --module :shared --function TaskCardLightPreview

# several previews
compose-preview render --module :shared --preview '*TaskCard*Preview'

# id + path + sha256 + changed flag
compose-preview show --json --filter TaskCardLightPreview

# a different output directory (default: <module>/build/compose-previews/renders)
compose-preview render --module :shared --function TaskCardLightPreview --output /tmp/previews
```

Exit codes: `0` rendered, `1` build error, `2` render failure, `3` no preview
matched. A success prints the PNG path; a failure prints the renderer error and
leaves an `*.error.json` next to where the PNG would have been.

The loop is: **render → read the PNG → report the path**. Never claim a visual
check you did not make. If the model cannot view images, say so plainly and hand
the PNG path to the user.

## Where the artifacts land

```
shared/build/compose-previews/renders/<PreviewName>-<hash>.png   # the PNG
shared/build/compose-previews/renders/<PreviewName>-<hash>.png.error.json  # a failure
shared/build/compose-previews/.cli-state.json                     # per-module CLI state
shared/build/compose-previews/previews.json                       # discovered previews
```

These are build outputs — **never commit them**. Inspect a PNG with any image
viewer (`xdg-open <path>`) or with the agent's image reader.

## Backends: Desktop works today, Android/Robolectric is blocked

`:shared` is a Kotlin Multiplatform module. The upstream plugin routes a KMP module
by **plugin order**: `org.jetbrains.compose` applied *before* the KMP/Android
plugins → Desktop (Skia) lane; the other way → Android (Robolectric) lane.

**Desktop lane — works today, no repo change.** `:shared` currently lands here:

```bash
compose-preview render --module :shared --function TaskCardLightPreview
# → shared/build/compose-previews/renders/TaskCardLightPreview-<hash>.png
```

`:desktopApp` and `:androidApp` have **no previews of their own** — all previews
live in `:shared`. To render them *from* those modules you would have to add
`composePreviewSource(project(":shared"))` to their build files; that is a deliberate
change, not part of this workflow. Render from `:shared` instead.

**Android/Robolectric lane — blocked on this repo's Robolectric 4.18.** To force it:

```bash
compose-preview render --module :shared --function TaskCardLightPreview \
  --gradle-arg=-PcomposePreview.kmpAndroidRobolectric=true
```

It fails with:

```
Unable to load shared Robolectric native runtime library from
~/.cache/composeai/robolectric-native:
java.lang.NoSuchMethodException: org.robolectric.nativeruntime.DefaultNativeRuntimeLoader
    .maybeCopyFonts(org.robolectric.util.TempDirectory)
```

Cause: the upstream renderer (`ee.schimke.composeai:renderer-android:3.28.x`) calls
`DefaultNativeRuntimeLoader.maybeCopyFonts(TempDirectory)` reflectively. Robolectric
**4.18** changed that method to a no-arg `maybeCopyFonts()`; **4.17 and older** still
have the `TempDirectory` overload. This repo pins `robolectric = "4.18"`
(`gradle/libs.versions.toml`), so the reflective call cannot resolve.

Two smaller blockers sit behind it and will bite even after the Robolectric version
is settled:

- `shared/build.gradle.kts` has `withHostTest {}` **without** `isIncludeAndroidResources = true`,
  which upstream asks for before a KMP module renders through Robolectric
  (upstream issue #5375: the plugin cannot add it for a module that already
  wrote `withHostTest { }` — the repo has to).
- The AGP manifest merge then fails on `androidx.activity.ComponentActivity`:
  the injected `androidx.compose.ui:ui-test-manifest` declares it `exported=true`,
  while `shared/src/androidHostTest/AndroidManifest.xml` declares it
  `exported=false`. `:androidApp` fails the same way, earlier, in
  `mergeDebugUnitTestManifest`.

Do **not** work around these to make a render look green. Record them; the fix
belongs upstream or in a separate decision. See the decision note for the current
status of each.

## Cost of a render

Measured on this repo (warm Gradle daemon, other builds running concurrently):

| Preview | Backend | Wall clock | Max RSS of the CLI process |
|---|---|---:|---:|
| `TaskCardLightPreview` | Desktop (Skia) | 6 s | ~310 MB |
| `AgendaContentLoadedPreview` | Desktop (Skia) | 12 s | ~340 MB |
| `TaskCardLightPreview` (cold daemon) | Desktop (Skia) | 48 s | — |

The Gradle daemon is a separate process, so the RSS above is the CLI's own tree,
not the whole build. A render is a local developer action, not something to run on
every change.

## Conventions to reuse (do not invent new ones)

- Annotation: `androidx.compose.ui.tooling.preview.Preview` — the JetBrains
  `org.jetbrains.compose.ui.tooling.preview.Preview` does not resolve in the Android
  compile task of this project. ADR `docs/decisions/2026-09-06-compose-previews.md`.
- Preview functions are `private` and live at the **end of the source file**, under
  a `// ===== Preview =====` marker. The renderer discovers private previews fine.
- Theme wrapper: `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/preview/PreviewThemed.kt`
  — `PreviewThemed(darkTheme, accent, fontSizeScale, useSurface)`. Use
  `useSurface = false` when the preview root already contains a `Scaffold`.
- Sample data: `shared/src/commonMain/kotlin/com/singularity/todo/preview/PreviewSamples.kt`
  — epoch-0 `Instant`, fixed `LocalDate(2026, 9, 6)`. Never call `Clock.System.now()`
  in preview code.
- Previews must not start Koin. Use the VM-as-parameter pattern with the fakes in
  `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt` —
  skill `singularity-todo-preview-with-koin`.

## When to render

Render the preview that covers the component you touched, then look at the PNG.
Do not render the whole catalogue; do not make rendering a gate on every change.

**A change in shared UI affects both targets.** The CLI can render the Desktop lane
today, so a Desktop render checks the composable's layout and theme but says
nothing about Android-specific behaviour. Say so explicitly when reporting: the
Android lane was **not** verified. Android behaviour still has to be checked another
way (Maestro flows, a device run) or the change stays unverified on Android.

## Failure modes worth knowing

| Symptom | Meaning |
|---|---|
| exit 3, "No previews matched" | wrong module or wrong function name — `compose-preview list` shows the real names |
| `NoSuchMethodException ... maybeCopyFonts` | the Robolectric 4.18 incompatibility above |
| manifest-merge error on `ComponentActivity@exported` | the `ui-test-manifest` conflict above |
| `NoClassDefFoundError: androidx/lifecycle/runtime/R$id` | `withHostTest { }` without `isIncludeAndroidResources = true` |
| "declares no host test ... Falling back to the Compose Multiplatform Desktop renderer" | expected on the other KMP modules — they have no `withHostTest` |
