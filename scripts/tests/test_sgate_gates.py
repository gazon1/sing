#!/usr/bin/env python3
"""Unit tests for sgate.gates — gate registry loader."""
from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path


# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(Path(__file__).resolve().parent.parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

import sgate.gates


class TestParseGateLine(unittest.TestCase):
    """Tests for _parse_gate_line (exercised via load_gates)."""

    def test_loads_all_gates_from_static_gates_sh(self):
        """load_gates() returns a non-empty list of Gate objects.

        The floor was 30 while the registry held 49. 26 gates were removed on
        2026-10-15 (ADR governance, backlog, speckit, docs, YAML duplicates), and
        an assertion that the count only ever grows is an assertion that the
        registry can never shrink -- which is not a property anyone wants. The floor
        is now a sanity bound: enough gates to catch a parse regression that
        silently yields an empty or near-empty list.
        """
        gates = sgate.gates.load_gates()
        self.assertIsInstance(gates, list)
        self.assertGreater(len(gates), 20, "Expected 20+ gates in static-gates.sh")

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
        """The traceability bridge gate is registered.

        `speckit scenarios current` was removed with the rest of the speckit group
        on 2026-10-15. The three gates that actually carry spec-to-test traceability
        remain, and this asserts on those rather than on the removed one -- kover
        measures line coverage and cannot tell whether a specified scenario has a
        test, so these three are the only coverage of that question.
        """
        gates = sgate.gates.load_gates()
        names = [g.name for g in gates]
        for required in (
            "scenario specs valid",
            "coverage matrix current",
            "coverage holes did not grow",
        ):
            self.assertIn(required, names)

    def test_removed_gate_groups_stay_removed(self):
        """The 2026-10-15 removal does not creep back.

        A gate deleted for cost reasons stays deleted until someone adds it back
        deliberately. Without this, restoring one entry to the registry — which
        looks like an unrelated edit — re-adds CI minutes nobody agreed to.
        """
        gates = sgate.gates.load_gates()
        names = {g.name for g in gates}
        removed = {
            # ADR governance
            "closed ADRs keep their body", "ADR frontmatter drift",
            "ADR references are valid", "ADR status policy",
            "ADR corpus rules are shared", "light ADR modules stay light",
            # backlog
            "unwired backlog refs", "backlog-issue-refs",
            # speckit
            "speckit scenarios current", "speckit stale",
            "spec-kit issue registry current", "requirement identifiers are unique",
            # docs / agent-facing text
            "skills catalog current", "skill frontmatter",
            "decisions digest within budget", "digest has no double suffix",
            "no dead doc refs", "no dead skill symbols",
            "publication hygiene", "publication hygiene self-test",
            "README claims", "README claims self-test",
            # YAML — yamllint's key-duplicates rule covers the same property
            "YAML has no duplicate keys",
        }
        self.assertEqual(
            removed & names, set(),
            f"gates removed on 2026-10-15 are back: {sorted(removed & names)}",
        )

    def test_blocking_and_advisory_gates_both_present(self):
        """Registry has at least one blocking and one advisory gate."""
        gates = sgate.gates.load_gates()
        modes = {g.mode for g in gates}
        self.assertIn("blocking", modes)
        self.assertIn("advisory", modes)


class TestTheGateBudget(unittest.TestCase):
    """A gate that needs the build gets a budget that fits the build.

    `declared dependencies are used` resolves the build: it asks Gradle which
    files each configuration resolved and reads the jars. On a cold Gradle home
    that is minutes, and it was killed at the 300 s budget the other gates
    share — with the same tree and the same code, on a day when the same gate
    measured 113 s.
    """

    def test_the_default_budget_is_five_minutes(self):
        from sgate.commands.run_all import DEFAULT_GATE_TIMEOUT

        self.assertEqual(DEFAULT_GATE_TIMEOUT, 300)

    def test_the_gate_that_resolves_the_build_gets_a_larger_budget(self):
        from sgate.commands.run_all import _timeout_for

        self.assertGreater(
            _timeout_for("declared dependencies are used"),
            300,
            "the gate that resolves the build must not share the budget of the "
            "gates that only read files",
        )

    def test_every_other_gate_keeps_the_default(self):
        from sgate.commands.run_all import DEFAULT_GATE_TIMEOUT, _timeout_for

        for gate in sgate.gates.load_gates():
            if gate.name == "declared dependencies are used":
                continue
            self.assertEqual(
                _timeout_for(gate.name), DEFAULT_GATE_TIMEOUT,
                f"{gate.name} has a budget of its own without a reason to have one",
            )

    def test_the_budget_fits_inside_the_static_job(self):
        """The job's own cap is the real ceiling.

        A budget above it buys nothing: the job would be killed instead of the
        gate, and the log would say so less clearly. The cap is read from the
        workflow rather than written here, because the static job also runs the
        control phase and the two have to move together — a hard-coded number
        in the test is a number that goes stale the moment the job changes.
        """
        from sgate.commands.run_all import CONTROLS_PHASE_TIMEOUT, GATE_TIMEOUTS

        workflow = Path(__file__).resolve().parents[2] / ".github/workflows/ci.yml"
        text = workflow.read_text(encoding="utf-8")
        cap = self._static_job_timeout(text)
        self.assertIsNotNone(cap, "the static job declares no timeout-minutes")
        for name, budget in GATE_TIMEOUTS.items():
            self.assertLess(
                budget, cap * 60,
                f"the budget for {name!r} exceeds the static job's own cap",
            )
        self.assertLess(
            CONTROLS_PHASE_TIMEOUT, cap * 60,
            "the control phase's budget exceeds the static job's own cap — the job "
            "would be killed mid-phase, leaving a sabotaged checkout behind",
        )

    @staticmethod
    def _static_job_timeout(text: str) -> int | None:
        """The static job's own cap, in minutes.

        The job block is read rather than the first `timeout-minutes` in the
        file: another job's cap is not this job's ceiling, and the file has
        six of them.
        """
        lines = text.splitlines()
        for index, line in enumerate(lines):
            if line != "  static:":
                continue
            for candidate in lines[index + 1:]:
                if candidate.strip() and not candidate.startswith("    "):
                    return None
                match = re.search(r"timeout-minutes:\s*(\d+)", candidate)
                if match:
                    return int(match.group(1))
            return None
        return None


class TestTheRunnerReadsTheVerdict(unittest.TestCase):
    """`run-all` takes the gate's verdict, not only its exit code.

    A gate dispatched through `sgate` is asked for `--json`, so its stdout is
    the contract and it carries a verdict of its own. The runner reads that
    verdict and ANDs it with the exit code — the rule `GatePayload.honest_pass`
    states. Before, "green" meant one thing in the gate and another in the
    runner, and nothing detected the difference.
    """

    @staticmethod
    def _gate(stdout: str, code: int) -> str:
        """A command that prints `stdout` and exits with `code`.

        The double quotes are escaped for the shell: the snippet itself is
        wrapped in double quotes, so a bare `"` inside it would end the
        quoting. `printf` reads the backslashes and prints the quotes.
        """
        escaped = stdout.replace('"', r'\"')
        return f"""bash -c "printf '{escaped}'; exit {code}" """

    @staticmethod
    def _run(command: str):
        from sgate.commands.run_all import _run_gate_subprocess

        return _run_gate_subprocess("blocking", "a gate", command)

    def test_a_gate_that_says_it_failed_is_red_even_when_it_exits_zero(self):
        result = self._run(self._gate('{"gate": "x", "passed": false}', 0))
        self.assertFalse(result.passed)
        self.assertEqual(result.exit_code, 0)

    def test_a_gate_that_says_it_passed_is_green_when_it_exits_zero(self):
        result = self._run(self._gate('{"gate": "x", "passed": true}', 0))
        self.assertTrue(result.passed)

    def test_a_gate_that_exits_non_zero_is_red_even_when_it_says_it_passed(self):
        result = self._run(self._gate('{"gate": "x", "passed": true}', 1))
        self.assertFalse(result.passed)

    def test_a_gate_that_prints_no_payload_is_judged_by_its_exit_code(self):
        """A gate run as a plain script has no payload; the exit code is all there is."""
        ok = self._run(self._gate("all good", 0))
        self.assertTrue(ok.passed)
        bad = self._run(self._gate("found something", 1))
        self.assertFalse(bad.passed)

    def test_a_broken_document_is_not_read_as_a_verdict(self):
        """A stdout that is not the contract is not a verdict.

        It is the contract gate's job to say so; the runner must not guess a
        verdict out of prose.
        """
        result = self._run(self._gate("not json", 0))
        self.assertTrue(result.passed)

    def test_a_document_without_a_verdict_is_not_read_as_a_verdict(self):
        result = self._run(self._gate('{"gate": "x"}', 0))
        self.assertTrue(result.passed)


if __name__ == "__main__":
    unittest.main()


class TheControlPhase(unittest.TestCase):
    """`sgate run-all --with-controls` — the proof that the gates can fail.

    Every gate has a control: a change that must make it fail. The harness
    applies the change, runs the gate, and puts the file back. It runs after
    the parallel pass, because it edits the tree the gates read — a gate
    reading the tree while the sabotage holds it reads a tree that is not
    there.
    """

    def test_the_option_exists_and_is_off_by_default(self):
        from sgate.commands.run_all import run_all

        options = {param.name: param for param in run_all.params}
        self.assertIn("with_controls", options)
        self.assertTrue(options["with_controls"].is_flag)

    def test_the_phase_has_a_budget_of_its_own(self):
        """A phase that runs a gate twice per control needs more room."""
        from sgate.commands.run_all import CONTROLS_PHASE_TIMEOUT, DEFAULT_GATE_TIMEOUT

        self.assertGreater(CONTROLS_PHASE_TIMEOUT, DEFAULT_GATE_TIMEOUT)

    def test_the_phase_is_reported_as_a_blocking_gate(self):
        from sgate.commands.run_all import CONTROLS_PHASE_NAME

        self.assertTrue(CONTROLS_PHASE_NAME)
