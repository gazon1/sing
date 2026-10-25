#!/usr/bin/env python3
"""Unit tests for the JSON output contract between the sgate adapters and their producers.

An adapter that rebuilds a verdict from the prose of its producer's output is
parsing a human's wording. These tests pin the contract instead: the adapter
must read what the producer says, must not invent a verdict from stray words
on stderr, and must never report green on a producer that failed.

The four adapters covered here are the ones that scrape:

    build_output_readers              scripts/check-build-output-readers.py
    gradle_test_population            scripts/check-gradle-test-population.py
    room_schema_integrity             scripts/check-room-schema-integrity.py
    test_task_external_inputs_declared  scripts/check-test-task-inputs.py
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(Path(__file__).resolve().parent.parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

import sgate.repo
from sgate.commands import base
from sgate.commands.build_output_readers import build_output_readers
from sgate.commands.gradle_test_population import gradle_test_population
from sgate.commands.room_schema_integrity import room_schema_integrity
from sgate.commands.test_task_external_inputs_declared import (
    test_task_external_inputs_declared,
)

#: The repository this test lives in. The root belongs to the script, not to
#: the installed package: an installed `sgate` points at whichever checkout
#: was installed last, and this test has to look at *this* tree's producers.
_ROOT = Path(__file__).resolve().parent.parent.parent

#: adapter command -> the producer script it runs, relative to the repo root.
ADAPTERS = {
    "build_output_readers": (
        build_output_readers,
        "scripts/check-build-output-readers.py",
    ),
    "gradle_test_population": (
        gradle_test_population,
        "scripts/check-gradle-test-population.py",
    ),
    "room_schema_integrity": (
        room_schema_integrity,
        "scripts/check-room-schema-integrity.py",
    ),
    "test_task_external_inputs_declared": (
        test_task_external_inputs_declared,
        "scripts/check-test-task-inputs.py",
    ),
}


def invoke(command, *flags: str) -> tuple[int, str, str]:
    """Run a Click command object; return (exit code, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    code = 0
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        try:
            command.main(args=list(flags), standalone_mode=False)
        except SystemExit as exc:
            code = exc.code if isinstance(exc.code, int) else 1
    return code, out.getvalue(), err.getvalue()


class FakeProducer:
    """A stand-in producer that says exactly what the test tells it to say."""

    def __init__(self, stdout: str = "", stderr: str = "", code: int = 0) -> None:
        self.stdout = stdout
        self.stderr = stderr
        self.code = code

    def install(self, root: Path, script_rel: str) -> None:
        script = root / script_rel
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text(
            "import sys\n"
            f"sys.stdout.write({self.stdout!r})\n"
            f"sys.stderr.write({self.stderr!r})\n"
            f"sys.exit({self.code})\n",
            encoding="utf-8",
        )


class TestAdaptersReadTheContract(unittest.TestCase):
    """The verdict comes from the producer's JSON, not from the wording around it."""

    def _run_with_producer(self, gate: str, producer: FakeProducer, *flags: str):
        command, script_rel = ADAPTERS[gate]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            producer.install(root, script_rel)
            with mock.patch.object(sgate.repo, "ROOT", root):
                return invoke(command, *flags)

    def test_contract_is_read_not_scraped(self):
        """Words on stderr do not become blocking errors; the JSON does.

        The scraping adapters collected every line that started with the
        producer's prefix and filed it as a violation, so a producer that
        mentioned an error in passing was reported as failing. The contract
        says what the verdict is.
        """
        code, out, _err = self._run_with_producer(
            "build_output_readers",
            FakeProducer(
                stdout=json.dumps({"passed": True, "blocking_errors": []}),
                stderr="check_build_output_readers: ERROR: mentioned in passing\n",
            ),
            "--json",
        )
        self.assertEqual(code, base.CLEAN)
        payload = json.loads(out)
        self.assertTrue(payload["passed"])
        self.assertEqual(payload["blocking_errors"], [])

    def test_producer_failure_is_reported_as_failure(self):
        """A producer that says it failed makes the adapter fail, with the reason."""
        code, out, _err = self._run_with_producer(
            "room_schema_integrity",
            FakeProducer(
                stdout=json.dumps(
                    {"passed": False, "blocking_errors": ["[head-agrees] version drift"]}
                ),
                code=1,
            ),
            "--json",
        )
        self.assertEqual(code, base.VALIDATION_FAILURE)
        payload = json.loads(out)
        self.assertFalse(payload["passed"])
        self.assertIn("[head-agrees] version drift", payload["blocking_errors"])

    def test_green_producer_that_failed_is_not_reported_green(self):
        """Exit code and payload are ANDed: either one can veto a pass.

        A producer that exits 0 while saying `passed: false` is a broken
        contract, and the adapter must not turn it into a green gate.
        """
        code, out, _err = self._run_with_producer(
            "gradle_test_population",
            FakeProducer(
                stdout=json.dumps({"passed": False, "blocking_errors": ["boom"]}),
                code=0,
            ),
            "--json",
        )
        self.assertNotEqual(code, base.CLEAN)
        self.assertFalse(json.loads(out)["passed"])

    def test_broken_contract_is_an_internal_error(self):
        """A producer that stops speaking JSON fails the gate loudly.

        It does not pass because the adapter could not find anything wrong,
        and the JSON channel still carries a well-formed report: the consumer
        reads a gate that failed, not a broken document.
        """
        code, out, err = self._run_with_producer(
            "test_task_external_inputs_declared",
            FakeProducer(stdout="not json at all\n", code=0),
            "--json",
        )
        self.assertEqual(code, base.INTERNAL_ERROR)
        payload = json.loads(out)
        self.assertFalse(payload["passed"])
        self.assertTrue(payload["blocking_errors"])
        self.assertIn("JSON contract", err)

    def test_broken_contract_keeps_stdout_clean_in_ci(self):
        """In CI the JSON channel stays clean too.

        The GitHub Actions annotation is written to stdout — that is where
        Actions reads it from — so a gate whose stdout is a JSON contract has
        to switch it off. This test runs with ``CI`` set, because the
        annotation only exists there, and the local run without it proves
        nothing about CI.
        """
        with mock.patch.dict(os.environ, {"CI": "true"}):
            code, out, err = self._run_with_producer(
                "test_task_external_inputs_declared",
                FakeProducer(stdout="not json at all\n", code=0),
                "--json",
            )
        self.assertEqual(code, base.INTERNAL_ERROR)
        self.assertNotIn("::error::", out, "the annotation would corrupt the JSON channel")
        json.loads(out)  # stdout is exactly one JSON document
        self.assertIn("JSON contract", err)

    def test_missing_producer_is_an_io_error(self):
        """A producer that is not there is IO_ERROR, not a silent pass."""
        command, script_rel = ADAPTERS["room_schema_integrity"]
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.object(sgate.repo, "ROOT", Path(tmp)):
                code, _out, _err = invoke(command, "--json")
        self.assertEqual(code, base.IO_ERROR)
        self.assertTrue(script_rel.endswith(".py"))

    def test_check_mode_reports_without_failing(self):
        """--check is a dry run: the verdict is reported, the exit code is 0.

        The flag used to be accepted and ignored, so a caller asking for a
        dry run got a hard failure instead.
        """
        code, out, _err = self._run_with_producer(
            "room_schema_integrity",
            FakeProducer(
                stdout=json.dumps({"passed": False, "blocking_errors": ["boom"]}),
                code=1,
            ),
            "--check",
            "--json",
        )
        self.assertEqual(code, base.CLEAN)
        self.assertFalse(json.loads(out)["passed"])

    def test_check_mode_reports_the_honest_verdict(self):
        """A check-mode report says false about a producer that failed.

        The dry run exists to be read. A report that says "passed" about a
        producer that exited non-zero is the same lie in a different place.
        """
        code, out, _err = self._run_with_producer(
            "room_schema_integrity",
            FakeProducer(
                stdout=json.dumps({"passed": True, "blocking_errors": []}),
                code=1,
            ),
            "--check",
            "--json",
        )
        self.assertEqual(code, base.CLEAN)
        self.assertFalse(json.loads(out)["passed"])

    def test_producer_stderr_is_forwarded(self):
        """The producer's stderr reaches the caller.

        The liveness gate reads stderr to tell "the gate died" from "the gate
        reported a finding". A swallowed traceback makes a crash look like a
        quiet failure — and the JSON channel is stdout, so stderr is free.
        """
        code, _out, err = self._run_with_producer(
            "build_output_readers",
            FakeProducer(
                stdout=json.dumps({"passed": False, "blocking_errors": ["boom"]}),
                stderr="Traceback (most recent call last):\n  boom\n",
                code=1,
            ),
            "--json",
        )
        self.assertNotEqual(code, base.CLEAN)
        self.assertIn("Traceback", err)


class TestRealProducersSpeakTheContract(unittest.TestCase):
    """Integration: the real producers emit the contract the adapters read."""

    def test_each_real_producer_emits_json(self):
        for gate, (command, script_rel) in ADAPTERS.items():
            with self.subTest(gate=gate):
                producer = Path(_ROOT) / script_rel
                self.assertTrue(producer.exists(), f"{producer} is missing")
                code, out, _err = invoke(command, "--json")
                self.assertEqual(code, base.CLEAN, f"{gate} did not pass on a clean tree")
                payload = json.loads(out)
                self.assertTrue(payload["passed"], f"{gate} reported passed=false")
                self.assertEqual(payload["gate"], gate)
                self.assertIn("blocking_errors", payload)
                self.assertEqual(payload["blocking_errors"], [])

    def test_producer_cli_offers_the_json_flag(self):
        """The contract is reachable from the command line, not only in-process."""
        for gate, (_command, script_rel) in ADAPTERS.items():
            with self.subTest(gate=gate):
                producer = Path(_ROOT) / script_rel
                text = producer.read_text(encoding="utf-8")
                self.assertIn(
                    "--json",
                    text,
                    f"{script_rel} does not offer --json, so its adapter has no contract to read",
                )


if __name__ == "__main__":
    unittest.main()
