---
title: "sgate run-all vs static-gates.sh — architecture and relationship"
date: 2026-10-14
tags: [sgate, cli, ci, architecture]
---

## Context

`sgate` is a Click-based CLI that wraps all project gates. `scripts/ci/static-gates.sh` is the
historical bash registry that drives the CI `static` job. After PRs #570–#571, both exist and
need to remain consistent.

## Decisions

### 1. `static-gates.sh` is a pure registry; `sgate run-all` is the executor

`static-gates.sh` must contain **only** gate definitions (name, description, command, mode).
It is sourced by `sgate run-all` to populate the gate registry. It must not contain
gate-checking logic — that belongs in Python.

The script may also define **helper functions** (`digest_and_sizes`, `digest_no_double_suffix`,
`adr_dry_run`) that `sgate run-all` sources with `STATIC_GATES_SOURCED=1`. These helpers are
pure functions with no side effects.

### 2. CI `static` job calls `sgate run-all || exit 1`

The CI workflow replaced `bash scripts/ci/static-gates.sh` with `sgate run-all || exit 1`.
This eliminates duplication: one tool owns CLI parsing, JSON output, and exit code aggregation.

### 3. `static-gates.sh` must guard against `main()` on source

The sourcing guard uses `STATIC_GATES_SOURCED` environment variable:

```bash
if [[ "${STATIC_GATES_SOURCED:-}" != "1" ]]; then
  main "$@"
fi
```

This prevents `main()` from running when `static-gates.sh` is sourced by `sgate run-all`
to read the registry.

### 4. `$0` vs `${BASH_SOURCE[0]}` for script path resolution

The repo root is computed as:

```bash
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
```

`${BASH_SOURCE[0]}` is used instead of `$0` because:
- When **executed** (`bash static-gates.sh`): both work
- When **sourced** (`source static-gates.sh`): `$0` is the shell (`bash`), not the script path

The `cd` to the resolved directory means all relative paths in gate commands work correctly
regardless of the current working directory.

### 5. Gate commands run with `cwd=str(repo_root)`

Every gate subprocess is launched with `cwd=str(sgate.repo.ROOT)`, ensuring all relative
paths resolve from the repository root — not from the current working directory.

## Consequences

- Gate metadata (name, description, mode) lives in one place: `static-gates.sh`
- `sgate run-all` is the single source of truth for execution behavior
- CI configuration is simplified: one command, not a bash script with inline logic
- Local developers can still run `bash scripts/ci/static-gates.sh --verbose` for the same
  behavior as before, without needing a Python environment

## Links

- PR #570: Initial `sgate run-all` implementation
- PR #571: KIWI_UNAVAILABLE pattern, `_cmd_not_found` marker
- `scripts/ci/static-gates.sh`
- `scripts/src/sgate/commands/run_all.py`
