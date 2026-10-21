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
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "run-maestro-e2e.py"


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


if __name__ == "__main__":
    unittest.main()
