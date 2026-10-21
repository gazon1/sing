"""maestro._testing — the FakeMaestro harness for integration testing.

FakeMaestro replaces the subprocess layer (try_run for "maestro test ...") with a
configurable map of flow → result. This lets integration tests cover:

* retry on device failure
* APK provenance mismatch after device relaunch
* --skip-install bypassing the gradle call
* capture_artifacts called on flow failure
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from emulator._device import CommandOutcome


@dataclass
class FakeMaestroResult:
    """Simulates subprocess.CompletedProcess for a single Maestro invocation."""
    returncode: int = 0
    stdout: str = ""
    stderr: str = ""


class FakeMaestro:
    """Simulates Maestro CLI for integration testing.

    Records each ``test(flow_path)`` call in ``calls`` and returns the next
    result from ``results`` (FIFO).

    Usage::

        fake = FakeMaestro([FakeMaestroResult(0)])
        # wire into the device/subprocess layers:
        _subprocess.try_run = lambda cmd, **kw: fake.test(Path(cmd[2]))
        ok, run_id = run_one_flow(flow, serial, ...)
        assert ok
        assert len(fake.calls) == 1
    """

    def __init__(self, results: list[FakeMaestroResult] | None = None):
        self.results: list[FakeMaestroResult] = results or [FakeMaestroResult(0)]
        self.calls: list[Path] = []
        self._device_sha: str | None = None

    def test(self, flow_path: Path, *, timeout: int = 300, **_kwargs) -> FakeMaestroResult:
        """Record the call and return the next result (FIFO)."""
        self.calls.append(flow_path)
        if self.results:
            return self.results.pop(0)
        return FakeMaestroResult(1, stderr="no results configured")

    def set_device_sha(self, sha: str | None) -> None:
        """Override the SHA reported by apk_sha_from_device."""
        self._device_sha = sha




# ── Clock / FakeProc / FakeAdb: emulator boot-cycle harness ─────────────────────


class Clock:
    """Virtual clock for tests — time.sleep / time.monotonic advance instantly."""

    def __init__(self, start: float = 0.0):
        self._t = start
        self._monotonic_offset = start

    def sleep(self, secs: float) -> None:
        self._t += secs
        self._monotonic_offset += secs

    def monotonic(self) -> float:
        return self._monotonic_offset


class FakeProc:
    """Fake background process returned by patched popen().

    Real emulator processes never exit on their own (they run until killed).
    So poll() returns None unless an exit code is queued, in which case it
    returns that code on the next poll and reverts to None afterward.

    poll_returns: list of exit codes returned on successive poll() calls.
    e.g. [1, None] — first poll returns 1 (crash), second returns None (running).
    """

    def __init__(self, poll_returns: list[int | None] | None = None):
        self._poll_returns = poll_returns if poll_returns is not None else []
        self._poll_count = 0

    def poll(self) -> int | None:
        self._poll_count += 1
        if self._poll_returns:
            return self._poll_returns.pop(0)
        return None


class FakeAdb:
    """Simulates an Android device going through the boot cycle.

    Usage in tests::

        fake = FakeAdb(boot_time=5.0)   # device boots after 5 virtual seconds
        with patch_ee(fake):
            result = ee.start_avd("emulator-5554")
            assert result == ee.EXIT_OK
            assert fake.serial == "emulator-5554"
    """

    def __init__(
        self,
        boot_time: float = 5.0,
        poll_returns: list[int | None] | None = None,
    ):
        self.boot_time = boot_time  # virtual seconds until device is ready
        self.poll_returns = poll_returns  # exit codes returned on successive poll() calls
        self.serial: str | None = None
        self.started = False
        self._clock = Clock()
        self._boot_completed = False
        self._pm_responsive = False
        self._sf_responsive = False
        self._unauthorized = False  # if True, get-state returns "unauthorized"
        self._failures: set[str] = set()

    def advance_to_ready(self) -> None:
        """Simulate boot completing — move virtual clock to boot_time."""
        self._clock.sleep(self.boot_time)
        self._boot_completed = True
        self._pm_responsive = True
        self._sf_responsive = True

    def fail_command(self, cmd: str) -> None:
        """Make a specific command return non-zero / None (as if timed out)."""
        self._failures.add(cmd)

    def try_run_detailed(self, args: list[str], *, timeout: float = 5, **kwargs):
        """The same answer as `try_run`, in the shape the device layer wants.

        The device layer has to tell a timeout from a failure — a command that
        did not answer in time says the host was busy, and a command that failed
        says the device is gone. The mock's return code cannot carry that, so
        the conversion is explicit: a command the fake marked as failed is a
        failure, not a timeout.
        """
        cp = self.try_run(args, timeout=timeout, **kwargs)
        code = getattr(cp, "returncode", 1)
        return CommandOutcome(
            code == 0,
            getattr(cp, "stdout", "") or "",
            getattr(cp, "stderr", "") or "",
            code,
            False,
        )

    def try_run(self, args: list[str], *, timeout: float = 5, **kwargs):
        """Returns a Mock CompletedProcess for adb commands, respecting virtual time.

        Advances the virtual clock by 1 s per call to model the passage of real
        time between successive adb probes inside the start_avd polling loop.
        """
        import unittest.mock
        self._clock.sleep(1.0)
        cp = unittest.mock.Mock()
        serial = self.serial or ""

        def bad():
            cp.returncode = 1
            cp.stdout = ""

        # adb devices
        if args == ["adb", "devices"]:
            if serial and self._boot_completed:
                cp.returncode = 0
                cp.stdout = f"List of devices attached\n{serial} device\n"
            else:
                cp.returncode = 0
                cp.stdout = "List of devices attached\n"
            return cp

        # Device-only commands require serial
        if len(args) < 3 or args[0] != "adb" or args[2] != serial:
            cp.returncode = 0
            cp.stdout = ""
            return cp

        cmd = " ".join(args)
        if cmd.endswith("get-state"):
            if not self._boot_completed:
                return bad()
            cp.returncode = 0
            cp.stdout = "unauthorized" if self._unauthorized else "device"
        elif cmd.endswith("getprop sys.boot_completed"):
            if not self._boot_completed:
                return bad()
            cp.returncode = 0
            cp.stdout = "1"
        elif cmd.endswith("pm path android"):
            if not self._pm_responsive:
                return bad()
            cp.returncode = 0
            cp.stdout = "package:/system/framework/framework-res.apk"
        elif cmd.endswith("dumpsys SurfaceFlinger"):
            if not self._sf_responsive:
                return bad()
            cp.returncode = 0
            cp.stdout = "SurfaceFlinger\nGPU mem:"
        else:
            cp.returncode = 0
            cp.stdout = ""
        return cp

    def popen(self, args: list[str], stdout_fd: int | None = None):
        self.started = True
        # Parse -port <N> from emulator args and set serial so adb devices works.
        # e.g. ["emulator", "-avd", "Medium_Phone", "-port", "5554"] → serial="emulator-5554"
        if "-port" in args:
            idx = args.index("-port")
            port = args[idx + 1]
            self.serial = f"emulator-{port}"
        return FakeProc(poll_returns=self.poll_returns)

    def time_sleep(self, secs: float) -> None:
        self._clock.sleep(secs)
        # Simulate boot completing when virtual clock reaches boot_time.
        if self._clock.monotonic() >= self.boot_time and not self._boot_completed:
            self._boot_completed = True
            self._pm_responsive = True
            self._sf_responsive = True

    def time_monotonic(self) -> float:
        return self._clock.monotonic()

