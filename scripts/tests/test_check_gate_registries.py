"""Tests for check-gate-registries.py — the two gate registries must agree.

A gate can be declared in two places that are not the same place:
`scripts/ci/static-gates.sh` (what `sgate run-all` reads and what CI's static
job runs) and `sgate.commands._registry.GATES` (what `sgate <name>` dispatches).
Measured before this gate existed: blanking the shell registry left
`import sgate` succeeding, left `load_gates()` returning every entry, and left
`sgate run-all` evaluating nothing and exiting 0 — a green static job that had
checked nothing.

The drift between the two was real: nine names disagreed while every check
agreed. So the properties asserted here are the four shapes of drift the gate
claims to catch, each planted on a synthetic pair of registries:

  * a gate line in the shell file with no registry entry — CI runs it,
    `sgate <name>` cannot dispatch it;
  * a registry entry with no gate line and nothing that runs it — `sgate
    run-all` never dispatches it, so a control for it measures nothing;
  * the same gate described with two different modes — one surface says it
    blocks the build, the other says it only warns;
  * a name in `NOT_RUN_IN_STATIC` that is not a registered gate any more — a
    declaration that outlived its gate.

Plus the two directions that must *not* fire (a registry entry that the shell
registry runs as a cluster member, and a registry entry excused by
`NOT_RUN_IN_STATIC`), because a gate that reports drift it invented is a gate
nobody trusts to report a pass.

The clean-tree case — this repository's own two registries — is asserted
against the real files, because that is the pair the gate exists to police.
"""

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_gate_registries", SCRIPTS / "check-gate-registries.py"
)
registries = importlib.util.module_from_spec(_spec)
sys.modules["check_gate_registries"] = registries
_spec.loader.exec_module(registries)

from sgate.commands._registry import CmdType, GateMode, GateSpec  # noqa: E402

#: A script the shell registry runs, so the registry entry that points at it is
#: covered and must not be reported as "nothing runs it".
COVERED_SCRIPT = "scripts/check-test-task-inputs.py"

#: A script nothing runs. Used for the registry-only drift case.
UNCOVERED_SCRIPT = "scripts/check-nothing-runs-this.py"


def _spec_for(name, mode="blocking", script=COVERED_SCRIPT):
    return GateSpec(
        name=name,
        mode=GateMode(mode),
        cmd_type=CmdType.PYTHON3,
        script=pathlib.Path(script),
    )


@contextlib.contextmanager
def _registries(shell_gates, registry, not_run=None):
    """Replace both registries with synthetic ones for the duration of a block.

    The three places the gate reads them are the shell registry loader, the
    `GATES` list and the `NOT_RUN_IN_STATIC` map — plus `sgate.gates`' own
    copy of `GATES`, which `load_gates()` reads through a module-level binding
    and would otherwise keep returning the real registry.
    """
    import sgate.commands._registry as registry_module
    import sgate.commands.run_all as run_all
    import sgate.gates as gates

    def _raise():
        raise RuntimeError("the shell registry could not be read")

    loader = _raise if shell_gates is None else (lambda: list(shell_gates))

    with (
        unittest.mock.patch.object(registry_module, "GATES", tuple(registry)),
        unittest.mock.patch.object(registry_module, "NOT_RUN_IN_STATIC", dict(not_run or {})),
        unittest.mock.patch.object(run_all, "_load_static_gates", loader),
        unittest.mock.patch.object(gates, "_REGISTRY_GATES", tuple(registry)),
    ):
        yield


class RealRegistriesTest(unittest.TestCase):
    """This repository's own two registries."""

    def test_the_real_registries_agree(self):
        violations, warnings, summary = registries.evaluate(strict=True)
        self.assertEqual([], violations, violations)
        self.assertEqual([], warnings, warnings)
        self.assertIn("gates in static-gates.sh", summary)

    def test_the_real_gate_is_green(self):
        code = registries.run(registries.cli.main, [])
        self.assertEqual(0, code)


class DriftTest(unittest.TestCase):
    """The four shapes of drift, each planted on a synthetic pair of registries."""

    def _violations(self, shell_gates, registry, not_run=None, strict=True):
        with _registries(shell_gates, registry, not_run):
            violations, warnings, _summary = registries.evaluate(strict=strict)
        return violations, warnings

    def test_a_gate_line_with_no_registry_entry_is_reported(self):
        violations, _ = self._violations(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("beta")],
        )
        joined = " ".join(violations)
        self.assertIn("'alpha' is a gate line in scripts/ci/static-gates.sh", joined)
        self.assertIn("no entry in", joined)

    def test_a_registry_entry_nothing_runs_is_reported(self):
        violations, _ = self._violations(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("alpha"), _spec_for("gamma", script=UNCOVERED_SCRIPT)],
        )
        joined = " ".join(violations)
        self.assertIn("'gamma' is registered in _registry.GATES with no gate line", joined)

    def test_two_modes_for_one_gate_is_reported(self):
        """One surface says it blocks, the other says it only warns.

        Which one a caller reads decides what a red gate means, so the
        disagreement has to be loud rather than resolved silently.
        """
        violations, _ = self._violations(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("alpha", mode="advisory")],
        )
        joined = " ".join(violations)
        self.assertIn("'alpha' is blocking in scripts/ci/static-gates.sh", joined)
        self.assertIn("advisory", joined)

    def test_a_stale_not_run_in_static_entry_is_reported(self):
        """A declaration that outlived its gate can hide a gate nobody runs."""
        violations, _ = self._violations(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("alpha")],
            not_run={"delta": None},
        )
        joined = " ".join(violations)
        self.assertIn("'delta' is in NOT_RUN_IN_STATIC", joined)

    def test_an_empty_shell_registry_is_a_violation(self):
        """Zero gates evaluated is not a suite that passed."""
        violations, _ = self._violations([], [])
        joined = " ".join(violations)
        self.assertIn("declared zero gates", joined)

    def test_an_empty_python_registry_is_a_violation(self):
        violations, _ = self._violations(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [],
        )
        joined = " ".join(violations)
        self.assertIn("Python gate registry", joined)


class NoFalsePositiveTest(unittest.TestCase):
    """The shapes that must not be reported as drift."""

    def _violations(self, shell_gates, registry, not_run=None, strict=True):
        with _registries(shell_gates, registry, not_run):
            violations, warnings, _summary = registries.evaluate(strict=strict)
        return violations, warnings

    def test_a_registry_entry_the_shell_registry_runs_is_not_reported(self):
        violations, _ = self._violations(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("alpha")],
        )
        self.assertEqual([], violations, violations)

    def test_a_registry_entry_excused_by_not_run_in_static_is_not_reported(self):
        """`NOT_RUN_IN_STATIC` is the declared way a gate runs somewhere else."""
        violations, _ = self._violations(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("alpha"), _spec_for("gamma", script=UNCOVERED_SCRIPT)],
            not_run={"gamma": None},
        )
        self.assertEqual([], violations, violations)

    def test_drift_is_a_warning_unless_strict_is_asked_for(self):
        """Drift without `--strict` warns; the violation list stays empty.

        The gate is blocking now, but the strict flag is what decides whether
        drift fails the run, and the two paths must not be the same path.
        """
        violations, warnings = self._violations(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("beta")],
            strict=False,
        )
        self.assertEqual([], violations, violations)
        self.assertTrue(warnings, "drift that is only a warning must still be reported")
        self.assertIn("'alpha' is a gate line", " ".join(warnings))


class UnavailableTest(unittest.TestCase):
    """A registry that cannot be read is an infrastructure failure, not a verdict."""

    def test_an_unreadable_registry_raises_registry_unavailable(self):
        with _registries(None, []):
            with self.assertRaises(registries.RegistryUnavailable):
                registries.evaluate(strict=True)

    def test_an_unreadable_registry_exits_with_the_io_error_code(self):
        """Exit 2, not 1: the gate did not run, so it did not fail either."""
        with _registries(None, []):
            code = registries.run(registries.cli.main, [])
        self.assertEqual(registries.IO_ERROR, code)

    def test_an_unreadable_registry_reports_itself_in_json(self):
        buffer = io.StringIO()
        with _registries(None, []):
            with contextlib.redirect_stdout(buffer):
                code = registries.run(registries.cli.main, ["--json"])
        payload = json.loads(buffer.getvalue())
        self.assertEqual(registries.IO_ERROR, code)
        self.assertFalse(payload["passed"])
        self.assertTrue(payload["blocking_errors"])


class CliTest(unittest.TestCase):
    """The exit codes and the JSON verdict."""

    def test_drift_fails_the_run(self):
        with _registries(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("beta")],
        ):
            code = registries.run(registries.cli.main, ["--strict"])
        self.assertEqual(registries.VALIDATION_FAILURE, code)

    def test_the_check_flag_reports_without_failing(self):
        """`--check` is the dry run: the findings are still printed."""
        buffer = io.StringIO()
        with _registries(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("beta")],
        ):
            with contextlib.redirect_stdout(buffer):
                code = registries.run(registries.cli.main, ["--strict", "--check"])
        self.assertEqual(registries.CLEAN, code)
        self.assertIn("alpha", buffer.getvalue())

    def test_the_json_verdict_names_the_drift(self):
        buffer = io.StringIO()
        with _registries(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("beta")],
        ):
            with contextlib.redirect_stdout(buffer):
                code = registries.run(registries.cli.main, ["--strict", "--json"])
        payload = json.loads(buffer.getvalue())
        self.assertEqual(registries.VALIDATION_FAILURE, code)
        self.assertFalse(payload["passed"])
        self.assertTrue(
            any("'alpha'" in message for message in payload["blocking_errors"]),
            payload["blocking_errors"],
        )

    def test_a_clean_pair_passes(self):
        with _registries(
            [("blocking", "alpha", f"python3 {COVERED_SCRIPT}")],
            [_spec_for("alpha")],
        ):
            code = registries.run(registries.cli.main, ["--strict"])
        self.assertEqual(registries.CLEAN, code)


class ModuleClassificationTest(unittest.TestCase):
    """Every command module is classified, and every classified module exists.

    `sgate --help` lists gates and utilities side by side, so the classification
    *is* the statement — and a statement nobody checks is a statement nobody
    made.
    """

    def test_the_real_command_modules_are_all_classified(self):
        self.assertEqual([], registries._module_classification_problems())

    def test_a_module_nobody_classified_is_reported(self):
        """A module on disk with no classification is a decision nobody made."""
        import sgate.commands._registry as registry_module

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            commands = root / "scripts" / "src" / "sgate" / "commands"
            commands.mkdir(parents=True)
            (commands / "brand_new_module.py").write_text("", encoding="utf-8")
            (commands / "__init__.py").write_text("", encoding="utf-8")
            with (
                unittest.mock.patch.object(registries, "ROOT", root),
                unittest.mock.patch.object(
                    registry_module, "COMMAND_MODULES", {"__init__": "infrastructure"}
                ),
            ):
                problems = registries._module_classification_problems()
        joined = " ".join(problems)
        self.assertIn("'brand_new_module' is a command module nobody classified", joined)

    def test_a_classified_module_that_is_gone_is_reported(self):
        """A classification is a claim about a file; a missing file falsifies it."""
        import sgate.commands._registry as registry_module

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            commands = root / "scripts" / "src" / "sgate" / "commands"
            commands.mkdir(parents=True)
            with (
                unittest.mock.patch.object(registries, "ROOT", root),
                unittest.mock.patch.object(
                    registry_module, "COMMAND_MODULES", {"retired_module": "gate"}
                ),
            ):
                problems = registries._module_classification_problems()
        joined = " ".join(problems)
        self.assertIn("'retired_module' is classified in COMMAND_MODULES", joined)


if __name__ == "__main__":
    unittest.main()
