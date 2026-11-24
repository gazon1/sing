"""Tests for scripts/run-maestro-e2e.py — the APK the run installs.

The workflow downloads the `e2e-apk` artifact into a *directory*, because
`actions/download-artifact` extracts the archive where it is told and the
archive's internal layout is not ours to predict. The script used to require a
file, so the run reached the install step and stopped with "APK not found:
/…/apk" — a directory that was there, with the APK inside it.

That was invisible until the transport worked: the shard died earlier, on the
missing emulator binary, and never reached the install step.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import subprocess
import tempfile
import unittest
import unittest.mock

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "run-maestro-e2e.py"

#: What an API 35 phone prints for the resumed activity — measured on a
#: realme RMX3938 (0O65321F32204DD5). There is no ``topResumedActivity`` line
#: on this device at all.
API_35_DUMP = (
    "  ResumedActivity: ActivityRecord{5a1b95b u0 "
    "com.singularity.todo/.MainActivity t3273 d0}\n"
)

#: The shape older Android versions print.
LEGACY_DUMP = (
    "  topResumedActivity=ActivityRecord{8f3 u0 "
    "com.singularity.todo/.MainActivity t42}\n"
)


def _load_script():
    """The script has a hyphen in its name, so it is loaded by path."""
    spec = importlib.util.spec_from_file_location("run_maestro_e2e", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ResolveApkTest(unittest.TestCase):
    def setUp(self):
        self.mod = _load_script()
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)

    def test_a_file_is_used_as_it_is(self):
        apk = self.dir / "app-debug.apk"
        apk.write_bytes(b"x")
        self.assertEqual(apk.resolve(), self.mod.resolve_apk(apk))

    def test_a_directory_yields_the_apk_inside_it(self):
        (self.dir / "apk").mkdir()
        apk = self.dir / "apk" / "app-debug.apk"
        apk.write_bytes(b"x")
        self.assertEqual(apk.resolve(), self.mod.resolve_apk(self.dir / "apk"))

    def test_a_nested_apk_is_found(self):
        # download-artifact keeps the archive's own layout, so the APK is not
        # guaranteed to sit at the top of the directory.
        nested = self.dir / "apk" / "debug"
        nested.mkdir(parents=True)
        apk = nested / "app-debug.apk"
        apk.write_bytes(b"x")
        self.assertEqual(apk.resolve(), self.mod.resolve_apk(self.dir / "apk"))

    def test_an_empty_directory_resolves_to_nothing(self):
        (self.dir / "apk").mkdir()
        self.assertIsNone(self.mod.resolve_apk(self.dir / "apk"))

    def test_several_apks_are_ambiguous_and_resolve_to_nothing(self):
        # Picking one of them is how a suite ends up testing the wrong build.
        (self.dir / "apk").mkdir()
        (self.dir / "apk" / "a.apk").write_bytes(b"x")
        (self.dir / "apk" / "b.apk").write_bytes(b"x")
        with contextlib.redirect_stdout(io.StringIO()):
            resolved = self.mod.resolve_apk(self.dir / "apk")
        self.assertIsNone(resolved)

    def test_a_missing_path_resolves_to_nothing(self):
        self.assertIsNone(self.mod.resolve_apk(self.dir / "nowhere"))


class CleanupScopeParityTest(unittest.TestCase):
    """The CI run and the local run must sweep their fixtures the same way.

    The CI driver used to pin ``--maestro-cleanup run`` while the local
    runner defaulted to something else. Both then measured the same tree
    differently, and the difference showed up as a different failure count
    for the same code — the kind of difference that makes a CI number
    untrustworthy rather than informative.

    The scope is therefore not passed at all in CI: one default, one place to
    change it, and the two runs cannot drift apart.
    """

    def setUp(self):
        self.mod = _load_script()

    def test_the_ci_driver_does_not_pin_a_cleanup_scope(self):
        argv = self.mod.maestro_argv(
            serial="emulator-5554",
            shard=1,
            shards=4,
            suite="smoke",
            junit_dir=pathlib.Path("build/maestro-results"),
        )
        self.assertNotIn("--maestro-cleanup", argv)

    def test_the_default_scope_is_the_wide_one(self):
        """The default has to be the scope that isolates the flows.

        Pinning the narrow scope in one place and leaving the default wide in
        another would reproduce exactly the drift this test exists to catch.
        """
        from emulator.maestro import _cli

        default = next(
            param for param in _cli.main.params
            if param.name == "maestro_cleanup"
        )
        self.assertEqual(default.default, "all")


class AppReadinessTest(unittest.TestCase):
    """The readiness check must be able to answer.

    It had a copy of the runner's resumed-activity rule, and the copy knew
    only the ``topResumedActivity=`` shape — the one a modern device does not
    print. It also ran before anything had launched the app, so it could never
    pass: it was a ninety-second sleep per shard that ended in "App not
    confirmed ready".
    """

    def setUp(self):
        self.mod = _load_script()

    @staticmethod
    def _completed(stdout: str = "", returncode: int = 0):
        return subprocess.CompletedProcess(
            args=[], returncode=returncode, stdout=stdout, stderr="",
        )

    def test_the_matcher_is_the_runner_s_own(self):
        """One rule, one owner: the driver borrows the runner's matcher."""
        from emulator.maestro import _device

        self.assertIs(self.mod.resumed_activity_line, _device.resumed_activity_line)
        self.assertFalse(hasattr(self.mod, "_top_resumed_activity"))

    def test_the_api_35_shape_is_recognised(self):
        with unittest.mock.patch.object(
            self.mod, "try_run", return_value=self._completed(API_35_DUMP),
        ):
            self.assertTrue(self.mod.wait_for_app_ready("serial", timeout=5))

    def test_the_legacy_shape_is_recognised(self):
        with unittest.mock.patch.object(
            self.mod, "try_run", return_value=self._completed(LEGACY_DUMP),
        ):
            self.assertTrue(self.mod.wait_for_app_ready("serial", timeout=5))

    def test_another_app_in_front_is_not_the_app(self):
        dump = (
            "  ResumedActivity: ActivityRecord{99 u0 "
            "com.android.settings/.Settings t1 d0}\n"
        )
        with unittest.mock.patch.object(
            self.mod, "try_run", return_value=self._completed(dump),
        ), unittest.mock.patch.object(self.mod.time, "sleep"):
            self.assertFalse(self.mod.wait_for_app_ready("serial", timeout=1))

    def test_the_app_is_started_before_the_wait(self):
        """The check asks whether the app is up; the app has to be started."""
        calls: list[list[str]] = []

        def fake(args, **kwargs):
            calls.append(args)
            if "dumpsys" in args:
                return self._completed(API_35_DUMP)
            return self._completed()

        with unittest.mock.patch.object(self.mod, "try_run", side_effect=fake):
            self.assertTrue(self.mod.ensure_app_ready("serial", timeout=5))

        started = [c for c in calls if "am" in c and "start" in c]
        self.assertTrue(started, calls)
        self.assertLess(
            calls.index(started[0]),
            next(i for i, c in enumerate(calls) if "dumpsys" in c),
        )


class EngineArgvTest(unittest.TestCase):
    """The executor the caller asked for has to reach the runner.

    A job that says which engine it wants and then runs another one is a job
    whose report cannot be read: the verdict would belong to a build nobody
    named.
    """

    def setUp(self):
        self.mod = _load_script()

    def _argv(self, **kwargs) -> list[str]:
        args = {
            "serial": "emulator-5554",
            "shard": None,
            "shards": None,
            "suite": "",
            "junit_dir": pathlib.Path("build/maestro-results"),
        }
        args.update(kwargs)
        return self.mod.maestro_argv(**args)

    def test_the_default_is_the_maestro_cli(self):
        argv = self._argv()
        self.assertNotIn("--engine", argv)

    def test_the_engine_is_named_in_the_argv(self):
        argv = self._argv(engine="maestro-runner")
        self.assertEqual(argv[argv.index("--engine") + 1], "maestro-runner")


class AppCrashEvidenceTest(unittest.TestCase):
    """The dialogs are hidden, so the crash has to be read out of the log.

    `prepare_device` sets `hide_error_dialogs 1` to keep the emulator's own ANR
    windows off the screen. Android hides *every* system error dialog under
    that setting, including the app's own crash and ANR windows — so a flow can
    pass over a crashed process and nothing on screen says so. This is the
    compensating signal, and these tests are what keep it honest.
    """

    def setUp(self):
        self.mod = _load_script()

    def _evidence(self, log: str) -> list[str]:
        return self.mod.crash_evidence_in(log)

    def test_an_app_anr_is_reported(self):
        log = (
            "I/ActivityManager: ANR in com.singularity.todo "
            "(com.singularity.todo/.MainActivity)\n"
        )
        self.assertTrue(
            self._evidence(log),
            "an ANR in the app under test must not be swallowed by the hidden dialog",
        )

    def test_an_app_crash_is_reported(self):
        log = (
            "E/AndroidRuntime: FATAL EXCEPTION: main\n"
            "E/AndroidRuntime: Process: com.singularity.todo, PID 4321\n"
            "E/AndroidRuntime: java.lang.IllegalStateException: boom\n"
        )
        self.assertTrue(
            self._evidence(log),
            "a crash in the app under test must not be swallowed by the hidden dialog",
        )

    def test_the_launcher_anr_the_setting_hides_is_not_reported(self):
        """The setting exists to hide this; reporting it would redden a shard
        for the emulator's slowness rather than for the app."""
        log = (
            "I/ActivityManager: ANR in com.google.android.apps.nexuslauncher\n"
            "I/ActivityManager: ANR in system_server\n"
        )
        self.assertEqual([], self._evidence(log))

    def test_another_app_crash_is_not_reported(self):
        log = (
            "E/AndroidRuntime: FATAL EXCEPTION: main\n"
            "E/AndroidRuntime: Process: com.example.other, PID 99\n"
        )
        self.assertEqual([], self._evidence(log))

    def test_a_crash_early_in_a_long_capture_is_still_reported(self):
        """The collector exists so the buffer cannot roll over a crash: an
        entry at the top of a long capture must still be found at the end."""
        head = (
            "I/ActivityManager: ANR in com.singularity.todo "
            "(com.singularity.todo/.MainActivity)\n"
        )
        noise = "".join(f"I/SomeTag: unrelated chatter line {n}\n" for n in range(50_000))
        self.assertTrue(
            self._evidence(head + noise),
            "a crash at the start of a long shard must survive to the end of the capture",
        )

    def test_a_device_that_does_not_answer_is_not_a_crash(self):
        self.assertEqual([], self._evidence(""))


class CrashLogCollectorTest(unittest.TestCase):
    """The capture has to start at the shard's boundary, not at the device's."""

    def setUp(self):
        self.mod = _load_script()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = pathlib.Path(self.tmp.name) / "shard-logcat.txt"

    def test_the_capture_starts_empty_so_an_earlier_shard_cannot_fail_this_one(self):
        """An emulator reused from an earlier shard must not fail a clean shard
        on the previous shard's crash — blaming code that did not run."""
        self.path.write_text(
            "I/ActivityManager: ANR in com.singularity.todo (stale, previous shard)\n",
            encoding="utf-8",
        )
        with unittest.mock.patch.object(self.mod.subprocess, "Popen") as popen:
            popen.return_value = unittest.mock.Mock()
            collector = self.mod.CrashLogCollector("emulator-5554", self.path)
            with collector:
                pass
            popen.assert_called_once()
            self.assertEqual(
                [], collector.findings(),
                "the collector must not read a crash from before the shard started",
            )

    def test_the_collector_streams_logcat_into_the_file(self):
        with unittest.mock.patch.object(self.mod.subprocess, "Popen") as popen:
            popen.return_value = unittest.mock.Mock()
            collector = self.mod.CrashLogCollector("emulator-5554", self.path)
            with collector:
                argv = popen.call_args.args[0]
            self.assertIn("logcat", argv)
            self.assertNotIn(
                "-d", argv,
                "a one-shot dump is what the collector replaces; it must stream",
            )
            self.assertEqual(self.path, pathlib.Path(popen.call_args.kwargs["stdout"].name))

    def test_a_missing_capture_is_not_a_crash(self):
        collector = self.mod.CrashLogCollector("emulator-5554", self.path)
        self.path.unlink(missing_ok=True)
        self.assertEqual([], collector.findings())


class ShardVerdictTest(unittest.TestCase):
    """The one decision that matters has a behavioural test, not a text search."""

    def setUp(self):
        self.mod = _load_script()

    def test_passing_without_a_crash_passes(self):
        self.assertEqual(self.mod.EXIT_PASS, self.mod.shard_verdict(self.mod.EXIT_PASS, []))

    def test_passing_with_a_crash_fails(self):
        self.assertEqual(
            self.mod.EXIT_FAIL,
            self.mod.shard_verdict(self.mod.EXIT_PASS, ["ANR in com.singularity.todo"]),
            "a shard whose flows passed while the app crashed must not exit 0",
        )

    def test_failing_without_a_crash_stays_failing(self):
        self.assertEqual(self.mod.EXIT_FAIL, self.mod.shard_verdict(self.mod.EXIT_FAIL, []))

    def test_failing_with_a_crash_stays_failing(self):
        self.assertEqual(
            self.mod.EXIT_FAIL,
            self.mod.shard_verdict(self.mod.EXIT_FAIL, ["FATAL EXCEPTION"]),
        )

    def test_another_exit_code_is_preserved(self):
        self.assertEqual(
            self.mod.EXIT_DEVICE_UNAVAILABLE,
            self.mod.shard_verdict(self.mod.EXIT_DEVICE_UNAVAILABLE, []),
        )

    def test_the_runner_returns_the_verdict(self):
        """The decision above is worthless if `main` does not return it."""
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            "return shard_verdict(rc, crashes)",
            source,
            "main must return the shard verdict rather than rc",
        )


if __name__ == "__main__":
    unittest.main()
