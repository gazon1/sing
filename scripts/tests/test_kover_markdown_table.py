"""Tests for scripts/kover-markdown-table.py — the repository's Kover reader.

The reader exists so that nobody has to parse the XML by hand to answer a
question about coverage. It did not answer the question that mattered: how many
CLASSES the report covered.

The percentages alone cannot tell a full report from a partial one. The fast
population and the full population reported the same instruction totals, while
their class counts differed — the fast run does not load the classes that only
the slow population reaches. A reader who saw "27.7%" against a recorded floor
and read it as a fifth of the codebase had misread the report, and then spent
an hour measuring something that was never broken.

So the cases that matter:

  - the summary reads the report ROOT's counters, not a sum over the packages:
    summing would count every class twice, once through its package and once
    through itself;
  - the class count is in the summary — it is the number that says which
    report this is;
  - two reports with the same instruction totals and different class counts
    produce different summaries, because that is the whole point;
  - the table still prints without the flag, so the reader stays usable the
    way it was.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'kover-markdown-table.py'
spec = importlib.util.spec_from_file_location('kover_markdown_table', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['kover_markdown_table'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


def _report(class_count: int, instructions: int) -> str:
    """A Kover-shaped report: two packages, and the root's own counters."""
    return f"""<?xml version="1.0" ?>
<report name=":">
  <package name="com/singularity/todo/feature/one">
    <class name="com/singularity/todo/feature/one/One">
      <counter type="INSTRUCTION" missed="10" covered="90"/>
      <counter type="CLASS" missed="0" covered="1"/>
    </class>
    <counter type="INSTRUCTION" missed="10" covered="90"/>
    <counter type="BRANCH" missed="4" covered="6"/>
    <counter type="LINE" missed="2" covered="8"/>
    <counter type="CLASS" missed="0" covered="1"/>
  </package>
  <package name="com/singularity/todo/feature/two">
    <class name="com/singularity/todo/feature/two/Two">
      <counter type="INSTRUCTION" missed="5" covered="95"/>
      <counter type="CLASS" missed="0" covered="1"/>
    </class>
    <counter type="INSTRUCTION" missed="5" covered="95"/>
    <counter type="BRANCH" missed="1" covered="4"/>
    <counter type="LINE" missed="1" covered="9"/>
    <counter type="CLASS" missed="0" covered="1"/>
  </package>
  <counter type="INSTRUCTION" missed="20" covered="{instructions}"/>
  <counter type="BRANCH" missed="5" covered="10"/>
  <counter type="LINE" missed="3" covered="17"/>
  <counter type="CLASS" missed="{max(class_count - 2, 0)}" covered="2"/>
</report>
"""


class KoverMarkdownTableTest(unittest.TestCase):
    """The summary says which report this is."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, name: str, text: str) -> pathlib.Path:
        path = self.dir / name
        path.write_text(text, encoding='utf-8')
        return path

    def test_the_summary_reads_the_root_counters(self) -> None:
        path = self.write('full.xml', _report(class_count=4642, instructions=315830))
        counters = mod.parse_summary(path)
        self.assertEqual(counters['INSTRUCTION'], (315830, 315850, '100.0%'))
        self.assertEqual(counters['CLASS'][1], 4642)
        self.assertEqual(counters['CLASS'][0], 2)

    def test_the_summary_does_not_sum_the_packages(self) -> None:
        # The packages carry 185 covered instructions between them; the root
        # says 315830. A reader must see the root's number.
        path = self.write('full.xml', _report(class_count=4642, instructions=315830))
        counters = mod.parse_summary(path)
        self.assertNotEqual(counters['INSTRUCTION'][0], 185)

    def test_two_reports_with_the_same_instructions_differ_in_classes(self) -> None:
        fast = self.write('fast.xml', _report(class_count=4634, instructions=315830))
        full = self.write('full.xml', _report(class_count=4642, instructions=315830))
        fast_counters = mod.parse_summary(fast)
        full_counters = mod.parse_summary(full)
        self.assertEqual(fast_counters['INSTRUCTION'], full_counters['INSTRUCTION'])
        self.assertNotEqual(fast_counters['CLASS'][1], full_counters['CLASS'][1])

    def test_the_summary_prints_the_class_count_first(self) -> None:
        path = self.write('full.xml', _report(class_count=4642, instructions=315830))
        text = mod.format_summary(mod.parse_summary(path))
        self.assertIn('| Classes |', text)
        self.assertIn('4642', text)
        self.assertLess(text.index('Classes'), text.index('Instructions'))

    def test_the_table_still_prints_without_the_flag(self) -> None:
        path = self.write('full.xml', _report(class_count=4642, instructions=315830))
        rows = mod.parse_report(path)
        self.assertTrue(rows)
        self.assertIn('| Module |', mod.format_table(rows))


if __name__ == '__main__':
    unittest.main()
