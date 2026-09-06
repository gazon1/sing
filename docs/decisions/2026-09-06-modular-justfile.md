---
title: Modular justfile with .just/ submodules
date: 2026-09-06
slug: modular-justfile
status: accepted
---

## Context

The project has Gradle commands spread across Android (assemble, install, run, test, logcat), Desktop (run, build, package), Shared tests (common, jvm, android-host), DB inspect (pull, schema, tables), and helper scripts (`./check.sh`, `./scripts/ram-bench.sh`, `./scripts/refresh-decisions-digest.sh`, `./scripts/run-android-ui-tests.sh`). AGENTS.md documents many commands ad-hoc with no central task runner.

## Idea

Introduce `just` task runner with the same modular pattern as the reference Flutter project (`/home/max/singularity_clone/singularity_todo_app/justfile`):

- Root `justfile` declares `mod <name> '.just/<name>'` and super-aliases (`aapk`, `drh`, `tcheck`, …).
- Each module is a directory under `.just/` containing `mod.just` that imports per-topic sub-files.
- Every recipe gets `[working-directory(justfile_directory())]` + `#!/bin/bash` body.
- Internal helpers called only from other recipes are marked `[private]`.

Keep all existing `./check.sh` and `./scripts/*.sh` unchanged — `just` recipes call them as-is.

## Decision

Adopt the modular structure below. The `justfile` and `.just/` directory are added to the repo root.

### File layout

```
justfile                              # root: modules, settings, default, aliases
.just/
├── config/   mod.just                # variables only (SERIAL, SKIP_ADB)
├── android/  mod.just
│   ├── build.just                    # build orchestrator PUBLIC; build-debug/release PRIVATE
│   ├── install.just                  # install, run, uninstall, force-stop  PUBLIC
│   ├── device.just                   # devices, logs, test, test-unit        PUBLIC
│   └── db/mod.just                   # db-pull PRIVATE; db-schema/tables PUBLIC
├── desktop/  mod.just
│   ├── build.just                    # build orchestrator PUBLIC; build-run/dist/deb PRIVATE
│   ├── run.just                      # run, run-headless, app-pid           PUBLIC
│   └── db/mod.just                   # db-schema, db-tables PUBLIC
├── tests/    mod.just                # common, jvm, android-host, shared, test, check, clean  PUBLIC
└── scripts/  mod.just                # bench, refresh-decisions, android-ui-tests  PUBLIC
```

### Recipe naming

Recipes in `android/` use `android::<name>` (e.g. `android::build`, `android::install`).
Recipes in `desktop/` use `desktop::<name>` (e.g. `desktop::run`, `desktop::build`).
Recipes in `tests/` use `tests::<name>` (e.g. `tests::check`, `tests::common`).
Recipes in `scripts/` use `scripts::<name>` (e.g. `scripts::bench`).
Private helpers use `android::<helper>` / `desktop::<helper>` prefix (e.g. `android::db-pull`, `desktop::build-run`).

### Unified orchestrators

`android::build VARIANT="debug"` — dispatches `./gradlew :androidApp:assembleDebug` or `assembleRelease`.
`desktop::build TARGET="run"` — dispatches `./gradlew :desktopApp:run`, `createDistributable`, or `packageDeb`.

## Rationale

- Matches the existing reference project so workflow transfers cleanly between repos.
- `just --list` is grep-able and grouped; private helpers are hidden from the menu.
- `[working-directory(justfile_directory())]` on every recipe makes modules robust regardless of cwd.
- `#!/bin/bash` body + `set -euo pipefail` on multi-step recipes enables full bash idiom.
- Aliases (`aapk`, `drh`, `tcheck`, `db-a`, `db-d`, …) provide one-letter shortcuts for the most common commands.
- Existing `./check.sh` and `./scripts/*.sh` are called as-is — no duplication or modification.

## Consequences

- `just` must be installed (`just 1.57.0` is present in this environment).
- Two new top-level entries added: `justfile` and `.just/`.
- AGENTS.md remains unchanged — its inline `adb`/`sqlite3` commands are still valid escape hatches.
- CI may later call `just tests::check` instead of `./check.sh` — the behavior is identical.
- Recipe names with `::` sub-namespacing (e.g. `android::db::schema`) do not work in `just 1.57.0` — flat names are used instead (e.g. `android::db-schema`).

## Links

- AGENTS.md (Run-loop section)
- `singularity-todo-decisions-workflow` skill
- Reference: `/home/max/singularity_clone/singularity_todo_app/justfile`
