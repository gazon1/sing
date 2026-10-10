# CI Run 38025951003 Failure Analysis

**Run:** `https://github.com/gazon1/sing/actions/runs/38025951003`
**SHA:** `6838226af9d53c935bb989516d23c307b4fc6bcb` (main, pre-PR-#429-merge)
**Date:** 2026-10-10
**Verdict:** Two pre-existing test bugs surfaced in CI but not locally.

---

## What Failed

| Job | Conclusion | Gate |
|-----|-----------|------|
| Static gates | ❌ failure | `gate scripts' own unit tests` |
| CI gate | ❌ failure | (depends on Static gates) |

```
gates: 28 passed, 1 failed, 7 advisory-failed
  ❌ gate scripts' own unit tests
```

---

## Root Cause 1 — `test_check_gate_honesty.py`

**Symptom:** `FileNotFoundError: [Errno 2] No such file or directory: '/home/runner/work/sing/sing/shared/build/reports/detekt/detekt.md'`

**What happened:**

`FakeDetekt.__call__` (the test double for a real Gradle detekt run) writes a fake report directly to `_mod.REPORT` (`shared/build/reports/detekt/detekt.md`). The `detekt/` subdirectory does not exist in a clean CI checkout — no Gradle task has run yet to create it.

**Why it worked locally:** The developer's machine had a `shared/build/reports/detekt/` directory from previous runs. CI starts from a clean checkout every time.

**Fix:** `scripts/tests/test_check_gate_honesty.py` — added `REPORT.parent.mkdir(parents=True, exist_ok=True)` before writing the fake report.

**Lines changed:** `scripts/tests/test_check_gate_honesty.py:42–47`

---

## Root Cause 2 — `check-kiwi-inventory-ratchet.py`

**Symptom:** `ModuleNotFoundError: No module named 'infra'`

**What happened:**

`load_scanner()` uses `importlib.util.spec_from_file_location()` to load `infra/kiwi/sync.py` without adding `infra/` to `sys.path`. The `sync.py` module itself contains:

```python
sys.path_importer_cache.clear()
import infra  # noqa: F401
```

This `import infra` inside `exec_module()` is supposed to find `infra/__init__.py` already on `sys.path` (or via `ROOT`). However, the combination of:
- `sys.path_importer_cache.clear()` (stale cache entries)
- `importlib.util.spec_from_file_location()` loading from a path outside `sys.path`

…can leave Python unable to locate `infra/__init__.py`, producing the spurious `ModuleNotFoundError`.

**Why it worked locally:** On a developer's machine, `sys.path` often contains `.` (current directory) or the repo root already, so `import infra` resolves. CI's clean environment exposes the path setup gap.

**Fix:** `scripts/check-kiwi-inventory-ratchet.py` — before calling `spec.loader.exec_module(module)`, pre-load `infra` and `infra.kiwi` directly into `sys.modules` using `importlib.util.find_spec()` + `exec_module()`. This eliminates the need for `sync.py`'s own `import infra` to search `sys.path` at all.

**Lines changed:** `scripts/check-kiwi-inventory-ratchet.py:56–94`

---

## Structural Change — `static-gates.sh`

**Problem:** The previous `static-gates.sh` ran gates **sequentially**. When the first blocking gate failed, the script exited immediately — no subsequent gates ran, so developers saw only one error at a time.

**Fix:** `scripts/ci/static-gates.sh` — all 30 gates now run **concurrently** in background subshells. Results are collected after all workers finish, so one run shows **all failures simultaneously**.

```bash
# Before: one fails → script stops → only one error visible
gate() { "$@" || rc=$?; ...; return $rc; }

# After: all run in parallel → all results collected → all errors visible
gate() {
  ( set +e; "$@" >"$out_file" 2>&1; echo $? >"$rc_file" ) &
}
# ... then wait and report all at once
```

**Consequence:** A blocking gate still fails the step (exit 1), but all other gates complete and report their output too.

---

## How to Avoid This

1. **`FakeDetekt` and any test double that writes to disk** — always `mkdir -p` the parent directory before writing, even in a clean environment.

2. **`importlib.util.spec_from_file_location()` loading modules with internal imports** — if the loaded module does `import X`, pre-populate `sys.modules['X']` before `exec_module()` rather than relying on `sys.path` search.

3. **`static-gates.sh` concurrency** — when adding a new gate, remember it runs in a background subshell. Any state must be communicated via temp files, not shell variables in the parent.

---

## Verification

```bash
# Run unit tests
python3 -m unittest discover -s scripts/tests  # expect: Ran 762 tests ... OK

# Run static gates
bash scripts/ci/static-gates.sh               # expect: 30 passed, 0 failed, 7 advisory-failed
```
