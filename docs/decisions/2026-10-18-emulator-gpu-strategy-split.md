---
created: 2026-10-18
tags: [emulator, ci, android, e2e]
status: accepted
---

# ADR: Two emulator surfaces, two `-gpu` strategies

## Context

The repository has two CI surfaces that run against an Android emulator:

| Surface | Workflow | Device management | `-gpu` flag |
|---|---|---|---|
| Instrumentation (Espresso) | `android-device-tests.yml` | `python3 scripts/run-android-ui-tests.py` → `python3 -m emulator` | `-gpu swiftshader_indirect` |
| Maestro UI flows | `e2e.yml` | `python3 scripts/run-maestro-e2e.py` → `python3 -m emulator` | `-gpu off` |

Both surfaces were migrated to `scripts/src/emulator` as the single device primitive.
`android-device-tests.yml` was migrated in `a027bd09`. `e2e.yml` was migrated in `cfd0c3f3` (`#727`).

The two surfaces use different GPU flags for different reasons:

- **`-gpu swiftshader_indirect`** (android-device-tests.yml): hardware-accelerated OpenGL via SwiftShader software renderer. Gfxstream bug (`TextureResize` SIGSEGV) is not triggered by Espresso assertions — they read the view hierarchy, not the compositor. Software rendering is the supported configuration for headless runners.

- **`-gpu off`** (e2e.yml): disables the GPU entirely. The comment in `e2e.yml:173-174` states: `"-gpu off sidesteps the gfxstream crash the host emulator hits, so the device-recovery loop of run-maestro is not needed here."` The Maestro driver exercises the UI through the accessibility layer, not the compositor, so no surface is created that would trigger the gfxstream SIGSEGV.

## Problem

The split is a latent risk:

1. **E2E Maestro runs on a different GPU primitive than instrumentation tests.** `e2e.yml` uses `-gpu off` while `android-device-tests.yml` uses `-gpu swiftshader_indirect`. The workaround avoids the crash but also means any UI assertion that exercises composition runs in a configuration that differs from what users see.

2. **SwiftShader performance impact on Maestro flows is unknown.** `-gpu swiftshader_indirect` is slower than `-gpu off`; the impact on flow duration and flakiness has not been measured.

3. **Two failure modes.** Instrumentation tests pass/fail for different reasons than Maestro flows due to the different GPU configurations.

4. **Issue placeholder was unresolved.** The nightly Maestro failure in the previous `e2e.yml` referenced `https://github.com/gazon1/sing/issues/TBD`.

## Decision

**Migrate `e2e.yml` to `scripts/src/emulator`** so both surfaces use the same device primitive and the same recovery loop. This was completed in `cfd0c3f3` (`#727`).

After the migration, both surfaces use `scripts/src/emulator` as the single device primitive. However, the GPU flags remain split:

- **`android-device-tests.yml`**: `-gpu swiftshader_indirect` (instrumentation tests use view hierarchy assertions, not compositor)
- **`e2e.yml`**: `-gpu off` (Maestro drives through accessibility layer; `-gpu off` sidesteps gfxstream crash)

The split is **not yet resolved**. Two options for unification:

### Option A — Adopt `-gpu swiftshader_indirect` for e2e.yml with recovery

Migrate `e2e.yml` from `-gpu off` to `-gpu swiftshader_indirect`, relying on the `emulator` module's device-recovery loop to handle gfxstream crashes transparently.

**Pros:**
- Single GPU primitive across all CI surfaces.
- Crash handled automatically, not avoided.
- Consistent with what users see (hardware composition).

**Cons:**
- SwiftShader rendering is slower; impact on flow duration unknown (tracked in `#722`).

### Option B — Keep `-gpu off` for e2e.yml

Accept the split and formalise it. `-gpu off` is a proven stable configuration for Maestro flows.

**Pros:**
- No migration work; risk-free.
- Faster execution (no SwiftShader overhead).

**Cons:**
- Two GPU primitives means two failure modes.
- Inconsistent with user-facing rendering.

**This decision records that Option A is the direction of travel, but defers the switch** until SwiftShader performance impact on the Maestro suite is measured (see `#722`). `-gpu off` remains the interim state for `e2e.yml`.

## Consequences

- `scripts/ci/e2e-shard.sh` was **deleted** in `cfd0c3f3`. Functionality moved to `scripts/src/emulator`.
- `Maestro/scripts/wait-for-boot.sh` was **deleted** in `cfd0c3f3`. Functionality moved to `scripts/src/emulator`.
- `Maestro/scripts/check-tags.sh` was **deleted** in `cfd0c3f3`. Replaced by `MaestroFlowTagsTest.kt` (JVM, runs in CI as part of `:shared:jvmTest`).
- The `TBD` issue placeholder was resolved by **#721** (E2E smoke instability advisory, closed by `cfd0c3f3`).
- **GPU split remains unresolved** (`#722`, `#723`): e2e.yml still uses `-gpu off`; android-device-tests.yml uses `-gpu swiftshader_indirect`.
- **SwiftShader benchmark pending** (`#722`): three runs with each GPU mode needed before unifying.

## Links

- `android-device-tests.yml` — instrumentation test workflow (migrated to `scripts/src/emulator` in `a027bd09`)
- `e2e.yml` — Maestro flow workflow (migrated to `scripts/src/emulator` in `cfd0c3f3`)
- `scripts/run-maestro-e2e.py` — Maestro entry point for e2e.yml (replaces `e2e-shard.sh`)
- `scripts/src/emulator` — single device primitive for both workflows
- `docs/decisions/2026-09-29-emulator-crash-recovery-runner.md` — why recovery exists
- `docs/decisions/2026-09-28-emulator-gfxstream-colorbuffer-segv.md` — gfxstream crash record
- `a027bd09` — commit that migrated `android-device-tests.yml` to `scripts/src/emulator`
- `cfd0c3f3` — commit that migrated `e2e.yml` to `scripts/src/emulator`
- `#721` — E2E smoke instability (closed by `cfd0c3f3`)
- `#722` — SwiftShader benchmark (open)
- `#723` — Unified EMULATOR_GPU_MODE (open)
