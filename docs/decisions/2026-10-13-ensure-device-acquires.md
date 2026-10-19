---
created: 2026-10-13
tags: [python, testing, emulator]
status: accepted
---

# ADR: `ensure_device` acquires — and the device seam gets a contract test

## Context

`DeviceDeps` (ADR `2026-10-13-device-deps-di-pattern.md`) was introduced so tests could
substitute fakes for device primitives. Three bugs shipped green through that seam,
and a fourth was found while fixing them.

### 1. `run-maestro` could never acquire a device with no `SERIAL`

`maestro/_cli.py` evaluated:

```python
ready = deps.ensure_device(serial) if serial else None
```

An empty `serial` — which is exactly what `tests::gate-maestro` passes, since its
`serial` parameter defaults to `""` — short-circuited to `None` and returned
`EXIT_FAIL` before adb was ever called. Reproduced:

```
$ env -u SERIAL python3 -m emulator.maestro --tags smoke
INFO  No ready device; attempting recovery.
ERROR Could not obtain a device.
```

The only CLI-level test that existed always passed `--serial emulator-5554`, so the
branch was never executed. `just gm agenda` had been dead; nothing asserted it.

Root cause was one level down: `ensure_device` was `return serial if device_is_ready(serial)
else None` — a *re-check*, not an acquisition, despite the name and despite the
crash-recovery ADR describing relaunch behaviour. So the recovery path in `_runner.py`
was broken too: after `reset_adb_server()` it called the same no-op and gave up.

### 2. Device-scoped adb calls were missing `-s <serial>`

`_artifacts.py` (four sites) and `_fixtures.py` (one) called:

```python
deps.adb(serial, "shell", "screencap", "-p", remote)   # → adb emulator-5554 shell …
```

Every other call site used `deps.adb("-s", serial, …)`. adb rejects a bare serial as an
unknown command, so **per-run fixture cleanup and all failure artifacts — screenshot and
logcat — silently never ran** on the local `run-maestro` path. CI never saw it: it runs
under `scripts/ci/e2e-shard.sh` and collects `~/.maestro/tests`.

This survived because the test fake was `adb=lambda *a, **kw: ""`. A fake that accepts
any argv cannot tell a correct call from a malformed one.

### 3. Test fakes did not match the callables they replaced

`capture_artifacts` and `cleanup_run_fixtures` are declared with four parameters and
called with `deps` last; the fakes in `_make_deps` took three. Latent `TypeError`,
reachable the moment a flow failed inside a unit test.

### 4. Both console scripts exited 0 on every failure

Found while writing the process-level test, not by inspection. Click 8's standalone mode
**discards a callback's return value** and exits 0 — its own source says *"it's not safe
to `ctx.exit(rv)` here!"*. Both `main` callbacks returned an int, and the existing comment
in `_cli.py` asserting the opposite was wrong. So `ensure-emulator` and `run-maestro`
exited 0 on every failure, and `gate-maestro` — which runs under `set -euo pipefail` —
could never fail, whatever the flows did.

## Decision

**1. Make the adb contract structural, not conventional.**

`DeviceDeps` gains `adb_on`; `adb` is reserved for device-independent commands
(`devices`, `start-server`, `kill-server`).

```python
def adb_on(serial: str, *args: str, timeout: float = 5) -> str | None:
    if not serial:
        raise ValueError("adb_on requires a serial; use adb() for device-independent commands")
    return adb("-s", serial, *args, timeout=timeout)
```

The `ValueError` matters: it turns "silently wrong device scope" into a loud failure, and
it is what makes the empty-serial case in bug 1 unreachable without being noticed.

**2. `acquire_device(pin) -> Acquisition` is the one way to get a device.**

`Acquisition` is a `NamedTuple` carrying `serial` and `exit_code`, truthy iff a serial was
obtained — so a caller can distinguish crashed from timeout from lock timeout instead of
logging one generic "could not obtain a device". `startup_lock` now has its own
`cfg.lock_timeout` (60 s, not `boot_timeout`), and a waiter that times out does one final
`find_ready` before reporting `EXIT_LOCK`: it blocked for roughly as long as the holder
was booting, so failing there would fail a healthy run by a race.

`start_avd` keeps its `-> int` signature; the serial is re-probed rather than returned,
because changing the signature would touch eight test call sites and a public re-export
for no benefit. It no longer prints — stdout carries the serial and nothing else, so
`SERIAL=$(ensure-emulator)` keeps working.

**3. A booting emulator is given its budget before it is killed.**

`start_avd` previously killed as soon as no device was ready, which killed an emulator
that was mid-boot and guaranteed a failed boot. It now polls for readiness for
`boot_timeout` first. Under the startup lock a concurrent acquirer reuses a healthy device
instead of killing it.

**4. Both CLIs route their verdict through `ctx.exit(rc)`.**

`ctx.exit(rc)` becomes `sys.exit(rc)` under a console script and still returns `rc` for
`standalone_mode=False` callers, so the tests are unaffected.

**5. The seam is verified, not assumed.**

`scripts/tests/test_device_contracts.py` checks each real implementation against its
declared signature via `inspect.signature`, and asserts that device-scoped work reaches
`adb_on` — the assertion `adb=lambda *a: ""` could never make.
`scripts/tests/test_emulator_cli_contract.py` runs the real entry point as a child
process against scripted `adb`/`emulator` stubs on `PATH`, asserting stdout and the exit
status a caller actually observes.

Fakes stay; they are the right tool. They stop being the only thing standing in for a
contract.

## Consequences

- `just gm agenda` works again, and for the first time reports failure. The `DeviceDeps`
  field table in `2026-10-13-device-deps-di-pattern.md` gains `adb_on`, and
  `ensure_device` becomes `acquire_device` returning `Acquisition`.
- Failure artifacts and fixture cleanup work on the local path. Anything asserting they
  were dead in a past run should be re-read.
- **`docs/decisions/2026-10-13-python-closure-capture-testability.md` no longer supports
  "the cold path is exempt from `DeviceDeps`"** — that exemption is why `ensure_device`
  was free to be a re-check with no test demanding otherwise. The exemption caused bug 1.
- Worst case is now explicit: `start_avd` retries `startup_attempts = 3` times, each with
  a full `BOOT_TIMEOUT` window, so the worst case is **3 × `boot_timeout`**, not
  `boot_timeout`. `EMULATOR_TIMEOUT` is really a per-attempt budget. With the 300 s default
  that is ~15 min per acquisition, and with `--maestro-max-retries 2` roughly 48 min per
  flow. Not changed here; exposing `startup_attempts` as `EMULATOR_START_ATTEMPTS` is the
  cheap follow-up.
- Two adjacent bugs fixed because they sat in the blast radius: `sgate/log.py` and
  `emulator/_cli.py` both removed loguru handler 0 unguarded, so whichever imported second
  raised `ValueError` — eleven unrelated `test_sgate_output` errors whenever an emulator
  test ran first under `unittest discover`. Both now use the guarded removal
  `emulator/_loguru.setup_logger` already modelled.

## Deliberately not done

- **`android-device-manager` as a `DeviceDeps` backend.** Real library, but weaker than
  what this layer needs: `Popen(stdout=PIPE)` never read (the emulator runs for hours and
  the 64 KiB buffer fills), a fixed `sleep` instead of a boot wait, `_find_free_emulator_port`
  forcing its own port and breaking `SERIAL=emulator-5556` pinning, and `stop_emulator()`
  unable to see a qemu this process did not start — the recovery case. Last release
  2025-08-25, single maintainer.
- **`AndroidViewClient`/`culebra`.** A UI-automation library, not an AVD lifecycle
  manager: no creation, port allocation, boot-wait or crash recovery.
- **`junitparser` behind `traceability/junit_xml.py`.** Already a dependency, and
  evaluated. Rejected on evidence: `hasattr(TestCase, "file")` is `False`, and `file` is
  the only stable identifier a Maestro flow's result has — `normalize.py` joins on it.
  Adopting it would have traded one silent failure for a louder one. Recorded in the
  module docstring.
- **Typer for `sgate`.** The 45 shims are generated by `_generate_shims.py`, so the
  boilerplate Typer removes is already paid once; and `check-script-conventions.py`
  enforces Click. The cheap 80 % of the benefit — mechanically asserting the `--check` /
  `--json` shape R2.2 requires — is worth taking instead.
- **`ast-grep` for `scanners/test_classes.py`.** The hand-rolled stripper and brace
  counter are a genuine weakness, but the tool is not installed (`/usr/bin/sg` is
  util-linux's setgid runner, a footgun), and the obvious better fix — have the Kotlin
  `RunnableTestMember` emit a manifest — is blocked by `static-gates.sh` being explicitly
  the no-JVM gate registry. Benchmark before adopting.
  - **Premise corrected 2026-10-19.** The "no-JVM gate registry" claim above is wrong:
    `static-gates.sh:181` registers `gate blocking "declared dependencies are used"`,
    and that gate shells out to Gradle (`scripts/check-dependency-usage.py:313` runs
    `./gradlew :shared:printResolvedArtifacts -q`). No gate enforces a JVM-free registry;
    the JVM-free *intent* appears only in comments (`static-gates.sh:2`,
    `.github/workflows/ci.yml:74`, `setup-repo-tools/action.yml:20`), and its real motive
    is speed, not prohibition. The rejection of `ast-grep` therefore does not rest on the
    stated reason. What survives: the tool is not installed, the `/usr/bin/sg` footgun is
    real, and the hand-rolled stripper is a genuine weakness worth measuring. The
    benchmark was run — see ADR
    `2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates.md`, which also supersedes
    this bullet's conclusion that adoption is a question for a future benchmark.

## Links

- `scripts/tests/test_device_contracts.py` — signature and argv-shape conformance
- `scripts/tests/test_emulator_cli_contract.py` — process-level stdout/exit contract
- `scripts/tests/test_ensure_emulator.py::AcquireDeviceTest` — the acquisition table
- ADR `2026-10-13-device-deps-di-pattern.md` — the seam this amends
- ADR `2026-09-29-emulator-crash-recovery-runner.md` — why recovery exists at all
- ADR `2026-09-29-emulator-launch-recipe.md` — the launch flags, unchanged