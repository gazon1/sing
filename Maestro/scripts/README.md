# Maestro/scripts

## Deleted scripts (2026-10-18)

These scripts have been removed. Their functionality lives elsewhere.

| File | Replaced by |
|---|---|
| `wait-for-boot.sh` | `scripts/src/emulator/_startup.wait_for_device_ready()` — called by `python3 -m emulator` inside `scripts/run-maestro-e2e.py` |
| `check-tags.sh` | `scripts/ci/validate-maestro-selectors.py` — called from `.githooks/pre-push` and `e2e.yml validate-selectors` job |

## Current canonical tools

The single source of truth for device management is `scripts/src/emulator/`:

- `./rm emulator` → `python3 -m emulator._cli`
- `./rm` → `python3 -m emulator.maestro._cli`

The bare console scripts `ensure-emulator` and `run-maestro` are the same two
entry points, but on PATH they are an editable install belonging to whichever
checkout was installed last — the wrapper is what binds them to this checkout.

For Maestro flow validation, use `scripts/ci/validate-maestro-selectors.py` (the blocking gate in CI and pre-push hook).
