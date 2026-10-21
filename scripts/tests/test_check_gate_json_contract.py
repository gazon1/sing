#!/usr/bin/env python3
"""Unit tests for check-gate-json-contract.py — the JSON contract every gate speaks.

The contract is one shape: **one JSON object on stdout, and nothing else**.
A gate that puts prose in front of its document breaks every consumer that
reads it, and the breakage is silent: the gate is green, the exit code is 0,
and the consumer gets a parse error instead of a verdict.

The detector is what decides "document" from "not a document", so it is tested
against the shapes that actually occur: a clean document, a document behind a
line of prose, no output at all, a JSON array, and a document with no verdict.
"""
from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def _load_module():
    """Load the gate script by path, the way the registry loads its modules."""
    path = ROOT / "scripts" / "check-gate-json-contract.py"
    spec = importlib.util.spec_from_file_location("check_gate_json_contract", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules["check_gate_json_contract"] = module
    spec.loader.exec_module(module)
    return module


mod = _load_module()


class TestContractViolation(unittest.TestCase):
    """What counts as a document, and what does not."""

    def test_clean_document_is_clean(self):
        self.assertIsNone(mod.contract_violation('{"gate": "x", "passed": true}'))

    def test_document_with_fields_is_clean(self):
        payload = json.dumps({"gate": "x", "passed": False, "blocking_errors": ["boom"]})
        self.assertIsNone(mod.contract_violation(payload))

    def test_prose_in_front_is_a_violation(self):
        """The GitHub Actions annotation is exactly this: a line before the document.

        `::error::` is written to stdout, because that is where Actions reads
        workflow commands from — so a gate whose stdout carries a document had
        two claimants for one channel.
        """
        out = "::error::[gate] violation: boom\n{\"gate\": \"x\", \"passed\": false}\n"
        reason = mod.contract_violation(out)
        self.assertIsNotNone(reason)
        self.assertIn("not one JSON document", reason)

    def test_nothing_printed_is_a_violation(self):
        """A gate that printed nothing has not said anything.

        A consumer that reads the document finds nothing to read, and a
        document that says nothing is not a verdict.
        """
        reason = mod.contract_violation("")
        self.assertIsNotNone(reason)
        self.assertIn("printed nothing", reason)

    def test_whitespace_only_is_a_violation(self):
        self.assertIsNotNone(mod.contract_violation("   \n  \n"))

    def test_json_array_is_a_violation(self):
        """The document is an object, not a list of results."""
        reason = mod.contract_violation("[1, 2, 3]")
        self.assertIsNotNone(reason)
        self.assertIn("not an object", reason)

    def test_document_without_a_verdict_is_a_violation(self):
        """A document that does not say whether the gate passed is not a verdict.

        `render-registry --check` printed `{"status": "current"}` until this
        gate was written: a status word, and a consumer would have had to
        infer the verdict from it.
        """
        reason = mod.contract_violation('{"status": "current"}')
        self.assertIsNotNone(reason)
        self.assertIn("passed", reason)

    def test_trailing_line_after_the_document_is_a_violation(self):
        reason = mod.contract_violation('{"passed": true}\nsomething else\n')
        self.assertIsNotNone(reason)


class TestRegistryWalking(unittest.TestCase):
    """The gate probes the gates CI actually runs."""

    def test_finds_the_sgate_dispatched_gates(self):
        gates = mod.sgate_gates()
        self.assertGreater(len(gates), 10, "expected the shell registry to name sgate gates")
        for name, cmd in gates:
            self.assertTrue(cmd.startswith("sgate "), f"{name}: {cmd}")

    def test_ignores_gates_run_as_plain_scripts(self):
        """A `python3 scripts/x.py` gate prints human text and has no contract."""
        gates = dict(mod.sgate_gates())
        self.assertNotIn("gate registries agree", gates)
        self.assertIn("room schema integrity", gates)

    def test_the_gate_does_not_probe_itself(self):
        """A gate that probed itself would launch every gate, and every gate the probe."""
        gates = dict(mod.sgate_gates())
        self.assertNotIn("gates speak the JSON contract", gates)


class TestRealGatesSpeakTheContract(unittest.TestCase):
    """Integration: a real gate, run the way the probe runs it."""

    def test_a_real_gate_emits_one_document(self):
        violation = mod.probe("room schema integrity", "sgate room_schema_integrity")
        self.assertIsNone(violation, f"room schema integrity broke the contract: {violation}")

    def test_the_probe_targets_this_checkout(self):
        """Not the `sgate` on PATH.

        On a machine with two checkouts of this repository the binary on PATH
        is an editable install pointing at the other one, and the gate would
        then certify somebody else's code. The module is run from this
        checkout's `scripts/src` instead.
        """
        argv = mod._probe_argv("room_schema_integrity")
        self.assertEqual(argv[1:3], ["-m", "sgate"])
        self.assertEqual(argv[-1], "--json")
        pythonpath = mod._probe_env()["PYTHONPATH"]
        self.assertTrue(
            pythonpath.endswith("scripts/src"),
            f"the probe must import sgate from this checkout, got {pythonpath!r}",
        )


if __name__ == "__main__":
    unittest.main()
