#!/usr/bin/env python3
"""Contract tests for the DeviceDeps seam.

Run with: python3 -m unittest discover -s scripts/tests

Why this file exists
--------------------
``DeviceDeps`` was introduced so tests could substitute fakes. But nothing checked
the contracts crossing it, and the fakes were permissive enough to accept whatever
they were handed. Three bugs shipped green through that hole:

1. ``maestro/_cli.py`` called ``ensure_device(serial) if serial else None``, so an
   unset ``SERIAL`` failed acquisition before adb was ever called.
2. ``_artifacts.py`` and ``_fixtures.py`` called ``deps.adb(serial, "shell", …)``,
   building ``adb <serial> shell …`` — an unknown command to adb. Failure artifacts
   and fixture cleanup silently never ran.
3. The test fakes declared 3 parameters for callables whose real signatures take 4,
   so the mismatch stayed invisible until a flow failed inside a unit test.

A fake that accepts anything cannot catch a caller that violates a contract. These
tests assert the contracts directly, against the real implementations.

See ADR 2026-10-13-ensure-device-acquires.
"""
from __future__ import annotations

import collections.abc
import inspect
import pathlib
import tempfile
import typing
import unittest
import unittest.mock

from emulator import _device, _startup
from emulator.maestro import _artifacts, _fixtures, _provenance, _subprocess
from emulator.maestro import _device as maestro_device
from emulator.maestro._types import DeviceDeps, _default_device_deps

#: The real implementation behind each field. ``_default_device_deps`` wraps a few
#: of these in lambdas that forward ``deps``; the contract is the module function,
#: so that is what is checked.
_REAL_IMPLS = {
    "try_run": _subprocess.try_run,
    "device_alive": _device.device_alive,
    "device_model": maestro_device.device_model,
    "maestro_saw_device_failure": maestro_device.maestro_saw_device_failure,
    "apk_sha_from_device": _provenance.apk_sha_from_device,
    "gradle_install_debug": maestro_device.gradle_install_debug,
    "acquire_device": _startup.acquire_device,
    "adb": _device.adb,
    "adb_on": _device.adb_on,
    "reset_adb_server": maestro_device.reset_adb_server,
    "wait_for_app_resumed": maestro_device.wait_for_app_resumed,
    "cleanup_run_fixtures": _fixtures.cleanup_run_fixtures,
    "capture_artifacts": _artifacts.capture_artifacts,
    "git_sha": _provenance.git_sha,
}


def _annotated_arity(hint) -> int | None:
    """Positional-arity declared by a ``Callable[[A, B], R]`` annotation.

    ``None`` for ``Callable[..., R]``, where the annotation makes no promise.
    """
    if typing.get_origin(hint) is not collections.abc.Callable:
        return None
    args = typing.get_args(hint)[0]
    if args is Ellipsis:
        return None
    return len(args)


class FieldSurfaceTest(unittest.TestCase):
    """The bundle and the reference table must not drift apart."""

    def test_every_field_has_a_real_impl(self):
        self.assertEqual(
            set(DeviceDeps.__dataclass_fields__), set(_REAL_IMPLS),
            "a DeviceDeps field has no entry in _REAL_IMPLS, so its contract is unchecked",
        )

    def test_default_bundle_builds(self):
        deps = _default_device_deps()
        for field in DeviceDeps.__dataclass_fields__:
            self.assertTrue(
                callable(getattr(deps, field)),
                f"DeviceDeps.{field} was not bound by _default_device_deps()",
            )


class SignatureConformanceTest(unittest.TestCase):
    """Every real implementation must accept the arguments the annotation declares.

    Bug 3 was a fake with three parameters standing in for a four-parameter
    callable. Checking the annotation against ``inspect.signature`` makes that
    shape of drift fail here rather than during a real run.
    """

    def test_real_impls_match_declared_arity(self):
        hints = typing.get_type_hints(DeviceDeps)
        problems = []
        for field, declared in hints.items():
            want = _annotated_arity(declared)
            if want is None:
                continue
            impl = _REAL_IMPLS[field]
            params = inspect.signature(impl).parameters.values()
            has_varargs = any(p.kind is p.VAR_POSITIONAL for p in params)
            positional = [p for p in params
                          if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
            if has_varargs or len(positional) >= want:
                continue
            problems.append(
                f"{field}: annotation declares {want} positional arg(s), "
                f"{impl.__module__}.{impl.__name__} accepts {len(positional)}"
            )
        self.assertEqual(problems, [], "\n".join(problems))

    def test_callables_that_take_deps_say_so(self):
        """The four fields that delegate must declare a `deps` parameter.

        Forwarding through the bundle is how these call each other; dropping the
        parameter turns the indirection into a circular import at best.
        """
        for field in ("apk_sha_from_device", "cleanup_run_fixtures",
                      "capture_artifacts", "git_sha"):
            impl = _REAL_IMPLS[field]
            self.assertIn(
                "deps", inspect.signature(impl).parameters,
                f"{field} lost its `deps` parameter",
            )


class _RecordingDeps:
    """A DeviceDeps stand-in that records which adb entry point was used."""

    def __init__(self, serial: str = "emulator-5554"):
        self.serial = serial
        self.scoped: list[tuple[str, tuple[str, ...]]] = []
        self.unscoped: list[tuple[str, ...]] = []

    def adb(self, *args, **kw):
        self.unscoped.append(args)
        return ""

    def adb_on(self, serial, *args, **kw):
        self.scoped.append((serial, args))
        if args[:2] == ("shell", "dumpsys") and "package" in args:
            return "  versionName=1.0.0+gabc123def456"
        if args[:3] == ("shell", "logcat", "-d"):
            return "logcat output"
        return "package:/system/x"

    def device_alive(self, serial):
        return True


class AdbArgvShapeTest(unittest.TestCase):
    """Every device-scoped adb call must go through `adb_on`.

    This is the assertion ``adb=lambda *a, **kw: ""`` cannot make. `adb` is for
    device-independent commands only; a device-scoped call reaching it would be
    built without ``-s <serial>`` and fail silently against the real adb.
    """

    def _assert_scoped_only(self, deps: _RecordingDeps):
        self.assertEqual(
            deps.unscoped, [],
            "device-scoped adb call went through adb(), so it had no -s <serial>",
        )
        self.assertTrue(deps.scoped, "no device-scoped call was recorded at all")
        for serial, args in deps.scoped:
            self.assertEqual(serial, deps.serial)
            self.assertTrue(args, "adb_on called with no arguments")

    def test_capture_artifacts_is_scoped(self):
        deps = _RecordingDeps()
        with tempfile.TemporaryDirectory() as tmp:
            _artifacts.capture_artifacts(
                "emulator-5554", "flow-a", pathlib.Path(tmp), deps
            )
        self._assert_scoped_only(deps)
        self.assertIn(("shell", "screencap", "-p", "/sdcard/flow-a_screen.png"),
                      [args for _, args in deps.scoped])

    def test_cleanup_run_fixtures_is_scoped(self):
        deps = _RecordingDeps()
        _fixtures.cleanup_run_fixtures("emulator-5554", "r1", "run", deps)
        self._assert_scoped_only(deps)
        self.assertTrue(any("todo-debug://seed?cleanup=" in args[-1]
                            for _, args in deps.scoped))

    def test_apk_sha_from_device_is_scoped(self):
        deps = _RecordingDeps()
        self.assertEqual(_provenance.apk_sha_from_device("emulator-5554", deps),
                         "abc123def456")
        self._assert_scoped_only(deps)

    def test_maestro_device_helpers_are_scoped(self):
        """device_model targets a specific device through the scoped entry point.

        It does not take ``deps`` — it binds ``adb_on`` at import — so the
        assertion is made against that binding instead.
        """
        captured: list[tuple[str, tuple[str, ...]]] = []

        def spy(serial, *args, **kw):
            captured.append((serial, args))
            return "Pixel_8"

        with unittest.mock.patch.object(maestro_device, "adb_on", spy):
            self.assertEqual(maestro_device.device_model("emulator-5554"), "Pixel_8")
        self.assertEqual(captured,
                         [("emulator-5554", ("shell", "getprop",
                                             "ro.product.model"))])


class AdbOnTest(unittest.TestCase):
    """`adb_on` itself: it must scope, and it must refuse to guess."""

    def test_injects_dash_s(self):
        captured: list[list[str]] = []

        def fake_try_run(args, *, timeout=5):
            captured.append(args)
            cp = unittest.mock.Mock()
            cp.returncode = 0
            cp.stdout = "ok\n"
            return cp

        with unittest.mock.patch.object(_device, "try_run", fake_try_run):
            self.assertEqual(_device.adb_on("emulator-5554", "shell", "echo", "hi"),
                             "ok")
        self.assertEqual(captured, [["adb", "-s", "emulator-5554",
                                     "shell", "echo", "hi"]])

    def test_empty_serial_raises_instead_of_running_unscoped(self):
        with self.assertRaises(ValueError):
            _device.adb_on("", "shell", "echo", "hi")

    def test_device_alive_rejects_empty_serial(self):
        self.assertFalse(_device.device_alive(""))


if __name__ == "__main__":
    unittest.main()