---
name: singularity-todo-compose-preview-cli
status: active
description: Render an existing @Preview composable in :shared to PNG with the upstream compose-preview CLI. Use when a Compose UI change needs a visual check, or when compose-preview rendering fails.
---

# Compose preview CLI (render previews to PNG)

`compose-preview` (upstream: https://github.com/yschimke/compose-ai-tools) renders
this repo's existing `@Preview` functions to PNG without Android Studio and without
an emulator. It is a **local developer/agent tool only** — no CI, no
screenshot-regression framework, nothing on the production runtime classpath.

This file is the how-to. The authoritative record — what was measured, what is
blocked and why — is the decision note
`docs/decisions/2026-11-20-compose-preview-cli-spike.md`; read it when you need
the numbers or the reasoning.

## Install / update

The CLI is a standalone binary; it is **not** a Gradle dependency of this repo.

```bash
bash <(curl -fsSL https://preview.coo.ee/install.sh) --cli-only   # install or update
compose-preview --version
```

The installer drops the binary into `~/.agents/skills/compose-preview/cli/compose-preview-<version>/bin/`
and links it into `~/.local/bin`. The version validated on this repo was **2.101.1**
(upstream skips releases it marks "not ready for CLI + Maven use").

The CLI drives Gradle through a **runtime init script** it injects itself — no
build file in this repo is changed and no `ee.schimke.composeai` plugin is pinned.
Do not add one; the CLI route is the agreed one.

## Render from the repo root

```bash
compose-preview list                                    # what exists
compose-preview render --module :shared --function TaskCardLightPreview
compose-preview render --module :shared --preview TaskCard          # substring
compose-preview show --json --filter TaskCardLightPreview           # id, path, sha256
```

Selection flags — **the installed binary's own help is the source of truth**, because
the flag set changes between CLI versions: `compose-preview render --help` lists the
selection flags, `compose-preview help --all` lists the general ones (`--gradle-arg`,
`--output`, …). On the validated 2.101.1:

| Flag | Matches |
|---|---|
| `--function <name>` | the exact function name |
| `--id <id>` | the exact preview id |
| `--filter <text>` | a case-insensitive substring of the id |
| `--preview <ref>` | the id, `Class.function`, the bare function name, or a substring of the id |

**None of them is a glob.** `--preview '*TaskCard*Preview'` matches nothing,
because the asterisks are ordinary characters in the search string.

Exit codes: `0` rendered, `1` build error, `2` render failure, `3` no preview
matched. A success prints the PNG path; a failure prints the renderer error and
leaves an `*.error.json` where the PNG would have been.

The loop is: **render → read the PNG → report the path**. Never claim a visual
check you did not make. If the model cannot view images, say so plainly and hand
the PNG path to the user.

## Where the artifacts land

```
shared/build/compose-previews/renders/<PreviewName>-<hash>.png               # the PNG
shared/build/compose-previews/renders/<PreviewName>-<hash>.png.error.json    # a failure
shared/build/compose-previews/previews.json                                  # discovered previews
```

Build outputs — **never commit them**. Inspect a PNG with any image viewer
(`xdg-open <path>`) or with the agent's image reader.

## Backends

**Desktop (Skia) — works today, no repo change.** A KMP-Android module renders on
the Desktop lane unless it opts into Robolectric, so `:shared` lands here:

```bash
compose-preview render --module :shared --function TaskCardLightPreview
```

`:desktopApp` and `:androidApp` have **no previews of their own** — all previews
live in `:shared`. Rendering them *from* those modules needs
`composePreviewSource(project(":shared"))` in their build files; that is a
deliberate change, not part of this workflow. Render from `:shared`.

**Android (Robolectric) — opt in, and currently blocked.** A KMP-Android module
takes the Robolectric lane only when it both opts in and declares a host test:

```bash
compose-preview render --module :shared --function TaskCardLightPreview \
  --gradle-arg=-PcomposePreview.kmpAndroidRobolectric=true
```

`:shared` declares `withHostTest { }`, so the opt-in is what moves it. `--gradle-arg`
forwards the property to the injected plugin (it is a general flag — see
`compose-preview help --all`). The render then fails, for three stacked reasons:

1. the upstream renderer calls a Robolectric method that **4.18** removed
   (this repo pins `robolectric = "4.18"`);
2. `withHostTest { }` lacks `isIncludeAndroidResources = true`, so the merged
   R classes are missing — upstream issue #5375 says the consumer must set it;
3. the AGP manifest merge then fails on `ComponentActivity@exported`.

Do **not** work around these to make a render look green. The causes, their
evidence and the candidate fixes are in the decision note; fixing them is a
separate decision.

## Cost of a render

Seconds, not minutes, and a few hundred MB of RSS in the CLI process — the
measured table is in the decision note. A render is a local developer action, not
something to run on every change.

## Conventions to reuse

Do not invent new ones. Preview conventions — the AndroidX `@Preview` annotation,
where preview functions live, `PreviewSamples`, `PreviewThemed` — are in
`docs/decisions/2026-09-06-compose-previews.md`. How to write a preview that
needs a ViewModel is in the skill `singularity-todo-preview-with-koin`.

## When to render

Render the preview that covers the component you touched, then look at the PNG.
Do not render the whole catalogue; do not make rendering a gate on every change.

**A change in shared UI affects both targets.** The Desktop lane checks layout,
theme and spacing — it says nothing about Android-specific behaviour. Report it
as such: the Android lane was not verified.

## Failure modes worth knowing

| Symptom | Meaning |
|---|---|
| exit 3, "No previews matched" | wrong module or wrong name — `compose-preview list` shows the real names; a glob in `--preview` matches nothing |
| `NoSuchMethodException ... maybeCopyFonts` | the Robolectric 4.18 incompatibility |
| manifest-merge error on `ComponentActivity@exported` | the `ui-test-manifest` conflict |
| `NoClassDefFoundError: androidx/lifecycle/runtime/R$id` | `withHostTest { }` without `isIncludeAndroidResources = true` |
| "declares no host test ... Falling back to the Compose Multiplatform Desktop renderer" | expected on the other KMP modules — they have no `withHostTest` |
