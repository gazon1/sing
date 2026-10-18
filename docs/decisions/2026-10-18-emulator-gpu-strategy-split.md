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
| Maestro UI flows | `e2e.yml` | `reactivecircus/android-emulator-runner@v2` + `scripts/ci/e2e-shard.sh` | `-gpu off` |

`android-device-tests.yml` was migrated in `a027bd09` ("ci(android): run the instrumentation job's device through scripts/src/emulator"). `e2e.yml` still uses `reactivecircus/android-emulator-runner`.

The two surfaces use different GPU flags for different reasons:

- **`-gpu swiftshader_indirect`** (android-device-tests.yml): hardware-accelerated OpenGL via SwiftShader software renderer. Gfxstream bug (`TextureResize` SIGSEGV) is not triggered by Espresso assertions — they read the view hierarchy, not the compositor. Software rendering is the supported configuration for headless runners.

- **`-gpu off`** (e2e.yml): disables the GPU entirely. The comment in `e2e.yml:173-174` states: `"-gpu off sidesteps the gfxstream crash the host emulator hits, so the device-recovery loop of run-maestro is not needed here."` The Maestro driver exercises the UI through the accessibility layer, not the compositor, so no surface is created that would trigger the gfxstream SIGSEGV.

## Problem

The split is a latent risk:

1. **E2E Maestro runs on a different device primitive than instrumentation tests.** The `reactivecircus/android-emulator-runner` owns the emulator lifecycle entirely inside the `script:` step of the GitHub Action — it is opaque to the repository's `emulator` module. The startup lock, stale-qemu cleanup, readiness probe, and device-recovery loop are unreachable from CI.

2. **The workaround is fragile.** `-gpu off` avoids the crash but also means any UI assertion that actually exercises composition (e.g. a screenshot test, or a visual regression check) would run in a configuration that differs from what users see.

3. **Maestro flows in `e2e.yml` cannot use device recovery.** When `e2e.yml` boots the emulator via `android-emulator-runner`, the `run-maestro` path that relaunches the AVD after a gfxstream crash is bypassed. The crash is avoided, not handled.

4. **The nightly Maestro failure in e2e.yml is advisory** (line 238 of `e2e.yml`): `"Advisory: E2E smoke instability is tracked in https://github.com/gazon1/sing/issues/TBD"`. The `TBD` is an unresolved placeholder.

## Decision

**Migrate `e2e.yml` to `scripts/src/emulator`** so both surfaces use the same device primitive and the same recovery loop.

The migration requires resolving the gfxstream crash for the Maestro path. Two options:

### Option A — Adopt `-gpu swiftshader_indirect` for e2e.yml with recovery

Migrate `e2e.yml` from `reactivecircus/android-emulator-runner` to `python3 scripts/run-android-ui-tests.py` (same entry point as instrumentation tests), adopting `-gpu swiftshader_indirect`. The `emulator` module's device-recovery loop handles gfxstream crashes transparently.

**Pros:**
- Single device primitive across all CI surfaces.
- Crash handled automatically, not avoided.
- Removes `scripts/ci/e2e-shard.sh` which is tightly coupled to the runner's `script:` execution model.

**Cons:**
- SwiftShader rendering is slower; impact on flow duration unknown.
- The `wait-for-boot.sh` and `e2e-shard.sh` scripts become legacy; cleanup needed.

### Option B — Keep separate surfaces, document the divergence

Accept the split and formalise it with an ADR that records the rationale for each flag and the conditions under which they can be changed.

**Pros:**
- No migration work; risk-free.
- `-gpu off` is a proven stable configuration for Maestro flows.

**Cons:**
- Two device primitives means two failure modes.
- `e2e.yml` remains unable to use device recovery.
- The `TBD` issue placeholder must be resolved.

**This decision records that Option A is the direction of travel, but defers the migration** until SwiftShader performance impact on the Maestro suite is measured. Option B is the interim state.

## Consequences

- `scripts/ci/e2e-shard.sh` is **legacy but still live**. It must not be deleted until `e2e.yml` migrates.
- `Maestro/scripts/wait-for-boot.sh` is **legacy but still live** — called by `e2e-shard.sh` which is called by the still-active `reactivecircus/android-emulator-runner` in `e2e.yml`. It must not be deleted until the migration.
- `Maestro/scripts/check-tags.sh` is **live** — called by `.githooks/pre-push`. Its canonical equivalent is `MaestroFlowTagsTest.kt` (JVM, runs in CI as part of `:shared:jvmTest`).
- The `TBD` issue placeholder in `e2e.yml:238` must be resolved to a real issue before the nightly alert can be actionable.

## Links

- `android-device-tests.yml` — instrumentation test workflow (migrated to `scripts/src/emulator`)
- `e2e.yml` — Maestro flow workflow (still uses `reactivecircus/android-emulator-runner`)
- `scripts/ci/e2e-shard.sh` — shell script called inside `android-emulator-runner script:` step
- `docs/decisions/2026-09-29-emulator-crash-recovery-runner.md` — why recovery exists
- `docs/decisions/2026-09-28-emulator-gfxstream-colorbuffer-segv.md` — gfxstream crash record
- `a027bd09` — commit that migrated `android-device-tests.yml` to `scripts/src/emulator`
