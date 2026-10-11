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
        self.assertIn("python3", g.command)
        self.assertIsInstance(g.kiwi_required, bool)

    def test_sabotage_test_default_is_name(self):
        """sabotage_test defaults to the gate name when not specified."""
        gates = sgate.gates.load_gates()
        for g in gates:
            self.assertEqual(g.sabotage_test, g.name)


class TestGateRegistryHasExpectedGates(unittest.TestCase):
    """Sanity-check that specific important gates are present."""

    def test_kiwi_availability_gate_exists(self):
        """'kiwi-availability' is not in static-gates.sh — it is a meta gate."""
        gates = sgate.gates.load_gates()
        names = [g.name for g in gates]
        # The hard-availability check is a native Click command, not a registry entry
        self.assertIn("kiwi inventory did not regress", names)

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
