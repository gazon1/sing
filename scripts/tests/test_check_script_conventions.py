"""Unit tests for `scripts/check-script-conventions.py` — the argparse detector.

Run with: python3 -m unittest discover -s scripts/tests

The rule under test is R2.1: a gate script uses Click, not argparse. The
detector used to be a substring search (`"import argparse" in content`), and
that is a mention, not an import: the gate's own source contains the literal
text in the docstring that documents the rule, so the gate reported *itself*
on every run next to the two scripts that genuinely used argparse. A gate
that always names itself is a gate nobody reads past its first line.

So the detector now walks the parse tree, and these tests pin the four
things that has to get right:

  1. a real `argparse` import is reported — in every spelling;
  2. a *mention* of argparse — in a string, a comment, a docstring — is not;
  3. the gate's own source is not reported (the regression this exists for);
  4. a file that does not parse is a reported violation, not a traceback:
     a gate script that cannot be parsed cannot be shown to obey the rule.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(_ROOT / "scripts" / "src"))

SCRIPTS = _ROOT / "scripts"
GATE = SCRIPTS / "check-script-conventions.py"

_spec = importlib.util.spec_from_file_location("check_script_conventions_gate", GATE)
_gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_gate)

check_cli_framework = _gate.check_cli_framework
check_package_resolution = _gate.check_package_resolution
check_sgate_runner = _gate.check_sgate_runner
find_argparse_import = _gate.find_argparse_import


def write_script(directory: pathlib.Path, name: str, source: str) -> pathlib.Path:
    """Write one `check-*.py` file into `directory` and return its path."""
    path = directory / name
    path.write_text(source, encoding="utf-8")
    return path


class ArgparseImportsAreDetected(unittest.TestCase):
    """A real import statement is a violation — in every spelling."""

    def test_plain_import_is_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            write_script(directory, "check-bad.py", "import argparse\n")
            errors = check_cli_framework(directory)
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("check-bad.py", errors[0])
        self.assertIn("argparse", errors[0])

    def test_aliased_and_from_forms_are_flagged(self):
        sources = [
            "import argparse as ap\n",
            "from argparse import ArgumentParser\n",
            "import argparse.something\n",
            "from argparse.sub import thing\n",
        ]
        for source in sources:
            with self.subTest(source=source):
                with tempfile.TemporaryDirectory() as tmp:
                    directory = pathlib.Path(tmp)
                    write_script(directory, "check-bad.py", source)
                    errors = check_cli_framework(directory)
                self.assertEqual(len(errors), 1, f"{source!r} was not flagged: {errors}")

    def test_the_violation_names_the_line(self):
        source = "import click\n\nimport argparse\n"
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            write_script(directory, "check-bad.py", source)
            errors = check_cli_framework(directory)
        self.assertEqual(len(errors), 1, errors)
        self.assertIn(":3:", errors[0])


class MentionsOfArgparseAreNotImports(unittest.TestCase):
    """The word argparse in a string, a comment or a docstring is not an import."""

    def test_a_string_mention_is_not_flagged(self):
        source = 'EXAMPLE = "import argparse"\n'
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            write_script(directory, "check-fine.py", source)
            errors = check_cli_framework(directory)
        self.assertEqual(errors, [])

    def test_a_comment_mention_is_not_flagged(self):
        source = "# do not `import argparse` here\nimport click\n"
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            write_script(directory, "check-fine.py", source)
            errors = check_cli_framework(directory)
        self.assertEqual(errors, [])

    def test_a_docstring_mention_is_not_flagged(self):
        source = '"""This gate forbids `from argparse import` in gate scripts."""\n'
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            write_script(directory, "check-fine.py", source)
            errors = check_cli_framework(directory)
        self.assertEqual(errors, [])

    def test_find_argparse_import_reports_no_line_for_a_mention(self):
        lineno, parse_error = find_argparse_import('x = "import argparse"\n')
        self.assertIsNone(lineno)
        self.assertIsNone(parse_error)


class TheGateDoesNotFlagItself(unittest.TestCase):
    """The regression: the detector used to report the file that names the rule."""

    def test_the_real_scripts_directory_has_no_argparse_violation(self):
        errors = check_cli_framework(SCRIPTS)
        self.assertEqual(errors, [], f"the real scripts tree is not clean: {errors}")

    def test_the_gates_own_source_is_not_flagged(self):
        errors = check_cli_framework(SCRIPTS)
        self.assertNotIn(
            "check-script-conventions.py",
            " ".join(errors),
            f"the gate flagged its own source: {errors}",
        )


class AFileThatDoesNotParseIsAViolation(unittest.TestCase):
    """A gate script that cannot be parsed cannot be shown to obey the rule."""

    def test_a_syntax_error_is_reported_not_raised(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            write_script(directory, "check-broken.py", "def oops(:\n")
            errors = check_cli_framework(directory)
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("check-broken.py", errors[0])
        self.assertIn("does not parse", errors[0])

    def test_find_argparse_import_returns_a_parse_error(self):
        lineno, parse_error = find_argparse_import("def oops(:\n")
        self.assertIsNone(lineno)
        self.assertIsNotNone(parse_error)


if __name__ == "__main__":
    unittest.main()


class PackageResolutionRules(unittest.TestCase):
    """R2.4, R2.5, R2.6 — how a gate script resolves the package it imports."""

    def setUp(self):
        self.directory = pathlib.Path(tempfile.mkdtemp())

    def write(self, name: str, source: str) -> pathlib.Path:
        path = self.directory / name
        path.write_text(source, encoding="utf-8")
        return path

    def errors(self) -> list[str]:
        return check_package_resolution(self.directory)

    def test_the_root_may_not_come_from_the_installed_package(self):
        """R2.4 — the repository belongs to the script."""
        self.write(
            "check-bad.py",
            "from sgate.repo import ROOT\n",
        )
        errors = self.errors()
        self.assertTrue(any("R2.4" in error for error in errors), errors)

    def test_a_path_from_the_installed_package_is_the_same_violation(self):
        self.write(
            "check-bad.py",
            "import sgate.repo\n\nvalue = sgate.repo.ROOT\n",
        )
        errors = self.errors()
        self.assertTrue(any("R2.4" in error for error in errors), errors)

    def test_a_checkout_specific_import_needs_the_bootstrap(self):
        """R2.5 — the manifest of the gates describes *this* checkout."""
        self.write(
            "check-bad.py",
            "from sgate.gate_manifest import PLACEMENTS\n",
        )
        errors = self.errors()
        self.assertTrue(any("R2.5" in error for error in errors), errors)

    def test_a_bootstrap_before_the_import_satisfies_the_rule(self):
        # The bootstrap points at the real package: the rule is about *this*
        # checkout's code, and a directory that holds no package resolves
        # nothing.
        package = _ROOT / "scripts" / "src"
        self.write(
            "check-good.py",
            "import pathlib\nimport sys\n\n"
            f"_SRC = pathlib.Path(r'{package}')\n"
            "if str(_SRC) not in sys.path:\n"
            "    sys.path.insert(0, str(_SRC))\n\n"
            "from sgate.gate_manifest import PLACEMENTS\n",
        )
        self.assertEqual(self.errors(), [])

    def test_a_bootstrap_after_the_import_is_a_violation(self):
        """R2.6 — an import that happens first is already bound."""
        self.write(
            "check-bad.py",
            "import pathlib\nimport sys\n\n"
            "from sgate.gate_manifest import PLACEMENTS\n\n"
            "_SRC = pathlib.Path(__file__).resolve().parent / 'src'\n"
            "if str(_SRC) not in sys.path:\n"
            "    sys.path.insert(0, str(_SRC))\n",
        )
        errors = self.errors()
        self.assertTrue(any("R2.6" in error for error in errors), errors)

    def test_the_public_api_is_bound_too(self):
        """The public API is the same in every checkout — and it is bound anyway.

        The rule used to excuse the modules that "are the same in every
        checkout". That exemption left the resolution to the environment, and
        the environment is where the other checkout came from: with an
        editable install of a sibling worktree in reach, `from sgate import
        run` was that worktree's code. Binding the package costs one line and
        answers the question the script is really asking — whose code is this?
        """
        self.write(
            "check-bad.py",
            "from sgate import run\n",
        )
        errors = self.errors()
        self.assertTrue(any("R2.5" in error for error in errors), errors)

    def test_a_bootstrap_of_another_directory_is_not_a_bootstrap(self):
        """`scripts/` is not the package root; the package lives in `scripts/src`."""
        self.write(
            "check-bad.py",
            "import pathlib\nimport sys\n\n"
            "_SCRIPTS = pathlib.Path(__file__).resolve().parent\n"
            "if str(_SCRIPTS) not in sys.path:\n"
            "    sys.path.insert(0, str(_SCRIPTS))\n\n"
            "from sgate.gate_manifest import PLACEMENTS\n",
        )
        errors = self.errors()
        self.assertTrue(any("R2.5" in error for error in errors), errors)

    def test_a_bootstrap_that_points_nowhere_is_a_violation(self):
        """A bootstrap naming a directory that does not exist resolves nothing."""
        self.write(
            "check-bad.py",
            "import pathlib\nimport sys\n\n"
            "_SRC = pathlib.Path(__file__).resolve().parent.parent / 'src'\n"
            "if str(_SRC) not in sys.path:\n"
            "    sys.path.insert(0, str(_SRC))\n\n"
            "from sgate.gate_manifest import PLACEMENTS\n",
        )
        errors = self.errors()
        self.assertTrue(any("R2.5" in error for error in errors), errors)

    def test_the_real_scripts_directory_has_no_violation(self):
        """The repository obeys the rules it declares."""
        self.assertEqual(check_package_resolution(SCRIPTS), [])


class TheBindingRuleIsOneRule(unittest.TestCase):
    """R2.5 — one rule for every script, and the exception that is declared.

    The rule was a gate script's rule, and it excused the modules that "are
    the same in every checkout". The result: 36 gate scripts and 6 test
    modules resolved the package by whatever the environment held — and with
    an editable install of a sibling worktree in reach, that was another
    checkout's code. The suite failed in 72 places about files this tree does
    not contain.

    So the rule is one rule: every script that imports the package binds it
    to its own tree. The scripts that have to run *without* the package say
    so, in a comment, and are exempt.
    """

    def setUp(self):
        self.directory = pathlib.Path(tempfile.mkdtemp())
        (self.directory / "tests").mkdir()

    def write(self, name: str, source: str) -> pathlib.Path:
        path = self.directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
        return path

    def errors(self) -> list[str]:
        return check_package_resolution(self.directory)

    def test_a_test_module_that_imports_the_package_needs_the_bootstrap(self):
        self.write("tests/test_x.py", "from sgate.gates import load_gates\n")
        errors = self.errors()
        self.assertTrue(any("R2.5" in error for error in errors), errors)

    def test_a_test_module_with_the_bootstrap_is_clean(self):
        package = _ROOT / "scripts" / "src"
        self.write(
            "tests/test_x.py",
            "import pathlib\nimport sys\n\n"
            f"_SRC = pathlib.Path(r'{package}')\n"
            "if str(_SRC) not in sys.path:\n"
            "    sys.path.insert(0, str(_SRC))\n\n"
            "from sgate.gates import load_gates\n",
        )
        self.assertEqual(self.errors(), [])

    def test_a_bootstrap_after_the_import_is_a_violation_in_a_test_module(self):
        self.write(
            "tests/test_x.py",
            "from sgate.gates import load_gates\n\n"
            "import pathlib\nimport sys\n\n"
            "_SRC = pathlib.Path(__file__).resolve().parent.parent / 'src'\n"
            "if str(_SRC) not in sys.path:\n"
            "    sys.path.insert(0, str(_SRC))\n",
        )
        errors = self.errors()
        self.assertTrue(any("R2.6" in error for error in errors), errors)

    def test_an_import_inside_a_function_needs_the_bootstrap_in_that_scope(self):
        self.write(
            "tests/test_x.py",
            "def read():\n    from sgate.gates import load_gates\n",
        )
        errors = self.errors()
        self.assertTrue(any("R2.5" in error for error in errors), errors)

    def test_a_script_that_declares_it_runs_without_the_package_is_exempt(self):
        self.write(
            "check-x.py",
            "# no-sgate: this script has to run when the package is missing.\n"
            "from sgate.gates import load_gates\n",
        )
        self.assertEqual(self.errors(), [])

    def test_a_script_that_does_not_import_the_package_is_clean(self):
        self.write("check-x.py", "import click\n")
        self.assertEqual(self.errors(), [])


class SgateRunnerRules(unittest.TestCase):
    """R2.7 — the shared runner, or a declared reason why not.

    The runner is what makes a gate script's exit code mean the same thing as
    every other gate's. A script that cannot use it has to say so, and the
    reason has to be there: an exception without a reason is an oversight
    wearing a rule's clothes.
    """

    def setUp(self):
        self.directory = pathlib.Path(tempfile.mkdtemp())

    def write(self, name: str, source: str) -> pathlib.Path:
        path = self.directory / name
        path.write_text(source, encoding="utf-8")
        return path

    def errors(self) -> list[str]:
        return check_sgate_runner(self.directory)

    def test_a_script_that_uses_the_runner_is_fine(self):
        self.write(
            "check-good.py",
            "from sgate.commands.base import run\n",
        )
        self.assertEqual(self.errors(), [])

    def test_a_script_that_neither_uses_the_runner_nor_says_why_is_reported(self):
        self.write("check-bad.py", "import click\n")
        errors = self.errors()
        self.assertTrue(any("R2.7" in error for error in errors), errors)

    def test_the_marker_may_be_declared_with_a_reason(self):
        self.write(
            "check-good.py",
            "# no-sgate: this script has to run when the package is missing.\n"
            "import click\n",
        )
        self.assertEqual(self.errors(), [])

    def test_the_marker_without_a_reason_is_reported(self):
        # The reason is the point of the marker: "this script is different" is
        # not a reason, it is the thing that needs one.
        self.write(
            "check-bad.py",
            "# no-sgate:\nimport click\n",
        )
        errors = self.errors()
        self.assertTrue(any("without a reason" in error for error in errors), errors)

    def test_a_marker_mentioned_in_a_docstring_is_not_a_declaration(self):
        # A docstring that *documents* the marker is not a script that
        # *declares* it — the same distinction the argparse detector makes.
        self.write(
            "check-bad.py",
            '"""This script would say: # no-sgate: because of a reason."""\n',
        )
        errors = self.errors()
        self.assertTrue(any("R2.7" in error for error in errors), errors)

    def test_a_file_that_does_not_parse_is_reported_not_raised(self):
        self.write("check-broken.py", "def oops(:\n")
        errors = self.errors()
        self.assertTrue(any("does not parse" in error for error in errors), errors)

    def test_the_real_scripts_directory_has_no_violation(self):
        """The repository obeys the rules it declares."""
        self.assertEqual(check_sgate_runner(SCRIPTS), [])
