"""Tests for measure-baseline-composition.py — where the detekt suppressions are.

The defect this tool exists to make visible: a plan proposed that
`BackingPropertyNaming`'s 53 suppressions were a rule being "decorated rather
than enforced". The rule had been switched off with a written reason two weeks
earlier — the codebase deliberately chose the opposite convention — and the 53
entries were suppressions for a rule that could not report them. They were
phantom debt.

Nobody could see that from the baseline file. It carries no marker saying which
rules are live, so every number quoted about it had to be recomputed by hand,
and the hand computation was wrong. The same wrong number then reached a GitHub
issue as a confident claim about which rules were untested.

So the numbers are printed by a re-runnable command, and the properties asserted
here are:

* an entry whose rule is declared `active: false` is counted as a *phantom* and
  named — a suppression for a rule that cannot report it is not debt, it is
  noise that inflates the count the ratchet compares;
* a baseline whose rules are all live has no phantoms, and the report says
  nothing about them — a measurement that cries wolf on a clean baseline is a
  measurement nobody reads;
* the table names every baseline it read and the totals, so a number in the
  report can be traced to a file;
* a rule that is switched off is marked as such in the top-rules listing,
  because a high count for a disabled rule is the case the tool exists for;
* a missing detekt config or a missing baseline is an error (exit 1), not an
  empty table: "nothing found" and "nothing looked at" are different claims.

The tool is a measurement, not a gate: it exits 0 even when it finds phantoms,
so the assertions are on what it prints. The detekt config and the baselines
are temporary files; the last test runs the tool against this repository's own
baselines, which it only ever reads.
"""

import contextlib
import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
REPO_ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "measure_baseline_composition", SCRIPTS / "measure-baseline-composition.py"
)
measure = importlib.util.module_from_spec(_spec)
sys.modules["measure_baseline_composition"] = measure
_spec.loader.exec_module(measure)

DETEKT_CONFIG = """\
style:
  MagicNumber:
    active: false
  LongMethod:
    active: true
"""

BASELINE = """\
<?xml version="1.0" ?>
<SmellBaseline>
  <CurrentIssues>
    <ID>LongMethod:Foo.kt:Foo$baz: Unit</ID>
  </CurrentIssues>
</SmellBaseline>
"""

BASELINE_WITH_PHANTOM = """\
<?xml version="1.0" ?>
<SmellBaseline>
  <CurrentIssues>
    <ID>LongMethod:Foo.kt:Foo$baz: Unit</ID>
    <ID>MagicNumber:Foo.kt:Foo$bar: Int</ID>
  </CurrentIssues>
</SmellBaseline>
"""


class BaselineTreeTest(unittest.TestCase):
    """A temporary detekt config and baseline."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.config = self.root / "config/detekt/detekt.yml"
        self.config.parent.mkdir(parents=True)
        self.baseline = self.root / "config/detekt/baseline-shared.xml"
        self.write_config(DETEKT_CONFIG)
        self.write_baseline(BASELINE)

    def write_config(self, text):
        self.config.write_text(text, encoding="utf-8")

    def write_baseline(self, text):
        self.baseline.write_text(text, encoding="utf-8")

    def run_tool(self, *args):
        buffer = io.StringIO()
        argv = ["measure-baseline-composition.py", *args]
        with (
            unittest.mock.patch.object(measure, "ROOT", self.root),
            unittest.mock.patch.object(
                measure, "DEFAULT_BASELINES", ("config/detekt/baseline-shared.xml",)
            ),
            unittest.mock.patch.object(sys, "argv", argv),
        ):
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                code = measure.main()
        return code, buffer.getvalue()


class PhantomTest(BaselineTreeTest):
    """A suppression for a rule that cannot report it."""

    def test_a_phantom_suppression_is_counted(self):
        self.write_baseline(BASELINE_WITH_PHANTOM)
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertIn("PHANTOM suppressions: 1", output)

    def test_the_phantom_rule_is_named(self):
        """A number without the rule is a number, not a work item."""
        self.write_baseline(BASELINE_WITH_PHANTOM)
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertIn("MagicNumber", output)

    def test_the_phantom_section_explains_what_a_phantom_is(self):
        self.write_baseline(BASELINE_WITH_PHANTOM)
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertIn("switched off", output)
        self.assertIn("detektBaseline", output)

    def test_a_clean_baseline_reports_no_phantoms(self):
        """A measurement that cries wolf on a clean baseline is not read."""
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertNotIn("PHANTOM", output)

    def test_the_disabled_rule_is_marked_in_the_top_rules_listing(self):
        """A high count for a disabled rule is the case the tool exists for."""
        self.write_baseline(BASELINE_WITH_PHANTOM)
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertIn("(disabled)", output)


class TableTest(BaselineTreeTest):
    """The table the numbers come from."""

    def test_the_table_names_the_baseline_and_its_counts(self):
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertIn("config/detekt/baseline-shared.xml", output)
        self.assertIn("TOTAL", output)

    def test_the_number_of_disabled_rules_is_reported(self):
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertIn("rules declared `active: false`: 1", output)

    def test_the_config_that_was_used_is_named(self):
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertIn("config/detekt/detekt.yml", output)

    def test_a_ruleset_level_active_flag_is_not_a_rule(self):
        """`active:` at indent 2 belongs to the ruleset, not to a rule.

        A parser that keyed on the nearest preceding header regardless of
        indent reported every ruleset's flag as a rule's — eleven "rules" for
        a file with five.
        """
        self.write_config(
            "style:\n"
            "  active: true\n"
            "  MagicNumber:\n"
            "    active: false\n"
        )
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertIn("rules declared `active: false`: 1", output)


class ErrorTest(BaselineTreeTest):
    """Nothing found and nothing looked at are different claims."""

    def test_a_missing_detekt_config_is_an_error(self):
        self.config.unlink()
        code, output = self.run_tool()
        self.assertEqual(1, code)
        self.assertIn("detekt config not found", output)

    def test_a_missing_baseline_is_an_error(self):
        self.baseline.unlink()
        code, output = self.run_tool()
        self.assertEqual(1, code)
        self.assertIn("no baseline file found", output)

    def test_a_baseline_with_no_entries_is_still_a_baseline(self):
        self.write_baseline('<?xml version="1.0" ?>\n<SmellBaseline>\n</SmellBaseline>\n')
        code, output = self.run_tool()
        self.assertEqual(0, code, output)
        self.assertIn("TOTAL", output)


class RealRepositoryTest(unittest.TestCase):
    """This repository's own detekt configuration and baselines."""

    def test_the_default_baselines_exist(self):
        for rel in measure.DEFAULT_BASELINES:
            self.assertTrue((REPO_ROOT / rel).is_file(), rel)

    def test_the_tool_runs_against_this_repository(self):
        """The measurement is re-runnable; it only ever reads."""
        buffer = io.StringIO()
        with unittest.mock.patch.object(
            sys, "argv", ["measure-baseline-composition.py"]
        ):
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                code = measure.main()
        output = buffer.getvalue()
        self.assertEqual(0, code, output)
        for rel in measure.DEFAULT_BASELINES:
            self.assertIn(rel, output)


if __name__ == "__main__":
    unittest.main()
