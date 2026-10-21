"""Tests for check-detekt-governance.py — the cluster wrapper around the detekt checks.

Six checks read the detekt configuration and the rules it declares. They were six
CI rows and six registry entries for one subject, and they are one gate now.
The wrapper is worth nothing unless it does two things: it must fail when a
member fails, and it must fail when a member *cannot* run — a member whose
script was renamed is a member that reports nothing while the cluster reads
green.

So the properties asserted here are:

* the cluster names the six checks it claims to run, and every script it names
  exists on disk;
* `--only <name>` selects exactly one member, and an unknown name is refused
  rather than silently selecting everything (a `--only` that ran everything
  would make one member's control drive all six);
* a failing member makes the wrapper exit non-zero and names the member;
* a member that cannot be run is an IO error, not a pass.

The real cluster is run once against this repository, because the clean-tree
case cannot be simulated: the members read this repository's detekt config.
"""

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_detekt_governance", SCRIPTS / "check-detekt-governance.py"
)
governance = importlib.util.module_from_spec(_spec)
sys.modules["check_detekt_governance"] = governance
_spec.loader.exec_module(governance)

ROOT = SCRIPTS.parent

PASSING = 'python3 -c "raise SystemExit(0)"'
FAILING = 'python3 -c "import sys; sys.stderr.write(\'boom\\n\'); sys.exit(3)"'


def _member(name, command):
    return governance.Member(name, command)


class ClusterShapeTest(unittest.TestCase):
    """The cluster names the checks it says it runs, and they exist."""

    def test_the_cluster_is_not_empty(self):
        self.assertTrue(governance.MEMBERS)

    def test_every_member_names_a_script_that_exists(self):
        """A member pointing at a deleted script can only report by accident."""
        for member in governance.MEMBERS:
            scripts = [p for p in member.command.split() if p.startswith("scripts/")]
            self.assertTrue(scripts, f"{member.name!r} names no script: {member.command!r}")
            for rel in scripts:
                self.assertTrue(
                    (ROOT / rel).is_file(),
                    f"{member.name!r} names {rel}, which does not exist",
                )

    def test_the_six_documented_members_are_the_ones_that_run(self):
        names = sorted(m.name for m in governance.MEMBERS)
        self.assertEqual(
            names,
            sorted([
                "detekt rules config is current",
                "detekt rule inventory is current",
                "detekt rule registry",
                "detekt baseline ratchet",
                "lint rules are declared decisions",
                "file-level suppressions explain themselves",
            ]),
            "the wrapper's membership changed — the cluster must stay the thing "
            "its docstring says it is",
        )

    def test_every_member_command_is_spelled_out(self):
        """The wiring scan reads command position; a bare path hides the call."""
        for member in governance.MEMBERS:
            self.assertTrue(
                member.command.startswith(("python3 ", "bash ")),
                f"{member.name!r} is not spelled out as a command: {member.command!r}",
            )


class SelectionTest(unittest.TestCase):
    """`--only` picks one member, and an unknown name is an error."""

    def test_no_selection_means_every_member(self):
        self.assertEqual(governance.MEMBERS, governance._selected(None))

    def test_a_slug_selects_exactly_one_member(self):
        selected = governance._selected("detekt_baseline_ratchet")
        self.assertEqual(1, len(selected))
        self.assertEqual("detekt baseline ratchet", selected[0].name)

    def test_a_display_name_selects_exactly_one_member(self):
        selected = governance._selected("detekt rule registry")
        self.assertEqual(1, len(selected))

    def test_an_unknown_member_is_refused(self):
        with self.assertRaises(Exception) as caught:
            governance._selected("no-such-member")
        self.assertIn("no-such-member", str(caught.exception))

    def test_the_slug_of_a_member_name_is_stable(self):
        self.assertEqual(
            "lint_rules_are_declared_decisions",
            governance._slug("lint rules are declared decisions"),
        )


class MemberRunTest(unittest.TestCase):
    """A member's exit code and output are what the wrapper reports."""

    def test_a_passing_command_reports_zero(self):
        code, output = governance.Member("ok", PASSING).run()
        self.assertEqual(0, code)
        self.assertEqual("", output)

    def test_a_failing_command_reports_its_exit_code_and_output(self):
        code, output = governance.Member("bad", FAILING).run()
        self.assertEqual(3, code)
        self.assertIn("boom", output)


class CliTest(unittest.TestCase):
    """The wrapper's verdict, with the member list replaced by stand-ins."""

    def _run(self, members, *args):
        buffer = io.StringIO()
        with unittest.mock.patch.object(governance, "MEMBERS", members):
            with contextlib.redirect_stdout(buffer):
                code = governance.run(governance.cli.main, list(args))
        return code, buffer.getvalue()

    def test_all_members_passing_exits_zero(self):
        code, _ = self._run([_member("one", PASSING), _member("two", PASSING)])
        self.assertEqual(0, code)

    def test_one_failing_member_exits_non_zero(self):
        code, _ = self._run([_member("one", PASSING), _member("two", FAILING)])
        self.assertEqual(1, code)

    def test_a_failing_member_is_named_in_the_output(self):
        code, text = self._run([_member("the failing one", FAILING)])
        self.assertEqual(1, code)
        self.assertIn("the failing one", text)

    def test_the_failure_output_is_printed(self):
        _, text = self._run([_member("m", FAILING)])
        self.assertIn("FAIL", text)
        self.assertIn("boom", text)

    def test_a_member_that_cannot_be_run_is_an_error_not_a_pass(self):
        code, _ = self._run([_member("gone", "python3 /nonexistent/check-nothing.py")])
        self.assertNotEqual(0, code)

    def test_the_json_verdict_reports_every_member(self):
        buffer = io.StringIO()
        with unittest.mock.patch.object(
            governance, "MEMBERS", [_member("one", PASSING), _member("two", FAILING)]
        ):
            with contextlib.redirect_stdout(buffer):
                code = governance.run(governance.cli.main, ["--json"])
        payload = json.loads(buffer.getvalue())
        self.assertEqual(1, code)
        self.assertFalse(payload["passed"])
        self.assertEqual(2, len(payload["checks"]))
        self.assertEqual([True, False], [c["passed"] for c in payload["checks"]])


class RealClusterTest(unittest.TestCase):
    """The clean-tree case, on this repository's own detekt configuration."""

    def test_the_real_cluster_passes_on_this_repository(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = governance.run(governance.cli.main, [])
        self.assertEqual(0, code)
        self.assertIn("OK", buffer.getvalue())


if __name__ == "__main__":
    unittest.main()
