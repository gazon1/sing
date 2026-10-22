"""Tests for check-slow-tests-ran.py.

A test the local loop never runs is a test whose breakage nobody local ever
sees — the suite is green locally and red in CI, and the two disagree about
what the suite is. The gate has to be right in the cases a clean tree never
shows:

  - a `slow` class with no suite in the report is a finding — the gap the
    gate was written for;
  - a `slow` class whose tests were skipped is a finding — a skipped test is
    the other silent green;
  - a `fast` class is not this gate's business — the run floor covers it, and
    requiring `slow` there would fail every local run;
  - a missing report is a finding, not an absence — "I could not find the
    report, so there is nothing to complain about" is the vacuous green.

The tests write their own tree and their own report: a gate exercised only
against the data of one repository is a gate whose behaviour nobody knows
when the tree changes shape.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / 'check-slow-tests-ran.py'
)
spec = importlib.util.spec_from_file_location('check_slow_tests_ran', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_slow_tests_ran'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


SLOW_SOURCE = """package com.singularity.todo

import org.junit.jupiter.api.Tag

@Tag("slow")
class SlowTest {
    @Test
    fun it_runs() = Unit
}
"""

FAST_SOURCE = """package com.singularity.todo

import org.junit.jupiter.api.Tag

@Tag("fast")
class FastTest {
    @Test
    fun it_runs() = Unit
}
"""

SUITE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="{name}" tests="1" skipped="{skipped}" failures="0" errors="0">
  <testcase name="it_runs()" classname="{name}" time="0.1"/>
</testsuite>
"""


class SlowTestsRanTest(unittest.TestCase):
    """Every `slow` class the full run owes the reader has to be in the report."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.source = self.root / 'shared' / 'src' / 'commonTest' / 'kotlin'
        self.source.mkdir(parents=True)
        self.results = self.root / 'shared' / 'build' / 'test-results' / 'jvmTest'
        self.results.mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _source(self, name: str, text: str) -> None:
        (self.source / name).write_text(text, encoding='utf-8')

    def _suite(self, name: str, skipped: int = 0) -> None:
        (self.results / f'TEST-{name}.xml').write_text(
            SUITE.format(name=name, skipped=skipped), encoding='utf-8'
        )

    def test_a_slow_class_without_a_suite_is_a_finding(self) -> None:
        self._source('SlowTest.kt', SLOW_SOURCE)

        problems, checked = mod.find_problems(self.root)

        self.assertEqual(1, len(problems), 'a slow class that did not run is the gap')
        self.assertIn('SlowTest', problems[0])
        self.assertEqual(['SlowTest'], checked)

    def test_a_slow_class_that_ran_is_clean(self) -> None:
        self._source('SlowTest.kt', SLOW_SOURCE)
        self._suite('com.singularity.todo.SlowTest')

        problems, checked = mod.find_problems(self.root)

        self.assertEqual([], problems)
        self.assertEqual(['SlowTest'], checked)

    def test_a_slow_class_whose_tests_were_skipped_is_a_finding(self) -> None:
        self._source('SlowTest.kt', SLOW_SOURCE)
        self._suite('com.singularity.todo.SlowTest', skipped=1)

        problems, _ = mod.find_problems(self.root)

        self.assertEqual(1, len(problems))
        self.assertIn('skipped', problems[0])

    def test_a_fast_class_is_not_this_gates_business(self) -> None:
        # The run floor (`check-test-runs.py`) requires the `fast` classes: they
        # are what a local run executes. Requiring `slow` there would fail every
        # local run, since the absent-`-Ptest.tags` default is
        # `excludeTags("slow")`.
        self._source('FastTest.kt', FAST_SOURCE)

        problems, checked = mod.find_problems(self.root)

        self.assertEqual([], problems)
        self.assertEqual([], checked)

    def test_a_missing_report_is_a_finding_not_an_absence(self) -> None:
        self._source('SlowTest.kt', SLOW_SOURCE)
        (self.root / 'shared' / 'build' / 'test-results' / 'jvmTest').rmdir()

        problems, _ = mod.find_problems(self.root)

        self.assertEqual(1, len(problems))
        self.assertIn('no report', problems[0])

    def test_a_tree_without_slow_classes_is_clean(self) -> None:
        problems, checked = mod.find_problems(self.root)

        self.assertEqual([], problems)
        self.assertEqual([], checked)

    # ── the mapping is complete ─────────────────────────────────────────────────

    def test_every_test_root_with_a_slow_class_is_mapped(self) -> None:
        # The gate's first version mapped five source roots and reported 96
        # slow classes; the repository had 98. The two it could not see were in
        # `adr-cli` and `adr-core` — modules whose test tasks ran nowhere, so
        # nothing noticed that they were not running either. The rule says
        # *every* slow class; this test is what makes the word true, and it
        # fails the day a slow class appears in a root the gate does not map.
        repo = pathlib.Path(__file__).resolve().parent.parent.parent
        mapped = {source for source, _ in mod.SOURCES}
        scanner = mod._scanner()

        unmapped: list[str] = []
        for root in _test_roots(repo):
            relative = str(root.relative_to(repo))
            if relative in mapped:
                continue
            for source_file in sorted(root.rglob('*Test.kt')):
                source = source_file.read_text(encoding='utf-8', errors='replace')
                for name in scanner.test_classes_in(source):
                    if scanner.read_tag(source, name) == 'slow':
                        unmapped.append(f'{relative}:{name}')

        self.assertEqual(
            [],
            unmapped,
            'a slow class in a root the gate does not map is a class nobody checks',
        )


def _test_roots(repo: pathlib.Path) -> list[pathlib.Path]:
    """Every test source root the repository has."""
    roots: list[pathlib.Path] = []
    for module in sorted(repo.iterdir()):
        if not (module / 'build.gradle.kts').is_file():
            continue
        sources = module / 'src'
        if not sources.is_dir():
            continue
        for candidate in sorted(sources.iterdir()):
            if not candidate.is_dir():
                continue
            if not (candidate.name.endswith('Test') or candidate.name == 'test'):
                continue
            if (candidate / 'kotlin').is_dir():
                roots.append(candidate / 'kotlin')
    return roots


if __name__ == '__main__':
    unittest.main()
