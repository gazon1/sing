#!/usr/bin/env python3
"""Unit tests for sgate.gates — gate registry loader."""
from __future__ import annotations

import unittest
from pathlib import Path

import sgate.gates


class TestParseGateLine(unittest.TestCase):
    """Tests for _parse_gate_line (exercised via load_gates)."""

    def test_loads_all_gates_from_static_gates_sh(self):
        """load_gates() returns a non-empty list of Gate objects."""
        gates = sgate.gates.load_gates()
        self.assertIsInstance(gates, list)
        self.assertGreater(len(gates), 30, "Expected 30+ gates in static-gates.sh")

    def test_gate_names_are_unique(self):
        """No duplicate gate names in the registry."""
        gates = sgate.gates.load_gates()
        names = [g.name for g in gates]
        self.assertEqual(len(names), len(set(names)), "Duplicate gate names found")

    def test_all_gates_have_valid_mode(self):
        """Every gate has mode 'blocking' or 'advisory'."""
        for gate in sgate.gates.load_gates():
            self.assertIn(gate.mode, ("blocking", "advisory"), gate.name)

    def test_all_gates_have_command(self):
        """Every gate has a non-empty command string."""
        for gate in sgate.gates.load_gates():
            self.assertIsNotNone(gate.command)
            self.assertGreater(len(gate.command), 0, f"{gate.name} has empty command")

    def test_gate_objects_are_named_tuples(self):
        """Gate objects have the expected NamedTuple fields."""
        gates = sgate.gates.load_gates()
        self.assertGreater(len(gates), 0)
        g = gates[0]
        self.assertEqual(g.name, "version catalog has no literals")
        self.assertEqual(g.mode, "blocking")
        # command is reconstructed from GateSpec: <python3-exe> <script> <args...>
        # sys.executable varies by installation; check the script path instead
        self.assertIn(
            "scripts/build-version-catalog-gate.py",
            g.command,
            f"expected command to contain script path, got: {g.command!r}",
        )
        self.assertIsInstance(g.kiwi_required, bool)

    def test_sabotage_test_default_is_name(self):
        """sabotage_test defaults to the gate name when not specified."""
        gates = sgate.gates.load_gates()
        for g in gates:
            self.assertEqual(g.sabotage_test, g.name)


class TestGateRegistryHasExpectedGates(unittest.TestCase):
    """Sanity-check that specific important gates are present."""

    def test_kiwi_availability_gate_exists(self):
        """Kiwi gates were removed in the TCMS stand retirement.

        The stand (infra/kiwi/) was deleted; all kiwi_* gates are gone.
        This test now verifies the opposite: no gate with 'kiwi' in its name.
        """
        gates = sgate.gates.load_gates()
        names = [g.name for g in gates]
        kiwi_gates = [n for n in names if "kiwi" in n.lower()]
        self.assertEqual(
            kiwi_gates, [],
            f"unexpected Kiwi gates still registered: {kiwi_gates}",
        )

    def test_traceability_gate_exists(self):
        """The traceability bridge gate is registered."""
        gates = sgate.gates.load_gates()
        names = [g.name for g in gates]
        self.assertIn("speckit scenarios current", names)

    def test_adr_config_sync_gate_exists(self):
        """ADR corpus rules gate is registered."""
        gates = sgate.gates.load_gates()
        names = [g.name for g in gates]
        self.assertIn("ADR corpus rules are shared", names)

    def test_blocking_and_advisory_gates_both_present(self):
        """Registry has at least one blocking and one advisory gate."""
        gates = sgate.gates.load_gates()
        modes = {g.mode for g in gates}
        self.assertIn("blocking", modes)
        self.assertIn("advisory", modes)


if __name__ == "__main__":
    unittest.main()
