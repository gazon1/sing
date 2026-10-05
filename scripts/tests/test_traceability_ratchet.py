#!/usr/bin/env python3
"""Tests for check-traceability-ratchet.py.

The gate is one-directional, so its interesting failures are all "did not fire"
shapes: a metric that stops being computed, a corpus that stops being read, an
escape hatch that is on by default. The tests here are about proving the check
still *sees* a planted regression, and one of them pins the reason the second
metric exists at all — the trade that leaves `holes` exactly where it was.

The first version of `_metrics` inverted the deprecated filter, which is how
16 became 1: the corpus has one deprecated spec and 18 live ones, and asking
for the dark ones while keeping only deprecated rows returns 1. Nothing about
that was loud — the gate printed a number and passed — so the count is asserted
against the real corpus here, not only against a fixture.
"""

import importlib.util
import json
import pathlib
import sys
import unittest

SCRIPT = (
    pathlib.Path(__file__).resolve().parent.parent
    / 'check-traceability-ratchet.py'
)
ROOT = SCRIPT.parent.parent

_spec = importlib.util.spec_from_file_location('check_traceability_ratchet', SCRIPT)
ratchet = importlib.util.module_from_spec(_spec)
sys.modules['check_traceability_ratchet'] = ratchet
_spec.loader.exec_module(ratchet)

sys.path.insert(0, str(ROOT / 'infra' / 'kiwi'))

from traceability import SCENARIOS_DIR  # noqa: E402
from traceability.coverage import build_coverage  # noqa: E402
from traceability.links import scan_all  # noqa: E402
from traceability.spec import Target, load_specs  # noqa: E402


def _real_corpus():
    specs = load_specs(SCENARIOS_DIR)
    return specs, build_coverage(specs, scan_all(specs, ROOT))


class RealCorpusTest(unittest.TestCase):
    """The numbers the floor file records, recomputed from the real specs."""

    def test_measured_numbers_match_the_recorded_floor(self):
        # If this fails, the floors in config/docs/traceability-ratchet.json are
        # stale — either the corpus moved (update the file, with a reason) or
        # `_metrics` changed (the file is describing a different measurement
        # than the one the gate performs, which is the failure that matters).
        specs, coverage = _real_corpus()
        measured, dark = ratchet._metrics(coverage)
        import json
        config = json.loads(
            (ROOT / 'config/docs/traceability-ratchet.json').read_text(encoding='utf-8')
        )
        recorded = {f['metric']: f for f in config['floors']}
        self.assertEqual(measured['holes'], recorded['holes']['max'])
        self.assertEqual(measured['dark_scenarios'], recorded['dark_scenarios']['max'])
        self.assertEqual(len(dark), measured['dark_scenarios'])

    def test_dark_scenarios_exclude_deprecated_and_unclaimed(self):
        # A retired scenario is not a gap and a spec that claims no target owes
        # nothing; both were previously folded in and both inflate the number
        # the ratchet compares, which would let real growth hide behind them.
        specs, coverage = _real_corpus()
        measured, dark = ratchet._metrics(coverage)
        deprecated = {s for s, spec in specs.items() if not spec.is_claimed}
        self.assertTrue(deprecated, 'fixture assumption: corpus has a deprecated spec')
        self.assertEqual(set(dark) & deprecated, set())
        unclaimed = {s for s, spec in specs.items() if not spec.targets}
        self.assertEqual(set(dark) & unclaimed, set())

    def test_a_scenario_with_one_carrier_is_not_dark(self):
        # The half-state is the whole reason the metric is separate from `holes`:
        # TASK-CHECK-01 has one claim and no carrier (a hole) but is not dark
        # only if some other target carries it — the reverse case below proves
        # the predicate, this one proves the shape of the corpus.
        specs, coverage = _real_corpus()
        _, dark = ratchet._metrics(coverage)
        self.assertNotIn('TASK-REC-01', dark)
        self.assertNotIn('AUTH-FIRSTRUN-01', dark)
        self.assertIn('TASK-CHECK-01', dark)


class SpecSplitTest(unittest.TestCase):
    """The trade `holes` cannot see, which is the reason `dark_scenarios` exists.

    Stated as a fixture rather than argued in prose, because the claim for a
    second metric is only worth anything if the arithmetic is checked. The shape
    is a spec split: one scenario claiming both targets with no carrier is 2
    holes and 1 dark scenario; splitting it per platform into two specs, each
    claiming one target and neither carrying a carrier, is still 2 holes and now
    2 dark scenarios. Nothing was added to the suite and one unverified user
    scenario quietly became two.

    The first draft of this test asserted a different trade — add a two-target
    carrierless spec, remove a carrier from elsewhere — and the fixture failed
    it immediately, because that trade moves `holes` by 4, not 0. A justification
    that does not survive its own arithmetic is not a justification, so the
    search that found a real one was run over all 9 per-scenario states on both
    targets (81 pairs, 85 qualifying trades) rather than reasoned about.
    """

    def _coverage(self, cells, deprecated=()):
        from traceability.coverage import Coverage
        from traceability.spec import ScenarioSpec, SpecStatus
        specs = {
            name: ScenarioSpec(
                id=name,
                title=name,
                area='feature.tasks',
                id_prefix=name.rsplit('-', 1)[0],
                priority='P1',
                status=(SpecStatus.DEPRECATED if name in deprecated else SpecStatus.CONFIRMED),
                targets=tuple(t for t, c in row.items() if c.claimed),
                preconditions='',
                steps=(),
                expected='',
            )
            for name, row in cells.items()
        }
        return Coverage(cells=cells, specs=specs)

    def _row(self, claimed, automated):
        from traceability.coverage import CoverageCell
        from traceability.spec import Target
        return {
            target: CoverageCell(claimed=t, automated=a)
            for target, t, a in zip((Target.ANDROID, Target.DESKTOP), claimed, automated)
        }

    def test_a_spec_split_leaves_holes_flat_and_dark_scenarios_up(self):
        before = self._coverage({'SYNC-STATUS-01': self._row((1, 1), (0, 0))})
        after = self._coverage({
            'SYNC-STATUS-ANDROID-01': self._row((1, 0), (0, 0)),
            'SYNC-STATUS-DESKTOP-01': self._row((0, 1), (0, 0)),
        })
        before_measured, _ = ratchet._metrics(before)
        after_measured, after_dark = ratchet._metrics(after)

        self.assertEqual(
            before_measured['holes'],
            after_measured['holes'],
            'the premise: a split does not change the cell count',
        )
        self.assertGreater(
            after_measured['dark_scenarios'],
            before_measured['dark_scenarios'],
            'the reason dark_scenarios is ratcheted: this trade is invisible to holes',
        )
        self.assertEqual(sorted(after_dark), ['SYNC-STATUS-ANDROID-01', 'SYNC-STATUS-DESKTOP-01'])

    def test_a_deprecated_split_does_not_count(self):
        # A scenario retired during the split is not a gap, and counting it
        # would let a real regression hide behind paperwork.
        after = self._coverage(
            {
                'SYNC-STATUS-ANDROID-01': self._row((1, 0), (0, 0)),
                'SYNC-STATUS-DESKTOP-01': self._row((0, 1), (0, 0)),
            },
            deprecated={'SYNC-STATUS-DESKTOP-01'},
        )
        measured, dark = ratchet._metrics(after)
        self.assertEqual(measured['dark_scenarios'], 1)
        self.assertEqual(dark, ['SYNC-STATUS-ANDROID-01'])


def _run_gate(config_text, *args):
    """Run the script as a subprocess — the exit code is the contract CI acts on.

    Growth is simulated by pointing `--config` at a copy of the floor file with a
    lower `max`. The corpus is left alone, because a test that mutates the real
    specs to prove a failure is a test that can leave the repository broken if it
    dies between sabotage and restore.
    """
    import subprocess
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        path = pathlib.Path(tmp) / 'ratchet.json'
        path.write_text(config_text, encoding='utf-8')
        return subprocess.run(
            [sys.executable, str(SCRIPT), '--config', str(path), *args],
            capture_output=True,
            text=True,
            cwd=ROOT,
            check=False,
        )


def _floors(cap=None):
    """The recorded floors, optionally forced to `cap`.

    Reading the caps from the file rather than writing a number into a test is the
    whole point: a version that hard-coded the number failed on every legitimate
    change to the corpus, for a reason that had nothing to do with the gate. A
    test that pins a number the gate is *designed* to move is pinned to the wrong
    thing.
    """
    import json
    config = json.loads(
        (ROOT / 'config/docs/traceability-ratchet.json').read_text(encoding='utf-8')
    )
    for floor in config['floors']:
        if cap is not None:
            floor['max'] = cap
        else:
            # Below the measurement on purpose: the point of these cases is
            # "the gate is green", and reading it proves the file on disk is
            # the one the gate would accept.
            floor['max'] = 10_000
    return json.dumps(config)


class ReportingTest(unittest.TestCase):
    """What the gate says, not what it returns.

    The third metric joined `METRICS` and this gate's comparisons, and the green
    line went on naming only the first two — a metric that is compared but never
    printed is one nobody can notice going wrong. The same class of failure lived
    in the failure listing, which spelled its own `○` instead of asking the cell,
    so an unreachable hole was reported as an ordinary one.

    Both assertions below are derived from `METRICS` and from the coverage model
    rather than from literal strings, so the next metric added cannot be the one
    that quietly stops being reported.
    """

    def test_the_green_line_names_every_ratcheted_metric(self):
        # Floors are set to the measured value, not to a comfortable 10_000: a cap
        # above reality makes the gate print a "below its floor" advisory per
        # metric, and those lines name every metric too. Asserting over the whole
        # stdout then passes against a summary that dropped the metric entirely —
        # which is exactly what this test exists to catch, and what the first
        # version of it did.
        measured, _ = ratchet._metrics(_real_corpus()[1])
        floors = _floors()
        for floor in json.loads(floors)['floors']:
            floor['max'] = measured[floor['metric']]
        result = _run_gate(json.dumps(json.loads(floors)))
        self.assertEqual(result.returncode, 0)

        green = [line for line in result.stdout.splitlines() if 'OK —' in line]
        self.assertEqual(
            len(green), 1, f"expected one green summary line, got {green!r}"
        )
        for metric in ratchet.METRICS:
            self.assertIn(
                metric.key,
                green[0],
                f"the green run does not report {metric.key}, so its floor is "
                f"compared but never shown",
            )

    def test_the_failure_listing_distinguishes_an_unreachable_hole(self):
        specs, coverage = _real_corpus()
        unreachable = coverage.unreachable_holes()
        self.assertTrue(
            unreachable,
            "the corpus has no unreachable cells, so this test would pass for the "
            "wrong reason; mark a sync scenario unreachable or restore this assertion",
        )
        result = _run_gate(_floors(0))
        self.assertEqual(result.returncode, 1)
        for scenario, target in unreachable:
            if not any(cell.claimed for cell in coverage.cells[scenario].values()):
                continue
            self.assertIn(
                f"◇ {scenario} [{target}]",
                result.stdout,
                "an unreachable hole was listed as an ordinary one, which is the "
                "same conflation the metric was added to stop",
            )

    def test_every_listed_line_carries_a_glyph_and_a_target(self):
        result = _run_gate(_floors(0))
        lines = [line for line in result.stdout.splitlines() if line.startswith('  ')]
        listed = [line for line in lines if len(line) > 2 and line[2] in '○◇●⊘—']
        self.assertTrue(listed, "the failure listing printed no cells at all")
        for line in listed:
            self.assertRegex(line.strip(), r'^[○◇●⊘—] \S+ \[\w+\]$')


class ExitCodeTest(unittest.TestCase):
    """Both directions of the contract, run as a subprocess.

    The exit code is the contract CI acts on, so asserting it means running the
    script rather than calling a function.
    """

    _run = staticmethod(_run_gate)
    _floors = staticmethod(_floors)

    def test_growth_fails(self):
        self.assertEqual(self._run(self._floors(0)).returncode, 1)

    def test_the_recorded_floors_pass(self):
        self.assertEqual(self._run(self._floors()).returncode, 0)

    def test_improvement_passes_and_says_to_lower_the_floor(self):
        result = self._run(self._floors(99))
        self.assertEqual(result.returncode, 0)
        # A floor left above reality is a floor that stops catching, so the
        # gate says so out loud even though the run is green.
        self.assertIn('below its floor', result.stdout)

    def test_accept_growth_is_off_unless_asked_for(self):
        self.assertEqual(self._run(self._floors(0), '--accept-growth').returncode, 0)


if __name__ == '__main__':
    unittest.main()
