"""Tests for check-ci-workflow-shape.py — the cluster wrapper around three CI checks.

The gate exists because three properties of one file (`.github/workflows/ci.yml`)
used to be three separate CI rows, three registry entries and three places to
look when one of them failed. Collapsing them into one gate is only worth
anything if the wrapper still reports *every* failure and still fails when a
member fails — a wrapper that swallows a member's exit code is a gate that
cannot fail, which is the defect class this file exists to catch.

So the properties asserted here are:

* the cluster still names the three checks it claims to run, and every script it
  names exists (a member that points at a deleted script is a member that can
  only fail, or only pass, by accident);
* `--only <name>` selects exactly one member, and an unknown name is refused
  rather than silently selecting everything;
* a failing member makes the wrapper exit non-zero, prints which member failed
  and its output, and a passing member makes it exit zero;
* a member that cannot be run at all is an IO error, not a pass.

The real cluster is run once against this repository, because the clean-tree
case is the one that cannot be simulated: the members read `ci.yml`.
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
    "check_ci_workflow_shape", SCRIPTS / "check-ci-workflow-shape.py"
)
shape = importlib.util.module_from_spec(_spec)
sys.modules["check_ci_workflow_shape"] = shape
_spec.loader.exec_module(shape)

ROOT = SCRIPTS.parent


def _member(name, command):
    return shape.Member(name, command)


PASSING = 'python3 -c "raise SystemExit(0)"'
FAILING = 'python3 -c "import sys; sys.stderr.write(\'boom\\n\'); sys.exit(3)"'
NOISY = 'python3 -c "import sys; sys.stdout.write(\'hello\\n\')"'


class ClusterShapeTest(unittest.TestCase):
    """The cluster names the checks it says it runs, and they exist."""

    def test_the_cluster_is_not_empty(self):
        self.assertTrue(shape.MEMBERS)

    def test_every_member_names_a_script_that_exists(self):
        """A member pointing at a deleted script is a member that reports nothing.

        The wrapper would still exit zero — the script simply would not run — so
        the check has to be made here, where a rename is visible.
        """
        for member in shape.MEMBERS:
            parts = member.command.split()
            scripts = [p for p in parts if p.startswith("scripts/")]
            self.assertTrue(
                scripts,
                f"{member.name!r} names no script: {member.command!r}",
            )
            for rel in scripts:
                self.assertTrue(
                    (ROOT / rel).is_file(),
                    f"{member.name!r} names {rel}, which does not exist",
                )

    def test_every_member_command_is_spelled_out(self):
        """The wiring scan reads command position, so a bare path hides the call.

        `scripts/check-gate-wiring.py` proves a gate is reachable by looking for
        the script path in the command. A member list written as bare paths
        would be invisible to it, and the member would look unreachable.
        """
        for member in shape.MEMBERS:
            self.assertTrue(
                member.command.startswith(("python3 ", "bash ")),
                f"{member.name!r} is not spelled out as a command: {member.command!r}",
            )

    def test_the_three_documented_members_are_the_ones_that_run(self):
        names = [m.name for m in shape.MEMBERS]
        self.assertEqual(
            sorted(names),
            sorted([
                "test-task external inputs declared",
                "gradle invocations keep one test population",
                "build-output readers precede the invocations that rewrite them",
            ]),
            "the wrapper's membership changed — the docstring and the cluster "
            "must agree, or the gate is not the thing it says it is",
        )


class SelectionTest(unittest.TestCase):
    """`--only` picks one member, and an unknown name is an error."""

    def test_no_selection_means_every_member(self):
        self.assertEqual(shape.MEMBERS, shape._selected(None))

    def test_a_slug_selects_exactly_one_member(self):
        selected = shape._selected("gradle_invocations_keep_one_test_population")
        self.assertEqual(1, len(selected))
        self.assertEqual("gradle invocations keep one test population", selected[0].name)

    def test_a_display_name_selects_exactly_one_member(self):
        selected = shape._selected("test-task external inputs declared")
        self.assertEqual(1, len(selected))

    def test_an_unknown_member_is_refused(self):
        """An unknown name must not fall back to running everything.

        A `--only` that silently ran the whole cluster would make the control
        for one member drive all of them, and the per-member control would stop
        proving anything.
        """
        with self.assertRaises(Exception) as caught:
            shape._selected("no-such-member")
        self.assertIn("no-such-member", str(caught.exception))

    def test_the_slug_of_a_member_name_is_stable(self):
        self.assertEqual(
            "detekt_rules_config_is_current",
            shape._slug("detekt rules config is current"),
        )


class MemberRunTest(unittest.TestCase):
    """A member's exit code and output are what the wrapper reports."""

    def test_a_passing_command_reports_zero(self):
        code, output = shape.Member("ok", PASSING).run()
        self.assertEqual(0, code)
        self.assertEqual("", output)

    def test_a_failing_command_reports_its_exit_code(self):
        code, _ = shape.Member("bad", FAILING).run()
        self.assertEqual(3, code)

    def test_the_output_of_both_streams_is_captured(self):
        code, output = shape.Member("noisy", FAILING).run()
        self.assertNotEqual(0, code)
        self.assertIn("boom", output)

    def test_stdout_is_captured_too(self):
        code, output = shape.Member("noisy", NOISY).run()
        self.assertEqual(0, code)
        self.assertIn("hello", output)


class CliTest(unittest.TestCase):
    """The wrapper's verdict, with the member list replaced by stand-ins."""

    def _run(self, members, *args):
        with unittest.mock.patch.object(shape, "MEMBERS", members):
            return shape.run(shape.cli.main, list(args))

    def test_all_members_passing_exits_zero(self):
        code = self._run([_member("one", PASSING), _member("two", PASSING)])
        self.assertEqual(0, code)

    def test_one_failing_member_exits_non_zero(self):
        code = self._run([_member("one", PASSING), _member("two", FAILING)])
        self.assertEqual(1, code)

    def test_a_failing_member_is_named_in_the_output(self):
        """A failure that is not named cannot be acted on.

        The wrapper exists to collapse three CI rows into one; if it collapsed
        them into one row that says "something failed", the collapse cost more
        than it bought.
        """
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            self._run([_member("the failing one", FAILING)])
        self.assertIn("the failing one", buffer.getvalue())

    def test_the_failure_output_is_printed_under_the_member(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            self._run([_member("m", FAILING)])
        text = buffer.getvalue()
        self.assertIn("FAIL", text)
        self.assertIn("boom", text)

    def test_a_member_that_cannot_be_run_is_an_error_not_a_pass(self):
        """A missing script is an IO error, and the wrapper still fails."""
        code = self._run([_member("gone", "python3 /nonexistent/check-nothing.py")])
        self.assertNotEqual(0, code)

    def test_the_json_verdict_reports_every_member(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = self._run([_member("one", PASSING), _member("two", FAILING)], "--json")
        payload = json.loads(buffer.getvalue())
        self.assertEqual(1, code)
        self.assertFalse(payload["passed"])
        self.assertEqual(2, len(payload["checks"]))
        self.assertEqual(
            [True, False],
            [check["passed"] for check in payload["checks"]],
        )


class RealClusterTest(unittest.TestCase):
    """The clean-tree case, on this repository's own `ci.yml`."""

    def test_the_real_cluster_passes_on_this_repository(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = shape.run(shape.cli.main, [])
        self.assertEqual(0, code)
        self.assertIn("OK", buffer.getvalue())


if __name__ == "__main__":
    unittest.main()
