#!/usr/bin/env python3
"""The environment metadata schema, and the two producers that write it.

Run with: python3 -m unittest scripts.tests.test_environment_metadata

Two runners write down the environment a run happened in: the Android
instrumented runner (`scripts/run-android-ui-tests.py`) and the Maestro
runner (`scripts/src/emulator/maestro/_cli.py`). Their files are read by
different consumers — the evidence manifest copies both side by side — so the
NAMES have to mean one thing each.

The schema is the list below. It is a list of NAMES, and the rule it
enforces is the one the old field names broke: one name, one meaning. A
producer that writes a field the schema does not know is inventing a
contract; a producer that writes an ambiguous name (`apk`) is making every
consumer guess.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS / "src"))

#: The environment metadata schema. Every field a producer writes has to be
#: in here, and every field has to mean exactly one thing:
#:
#: * `kind` — `emulator` or `device`;
#: * `device` — the model of the device;
#: * `serial` — the adb serial;
#: * `api` — the API level;
#: * `commit` — the commit of the checkout that ran;
#: * `apk_filename` — the NAME of the APK file, not a hash;
#: * `app_version` — the versionName of the APK;
#: * `apk_commit_sha` — the GIT SHA the APK was built from (not a sha256);
#: * `shard` / `shards` — which piece of a sharded run this is.
SCHEMA = frozenset(
    {
        "kind",
        "device",
        "serial",
        "api",
        "commit",
        "apk_filename",
        "app_version",
        "apk_commit_sha",
        "shard",
        "shards",
    }
)


def _load(path: pathlib.Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


android_runner = _load(SCRIPTS / "run-android-ui-tests.py", "run_android_ui_tests")

import emulator.maestro._cli as maestro_cli  # noqa: E402  — the path is set above


class _FakeDevice:
    """The four device facts the Maestro runner reads."""

    def device_api_level(self, serial: str) -> int:
        return 36

    def device_model(self, serial: str) -> str:
        return "Pixel 6"

    def apk_sha_from_device(self, serial: str) -> str:
        return "abc1234"

    def git_sha(self, deps) -> str:
        return "abc1234"


class SchemaTest(unittest.TestCase):
    """Both producers, one schema."""

    def test_the_android_runner_writes_only_schema_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "environment.json"
            original = android_runner.ENVIRONMENT_PATH
            android_runner.ENVIRONMENT_PATH = path
            try:
                android_runner.write_environment(
                    {
                        "kind": "emulator",
                        "device": "Pixel 6",
                        "serial": "emulator-5554",
                        "api": "36",
                        "commit": "abc1234",
                        **android_runner.apk_facts(
                            pathlib.Path("androidApp-debug.apk"),
                            "1.0.0+abc1234",
                            "abc1234",
                        ),
                    }
                )
                payload = json.loads(path.read_text(encoding="utf-8"))
            finally:
                android_runner.ENVIRONMENT_PATH = original
        self.assertLessEqual(set(payload), SCHEMA)

    def test_the_maestro_runner_writes_only_schema_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            maestro_cli._write_environment(
                pathlib.Path(tmp),
                "emulator-5554",
                _FakeDevice(),
                shard=2,
                shards=4,
            )
            payload = json.loads(
                (pathlib.Path(tmp) / "environment.json").read_text(encoding="utf-8")
            )
        self.assertLessEqual(set(payload), SCHEMA)

    def test_neither_producer_writes_an_ambiguous_name(self):
        # `apk` meant a file name in one producer and a commit in the other.
        for payload in (self._android_payload(), self._maestro_payload()):
            self.assertNotIn("apk", payload)
            self.assertNotIn("apk_version", payload)

    def test_the_apk_fields_mean_what_they_say(self):
        facts = android_runner.apk_facts(
            pathlib.Path("androidApp-debug.apk"), "1.0.0+abc1234", "abc1234"
        )
        # A file name is a file name.
        self.assertEqual(facts["apk_filename"], "androidApp-debug.apk")
        # The version is the versionName.
        self.assertEqual(facts["app_version"], "1.0.0+abc1234")
        # The commit is the GIT SHA the APK was built from — a sha256 would
        # be 64 hex characters and would answer a different question.
        self.assertEqual(facts["apk_commit_sha"], "abc1234")
        self.assertLess(len(facts["apk_commit_sha"]), 64)

    def test_the_maestro_runner_writes_the_same_apk_commit_name(self):
        payload = self._maestro_payload()
        self.assertEqual(payload["apk_commit_sha"], "abc1234")

    def _android_payload(self) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "environment.json"
            original = android_runner.ENVIRONMENT_PATH
            android_runner.ENVIRONMENT_PATH = path
            try:
                android_runner.write_environment(
                    {
                        "kind": "emulator",
                        "device": "Pixel 6",
                        "serial": "emulator-5554",
                        "api": "36",
                        "commit": "abc1234",
                        **android_runner.apk_facts(
                            pathlib.Path("androidApp-debug.apk"),
                            "1.0.0+abc1234",
                            "abc1234",
                        ),
                    }
                )
                return json.loads(path.read_text(encoding="utf-8"))
            finally:
                android_runner.ENVIRONMENT_PATH = original

    def _maestro_payload(self) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            maestro_cli._write_environment(
                pathlib.Path(tmp),
                "emulator-5554",
                _FakeDevice(),
                shard=2,
                shards=4,
            )
            return json.loads(
                (pathlib.Path(tmp) / "environment.json").read_text(encoding="utf-8")
            )


if __name__ == "__main__":
    unittest.main()
