"""Tests for the publishers of the frame-time evidence.

The published evidence is what the document quotes, and it has a shape of
its own: the desktop evidence is the aggregation of several runs, while the
test writes one run. The aggregation lives in the repository, and these
tests hold its shape:

* the published desktop evidence keeps every run next to its own samples,
  and the medians of the runs are the medians of the runs;
* the published desktop evidence keeps every scenario of the runs;
* the published desktop evidence says whether the runs were separate
  executions - three copies of one run are not three runs;
* the published Android evidence is the report of the measurement with the
  raw samples of every run still in it;
* the table of the document is rendered out of the report, so the numbers of
  the document are the numbers of the artifact.

Run with: python3 -m unittest discover -s scripts/tests
"""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

PUBLISH_ANDROID = ROOT / "scripts" / "publish-android-frame-time-evidence.py"
PUBLISH_DESKTOP = ROOT / "scripts" / "publish-desktop-frame-time-evidence.py"
RENDER_TABLE = ROOT / "scripts" / "render-android-frame-time-table.py"
UPDATE_DOC = ROOT / "scripts" / "update-frame-time-doc-tables.py"
DOC = ROOT / "docs" / "architecture" / "compose-frame-time-baseline.md"

SCENARIO = "agenda-upcoming-scroll"

# The screens the desktop baseline measures. A series that leaves one of them
# out is refused by the publisher, so a fixture of the tests that is meant to
# be published has to hold all of them.
DESKTOP_SCENARIOS = (
    "agenda-upcoming-scroll",
    "projects-list-scroll",
    "project-detail-open",
    "task-detail-open",
    "calendar-periods",
)

# The screens the Android baseline measures. The same rule holds there: the
# report has to name the whole set, not the set the run happened to pick.
ANDROID_SCENARIOS = (
    "agenda-scroll",
    "project-tasks-scroll",
    "project-detail-scroll",
    "task-detail-scroll",
    "calendar-periods",
)


def desktop_series(**overrides: object) -> list[dict]:
    """One report per required desktop scenario - a series that can be published."""
    return [scenario_report(scenario=name, **overrides) for name in DESKTOP_SCENARIOS]


def android_report(
    samples: int = 1,
    measured_runs: int = 1,
    builds: list[str] | None = None,
    scenarios: tuple[str, ...] = ANDROID_SCENARIOS,
    planned: list[str] | None = None,
) -> dict:
    """A report of the Android measurement, with the whole set of scenarios.

    `planned` is the list the report claims it was about. It defaults to the
    scenarios the report holds, and a report that planned less than it holds
    is the case the completeness rule has to catch.
    """
    versions = builds or ["0.1.0"] * samples
    return {
        "method": {
            "measuredRuns": measured_runs,
            "plannedScenarios": list(scenarios if planned is None else planned),
        },
        "scenarios": {
            name: {
                "samples": [
                    {"appVersionName": version, "measuredDurationsMs": [1.0]} for version in versions
                ],
                "summary": {
                    "totalFrames": 181,
                    "framesPerRunMedian": 181,
                    "jankyFramesPerRunMedian": 6,
                    "jankyPercentOverall": 3.07,
                    "gfxinfoP50MedianMs": 14,
                    "gfxinfoP95MedianMs": 21,
                    "measuredFramesMedianMs": 14.06,
                    "measuredFramesP95Ms": 21.18,
                },
            }
            for name in scenarios
        },
    }


def scenario_report(
    scenario: str = SCENARIO,
    open_median: float = 470.0,
    gesture_median: float = 80.0,
    opens: list[float] | None = None,
    gestures: list[float] | None = None,
    runs: int = 1,
    gestures_per_run: int = 2,
    distance_px: int = 400,
    dataset: dict[str, object] | None = None,
    subject: dict[str, object] | None = None,
    host_load: float = 3.5,
    **overrides: object,
) -> dict:
    """The report the test prints for one scenario of one run.

    The report agrees with itself: every measured run holds one open of the
    screen and `gestures_per_run` gestures, so the number of samples is the
    number the configuration claims. A report that does not agree with itself
    is refused by the publisher, so the fixture of the tests has to be a
    report the publisher accepts.
    """
    opens = opens if opens is not None else [460.0]
    gestures = gestures if gestures is not None else [70.0, 80.0]
    report = {
        "target": "jvm-desktop",
        "scenario": scenario,
        "dataset": dataset
        if dataset is not None
        else {
            "generator": "synthetic-backup-generator (#3090)",
            "profile": "10k",
            "seed": 42,
            "payloadChecksum": "db1d2450da4de66e06c206cd1800af48c3ca29023420cda2d5067b84be7e3f1d",
            "counts": {"tasks": 10000, "projects": 100},
        },
        "subject": subject if subject is not None else {"screen": "Upcoming"},
        "runs": runs,
        "warmupRuns": 2,
        "percentileConvention": "nearest-rank",
        "hostLoadAverage": host_load,
        "measurements": [
            {
                "name": "open",
                "kind": "navigation",
                "samples": len(opens),
                "medianMs": open_median,
                "p95Ms": max(opens),
                "samplesMs": opens,
            },
            {
                "name": "gesture",
                "kind": "drag",
                "gesturesPerRun": gestures_per_run,
                "distancePx": distance_px,
                "samples": len(gestures),
                "medianMs": gesture_median,
                "p95Ms": max(gestures),
                "samplesMs": gestures,
            },
        ],
    }
    report.update(overrides)
    return report


def junit_xml(timestamp: str, reports: list[dict]) -> str:
    """The JUnit XML of one run, with one report per scenario."""
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<testsuite name="com.singularity.todo.feature.flows.desktop.DesktopFrameTimeBaselineTest" '
        f'tests="{len(reports)}" timestamp="{timestamp}" hostname="localhost">',
    ]
    for report in reports:
        lines.append(
            '  <testcase name="{scenario}" classname="DesktopFrameTimeBaselineTest" time="12.3">'.format(
                scenario=report["scenario"]
            )
        )
        lines.append(f"    <system-out>FRAME_TIME_BASELINE {json.dumps(report)}</system-out>")
        lines.append("  </testcase>")
    lines.append("</testsuite>")
    return "\n".join(lines) + "\n"


class PublishDesktopEvidenceTest(unittest.TestCase):
    """The published desktop evidence is the aggregation of the runs."""

    def _publish(self, tmp: str, runs: list[tuple[str, list[dict]]]) -> subprocess.CompletedProcess:
        paths = []
        for index, (timestamp, reports) in enumerate(runs, start=1):
            path = Path(tmp) / f"run-{index}.xml"
            path.write_text(junit_xml(timestamp, reports), encoding="utf-8")
            paths.append(str(path))
        out = Path(tmp) / "evidence.json"
        return subprocess.run(
            [sys.executable, str(PUBLISH_DESKTOP), *paths, "--out", str(out)],
            capture_output=True,
            text=True,
        )

    def _evidence(self, tmp: str) -> dict:
        return json.loads((Path(tmp) / "evidence.json").read_text(encoding="utf-8"))

    def test_every_run_keeps_its_own_samples_and_its_own_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    (
                        "2026-11-24T15:36:47",
                        desktop_series(open_median=470.0, gesture_median=80.0, gestures=[70.0, 80.0]),
                    ),
                    (
                        "2026-11-24T15:37:35",
                        desktop_series(open_median=452.0, gesture_median=76.0, gestures=[60.0, 76.0]),
                    ),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            evidence = self._evidence(tmp)
            runs = evidence["scenarios"][SCENARIO]["runs"]
            self.assertEqual(len(runs), 2)
            self.assertEqual(runs[0]["ranAt"], "2026-11-24T15:36:47")
            self.assertEqual(runs[1]["ranAt"], "2026-11-24T15:37:35")
            self.assertEqual(runs[0]["measurements"]["gesture"]["medianMs"], 80.0)
            self.assertEqual(runs[1]["measurements"]["gesture"]["samplesMs"], [60.0, 76.0])

    def test_the_medians_of_the_runs_are_the_medians_of_the_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", desktop_series(open_median=470.0, gesture_median=80.0)),
                    ("2026-11-24T15:37:35", desktop_series(open_median=452.0, gesture_median=76.0)),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            results = self._evidence(tmp)["scenarios"][SCENARIO]["results"]
            self.assertEqual(results["gesture"]["medianMs"], 78.0)
            self.assertEqual(results["open"]["medianMs"], 461.0)
            self.assertEqual(results["gesture"]["medianSpreadMs"], [76.0, 80.0])

    def test_the_samples_of_every_run_are_pooled(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    (
                        "2026-11-24T15:36:47",
                        desktop_series(gestures=[70.0, 80.0], opens=[460.0]),
                    ),
                    (
                        "2026-11-24T15:37:35",
                        desktop_series(gestures=[60.0, 65.0], opens=[440.0]),
                    ),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            samples = self._evidence(tmp)["scenarios"][SCENARIO]["samples"]
            self.assertEqual(len(samples["gesture"]), 4)
            self.assertEqual(sorted(samples["gesture"]), [60.0, 65.0, 70.0, 80.0])
            self.assertEqual(len(samples["open"]), 2)

    def test_the_load_of_the_host_travels_with_the_numbers(self):
        """The numbers move with the load of the host, so the load is published."""
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", desktop_series(host_load=3.5)),
                    ("2026-11-24T15:37:35", desktop_series(host_load=4.0)),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            method = self._evidence(tmp)["scenarios"][SCENARIO]["method"]
            self.assertEqual(method["hostLoadAveragePerRun"], [3.5, 4.0])

    def test_every_scenario_of_the_runs_is_published(self):
        """The series measures several screens, and every one of them is published."""
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", desktop_series()),
                    ("2026-11-24T15:37:35", desktop_series()),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            evidence = self._evidence(tmp)
            self.assertEqual(sorted(evidence["scenarios"]), sorted(DESKTOP_SCENARIOS))
            self.assertEqual(len(evidence["scenarios"]["projects-list-scroll"]["runs"]), 2)

    def test_a_series_that_left_a_screen_out_is_not_published(self):
        """Three runs of four screens agree with each other and are not the baseline."""
        partial = [name for name in DESKTOP_SCENARIOS if name != "calendar-periods"]
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", [scenario_report(scenario=name) for name in partial]),
                    ("2026-11-24T15:37:35", [scenario_report(scenario=name) for name in partial]),
                ],
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("did not measure the whole desktop baseline", result.stderr)
            self.assertIn("calendar-periods", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_the_dataset_and_the_subject_of_the_runs_are_published(self):
        """A number without the dataset and the subject it was measured on is not a number."""
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", desktop_series()),
                    ("2026-11-24T15:37:35", desktop_series()),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            scenario = self._evidence(tmp)["scenarios"][SCENARIO]
            self.assertEqual(scenario["dataset"]["seed"], 42)
            self.assertEqual(scenario["dataset"]["profile"], "10k")
            self.assertEqual(scenario["subject"], {"screen": "Upcoming"})

    def test_runs_of_different_configurations_are_not_published_together(self):
        """A run over a hundred tasks and a run over a thousand are two experiments."""
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", desktop_series(dataset={"profile": "10k", "seed": 42})),
                    ("2026-11-24T15:37:35", desktop_series(dataset={"profile": "1k", "seed": 42})),
                ],
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("different experiment", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_runs_over_a_different_subject_are_not_published_together(self):
        """The task the scenario opens is part of what the scenario measured."""
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", desktop_series(subject={"screen": "Task 751"})),
                    ("2026-11-24T15:37:35", desktop_series(subject={"screen": "Task 42"})),
                ],
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("different experiment", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_runs_with_a_different_gesture_are_not_published_together(self):
        """The gesture is the configuration of the measurement, not a detail of the run."""
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", desktop_series(distance_px=400)),
                    ("2026-11-24T15:37:35", desktop_series(distance_px=800)),
                ],
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("distancePx", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_runs_that_measured_different_scenarios_are_not_published_together(self):
        """Two series are two series, and pooling them would publish a number of neither."""
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", desktop_series()),
                    (
                        "2026-11-24T15:37:35",
                        [scenario_report(scenario="projects-list-scroll")],
                    ),
                ],
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("different scenarios", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_a_run_that_does_not_match_its_own_configuration_is_not_published(self):
        """A report whose samples do not match its configuration is not a measurement."""
        with tempfile.TemporaryDirectory() as tmp:
            report = desktop_series(gestures=[70.0, 80.0])
            report[0]["measurements"][1]["samples"] = 9
            result = self._publish(tmp, [("2026-11-24T15:36:47", report)])
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("does not match its own configuration", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_copies_of_one_run_are_not_published_as_three_runs(self):
        """Three copies of one execution are one run, and Gradle skipped the rest."""
        with tempfile.TemporaryDirectory() as tmp:
            report = desktop_series()
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", report),
                    ("2026-11-24T15:36:47", report),
                ],
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("not separate executions", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())


class PublishAndroidEvidenceTest(unittest.TestCase):
    """The published Android evidence is the report of the measurement."""

    def _report(self, samples: int = 1, measured_runs: int = 1, builds: list[str] | None = None) -> dict:
        """A report of the whole set of scenarios, with the given number of runs.

        `measured_runs` is how many runs the measurement was asked for, and
        `samples` is how many of them are in the report.
        """
        return android_report(samples=samples, measured_runs=measured_runs, builds=builds)

    def _publish(self, tmp: str, report: dict) -> subprocess.CompletedProcess:
        report_path = Path(tmp) / "frame-time-report.json"
        report_path.write_text(json.dumps(report), encoding="utf-8")
        out = Path(tmp) / "evidence.json"
        return subprocess.run(
            [sys.executable, str(PUBLISH_ANDROID), str(report_path), "--out", str(out)],
            capture_output=True,
            text=True,
        )

    def test_the_report_keeps_its_samples_and_gains_its_provenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(tmp, self._report())
            self.assertEqual(result.returncode, 0, result.stderr)
            published = json.loads((Path(tmp) / "evidence.json").read_text(encoding="utf-8"))
            self.assertEqual(
                published["scenarios"]["agenda-scroll"]["samples"][0]["measuredDurationsMs"],
                [1.0],
            )
            self.assertEqual(published["evidence"]["publishedBy"], "scripts/publish-android-frame-time-evidence.py")

    def test_a_report_with_a_half_measured_scenario_is_not_published(self):
        """A scenario with two runs out of five says nothing about the other three."""
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(tmp, self._report(samples=2, measured_runs=5))
            self.assertEqual(result.returncode, 1)
            self.assertIn("do not have all their runs", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_a_report_measured_on_more_than_one_build_is_not_published(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(tmp, self._report(samples=2, measured_runs=2, builds=["0.1.0+abc", "0.1.0+def"]))
            self.assertEqual(result.returncode, 1)
            self.assertIn("more than one build", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_a_report_that_does_not_name_its_scenarios_is_not_published(self):
        """Two measured scenarios look like a complete measurement of two."""
        report = self._report()
        del report["method"]["plannedScenarios"]
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(tmp, report)
            self.assertEqual(result.returncode, 1)
            self.assertIn("does not say which scenarios", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_a_report_without_one_of_its_scenarios_is_not_published(self):
        """A scenario that was never measured is missing, not empty."""
        report = android_report(
            scenarios=tuple(name for name in ANDROID_SCENARIOS if name != "calendar-periods"),
            planned=list(ANDROID_SCENARIOS),
        )
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(tmp, report)
            self.assertEqual(result.returncode, 1)
            self.assertIn("calendar-periods", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())

    def test_a_report_of_one_scenario_is_not_published(self):
        """The set the run picked is not the set the baseline measures.

        The report of a run of a single scenario declares that one scenario
        as the one it was about, and it holds it - so everything the report
        says about itself is true, and it is still not a baseline.
        """
        report = android_report(scenarios=("agenda-scroll",), planned=["agenda-scroll"])
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(tmp, report)
            self.assertEqual(result.returncode, 1)
            self.assertIn("were not measured at all", result.stderr)
            self.assertIn("calendar-periods", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())


class UpdateDocTablesTest(unittest.TestCase):
    """The tables and the spread of the document come from the artifacts."""

    def _android_report(self, samples: int = 1, measured_runs: int = 1) -> dict:
        return android_report(
            samples=samples,
            measured_runs=measured_runs,
            builds=["0.1.0+gc9bd6166a5d3"] * samples,
        )

    def _desktop_evidence(self) -> dict:
        return {
            "scenarios": {
                SCENARIO: {
                    "method": {
                        "hostLoadAveragePerRun": [3.5, 3.5],
                        "measurements": [
                            {"name": "open", "kind": "navigation"},
                            {"name": "gesture", "kind": "drag"},
                        ],
                    },
                    "results": {
                        "open": {"medianMs": 78.0, "p95Ms": 80.0, "sampleCount": 2},
                        "gesture": {"medianMs": 78.0, "p95Ms": 90.0, "sampleCount": 4},
                    },
                    "runs": [
                        {
                            "index": 1,
                            "measurements": {
                                "open": {"medianMs": 80.0},
                                "gesture": {"medianMs": 80.0},
                            },
                            "hostLoadAverage": 3.5,
                        },
                        {
                            "index": 2,
                            "measurements": {
                                "open": {"medianMs": 76.0},
                                "gesture": {"medianMs": 76.0},
                            },
                            "hostLoadAverage": 3.5,
                        },
                    ],
                }
            }
        }

    def _update(self, tmp: str, report: dict, evidence: dict) -> tuple[subprocess.CompletedProcess, str]:
        report_path = Path(tmp) / "frame-time-report.json"
        report_path.write_text(json.dumps(report), encoding="utf-8")
        evidence_path = Path(tmp) / "desktop.json"
        evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
        doc = Path(tmp) / "doc.md"
        doc.write_text(DOC.read_text(encoding="utf-8"), encoding="utf-8")
        result = subprocess.run(
            [
                sys.executable,
                str(UPDATE_DOC),
                "--android-report",
                str(report_path),
                "--desktop-evidence",
                str(evidence_path),
                "--doc",
                str(doc),
            ],
            capture_output=True,
            text=True,
        )
        return result, doc.read_text(encoding="utf-8")

    def test_the_document_gets_the_numbers_of_the_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            result, text = self._update(tmp, self._android_report(), self._desktop_evidence())
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("0.1.0+gc9bd6166a5d3", text)
            self.assertIn("3,07 %", text)
            self.assertIn("Прогоны подряд дали по сценариям", text)
            self.assertIn("`agenda-upcoming-scroll` (шаг прокрутки) — 80,0 / 76,0 мс", text)
            self.assertIn("load average 3,5–3,5", text)

    def test_the_table_holds_a_row_per_scenario_and_measurement(self):
        with tempfile.TemporaryDirectory() as tmp:
            result, text = self._update(tmp, self._android_report(), self._desktop_evidence())
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("| `agenda-upcoming-scroll` | вход на экран | 78 мс | 80 мс | 2 |", text)
            self.assertIn("| `agenda-upcoming-scroll` | шаг прокрутки | 78 мс | 90 мс | 4 |", text)

    def test_an_incomplete_report_leaves_the_document_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            before = DOC.read_text(encoding="utf-8")
            result, text = self._update(
                tmp, self._android_report(samples=2, measured_runs=5), self._desktop_evidence()
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(text, before)


class RenderTableTest(unittest.TestCase):
    """The table of the document is rendered out of the report."""

    def _report(self, samples: int = 1, measured_runs: int = 1) -> dict:
        return android_report(
            samples=samples,
            measured_runs=measured_runs,
            builds=["0.1.0+gc9bd6166a5d3"] * samples,
        )

    def _render(self, tmp: str, report: dict) -> subprocess.CompletedProcess:
        report_path = Path(tmp) / "frame-time-report.json"
        report_path.write_text(json.dumps(report), encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(RENDER_TABLE), str(report_path)],
            capture_output=True,
            text=True,
        )

    def test_the_table_holds_a_row_per_scenario_with_the_numbers_of_the_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._render(tmp, self._report())
            self.assertEqual(result.returncode, 0, result.stderr)
            rows = [line for line in result.stdout.splitlines() if line.startswith("| `")]
            self.assertEqual(len(rows), len(ANDROID_SCENARIOS))
            self.assertIn("181", rows[0])
            self.assertIn("3,07 %", rows[0])
            self.assertIn("14,06 мс", rows[0])
            self.assertIn("0.1.0+gc9bd6166a5d3", rows[0])

    def test_a_report_with_a_half_measured_scenario_has_no_table(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._render(tmp, self._report(samples=2, measured_runs=5))
            self.assertEqual(result.returncode, 1)
            self.assertNotIn("| `", result.stdout)

    def test_a_report_without_one_of_its_scenarios_has_no_table(self):
        report = android_report(
            scenarios=tuple(name for name in ANDROID_SCENARIOS if name != "calendar-periods"),
            planned=list(ANDROID_SCENARIOS),
        )
        with tempfile.TemporaryDirectory() as tmp:
            result = self._render(tmp, report)
            self.assertEqual(result.returncode, 1)
            self.assertNotIn("| `", result.stdout)
