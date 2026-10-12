---
title: "sgate exit-code and exception-handling architecture"
date: 2026-10-14
status: accepted
tags: [sgate, cli, exit-codes, error-handling]
---

## Context

`sgate` gates are external processes (bash scripts, Python scripts) that exit with specific codes.
The sgate shim must translate those into structured output and its own exit code. Several
architectural decisions govern this translation.

## Exit Code Map

| External exit | Meaning | sgate exit | Rationale |
|---|---|---|---|
| 0 | gate passed | 0 | clean pass |
| 1 | gate violation | 1 | user-facing failure |
| 2 | I/O error | 2 | infrastructure error |
| 4 | kiwi unavailable | 4 | KIWI_REQUIRED gate; skip is expected |

**Not used by generated shims**: 3 (config error), 5 (internal error) — these are reserved
for the Python layer.

## Decisions

### 1. `except SystemExit: raise` — propagate deliberate exits

All generated shims use:

```python
except SystemExit:
    raise  # propagate deliberate exits from run()/run_capture()
except Exception:
    logger.exception("unexpected error")
    raise SystemExit(INTERNAL_ERROR)
```

`SystemExit` is **re-raised**, not caught, because:
- `gate_fail()` calls `sys.exit(1)` — a deliberate user-facing failure
- `run_capture()` raises `SystemExit` on `FileNotFoundError` (KIWI_REQUIRED gates)
- These are intentional control flow; catching them as infrastructure errors would conflate
  "gate failed" with "something broke"

`Exception` is caught separately to handle unexpected errors (bugs in the shim itself) without
masking deliberate exits.

### 2. `to_stderr=True` for human-readable output in JSON mode

When a gate runs in `--json` mode and fails, the error message is written to **stderr**:

```python
gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}", to_stderr=True)
```

Rationale: the JSON object is written to **stdout** via `print(json.dumps(data))`. If the
human-readable error also went to stdout, the JSON would be interleaved with error text,
breaking `jq` / `json.load` downstream. Sending errors to stderr keeps stdout clean for
structured output.

### 3. KIWI_UNAVAILABLE: `run_capture` returns `CompletedProcess(127)` instead of raising

For KIWI_REQUIRED gates, `run_capture()` catches `FileNotFoundError` and returns:

```python
raise SystemExit(KIWI_UNAVAILABLE)  # exits 4, NOT 127
```

**Not** raising `FileNotFoundError` because:
- `FileNotFoundError` is infrastructure, not a gate result — it says "the tool is missing",
  not "the gate failed"
- Raising would make it impossible for callers to distinguish "kiwi absent" (expected skip)
  from "something broke in the runner"
- Returning a sentinel `CompletedProcess` with `._cmd_not_found = True` lets the shim decide
  what exit code to use

The shim checks `result._cmd_not_found` and exits 4 (skip) instead of propagating 127.

### 4. `KIWI_REQUIRED` gates are marked in the registry

Kiwi-dependent gates are flagged in `static-gates.sh` with the command going through the sgate
shim (e.g., `sgate scenario_specs_valid`), ensuring the KIWI_UNAVAILABLE logic applies regardless
of whether the gate is invoked directly or via `run-all`.

This routes through the sgate shim rather than calling `traceability` directly, so:
- If `traceability` is absent: sgate exits 4 (skip) instead of 127
- If `traceability` runs and fails: the actual gate failure exits 1

### 5. `_cmd_not_found` attribute on `CompletedProcess`

`run_capture()` annotates the returned `CompletedProcess` with `._cmd_not_found = True`
when `FileNotFoundError` is caught. Callers use this to distinguish:

```python
result = run_capture([...], gate_name)
if getattr(result, '_cmd_not_found', False):
    # kiwi/traceability not installed — skip expected
    raise SystemExit(KIWI_UNAVAILABLE)
```

This attribute is never set for normal exits (returncode != 0 or == 0).

## Consequences

- All infrastructure errors (missing tools) result in exit 4, not 127 or 5
- Gate violations always exit 1
- `run-all --json` output is valid JSON on stdout; errors on stderr
- No conflation between "gate failed" and "infrastructure broken"

## Links

- `_runner.py`: `run_capture()`, `_cmd_not_found` marker
- `_generate_shims.py`: `_handle_result()` helper with KIWI_UNAVAILABLE logic
- `static-gates.sh`: kiwi gate registry entries
- PR #571: KIWI_UNAVAILABLE pattern introduced
