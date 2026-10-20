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
