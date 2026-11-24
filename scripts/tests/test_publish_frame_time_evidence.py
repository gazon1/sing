"""Tests for the publishers of the frame-time evidence.

The published evidence is what the document quotes, and it has a shape of
its own: the desktop evidence is the aggregation of several runs, while the
test writes one run. The aggregation lives in the repository, and these
tests hold its shape:

* the published desktop evidence keeps every run next to its own samples,
  and the medians of the runs are the medians of the runs;
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


def run_report(scroll_median: float, open_median: float, scrolls: list[float], opens: list[float]) -> str:
    """The report the test prints on one run."""
    return json.dumps(
        {
            "target": "jvm-desktop",
            "scenario": "agenda-upcoming-scroll",
            "tasks": 1000,
            "runs": 5,
            "warmupRuns": 2,
            "dragsPerRun": 10,
            "dragDistancePx": 400,
            "openTimelineMedianMs": open_median,
            "openTimelineSamples": len(opens),
            "openTimelineSamplesMs": opens,
            "scrollMedianMs": scroll_median,
            "scrollP95Ms": max(scrolls),
            "scrollSamples": len(scrolls),
            "scrollSamplesMs": scrolls,
            "composedRows": 13,
            "hostLoadAverage": 3.5,
            "percentileConvention": "nearest-rank",
        }
    )


def junit_xml(timestamp: str, report: str) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<testsuite name="com.singularity.todo.feature.flows.agenda.AgendaFrameTimeBaselineTest" '
        f'tests="1" timestamp="{timestamp}" hostname="localhost">\n'
        '  <testcase name="upcoming_timeline_frame_cost" classname="AgendaFrameTimeBaselineTest" '
        'time="12.3">\n'
        f"    <system-out>FRAME_TIME_BASELINE {report}</system-out>\n"
        "  </testcase>\n"
        "</testsuite>\n"
    )


class PublishDesktopEvidenceTest(unittest.TestCase):
    """The published desktop evidence is the aggregation of the runs."""

    def _publish(self, tmp: str, runs: list[tuple[str, str]]) -> subprocess.CompletedProcess:
        paths = []
        for index, (timestamp, report) in enumerate(runs, start=1):
            path = Path(tmp) / f"run-{index}.xml"
            path.write_text(junit_xml(timestamp, report), encoding="utf-8")
            paths.append(str(path))
        out = Path(tmp) / "evidence.json"
        return subprocess.run(
            [sys.executable, str(PUBLISH_DESKTOP), *paths, "--out", str(out)],
            capture_output=True,
            text=True,
        )

    def test_every_run_keeps_its_own_samples_and_its_own_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", run_report(80.0, 470.0, [70.0, 80.0, 90.0], [460.0, 470.0])),
                    ("2026-11-24T15:37:35", run_report(76.0, 452.0, [60.0, 76.0, 80.0], [440.0, 452.0])),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            evidence = json.loads((Path(tmp) / "evidence.json").read_text(encoding="utf-8"))
            self.assertEqual(len(evidence["runs"]), 2)
            self.assertEqual(evidence["runs"][0]["ranAt"], "2026-11-24T15:36:47")
            self.assertEqual(evidence["runs"][1]["ranAt"], "2026-11-24T15:37:35")
            self.assertEqual(evidence["runs"][0]["scrollMedianMs"], 80.0)
            self.assertEqual(evidence["runs"][1]["scrollSamplesMs"], [60.0, 76.0, 80.0])

    def test_the_medians_of_the_runs_are_the_medians_of_the_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", run_report(80.0, 470.0, [70.0], [460.0])),
                    ("2026-11-24T15:37:35", run_report(76.0, 452.0, [60.0], [440.0])),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            evidence = json.loads((Path(tmp) / "evidence.json").read_text(encoding="utf-8"))
            self.assertEqual(evidence["results"]["scrollMedianMs"], 78.0)
            self.assertEqual(evidence["results"]["openTimelineMedianMs"], 461.0)
            self.assertEqual(evidence["results"]["scrollMedianSpreadMs"], [76.0, 80.0])

    def test_the_samples_of_every_run_are_pooled(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", run_report(80.0, 470.0, [70.0, 80.0], [460.0])),
                    ("2026-11-24T15:37:35", run_report(76.0, 452.0, [60.0], [440.0])),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            evidence = json.loads((Path(tmp) / "evidence.json").read_text(encoding="utf-8"))
            self.assertEqual(evidence["samples"]["scrollSampleCount"], 3)
            self.assertEqual(sorted(evidence["samples"]["scrollMs"]), [60.0, 70.0, 80.0])
            self.assertEqual(evidence["samples"]["openTimelineSampleCount"], 2)

    def test_the_load_of_the_host_travels_with_the_numbers(self):
        """The numbers move with the load of the host, so the load is published."""
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(
                tmp,
                [
                    ("2026-11-24T15:36:47", run_report(80.0, 470.0, [70.0], [460.0])),
                    ("2026-11-24T15:37:35", run_report(76.0, 452.0, [60.0], [440.0])),
                ],
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            evidence = json.loads((Path(tmp) / "evidence.json").read_text(encoding="utf-8"))
            self.assertEqual(evidence["method"]["hostLoadAveragePerRun"], [3.5, 3.5])

    def test_copies_of_one_run_are_not_published_as_three_runs(self):
        """Three copies of one execution are one run, and Gradle skipped the rest."""
        with tempfile.TemporaryDirectory() as tmp:
            report = run_report(80.0, 470.0, [70.0], [460.0])
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
        """A report of one scenario with the given number of runs.

        `measured_runs` is how many runs the measurement was asked for, and
        `samples` is how many of them are in the report.
        """
        versions = builds or ["0.1.0"] * samples
        return {
            "method": {"measuredRuns": measured_runs, "plannedScenarios": ["agenda-scroll"]},
            "scenarios": {
                "agenda-scroll": {
                    "samples": [
                        {"appVersionName": version, "measuredDurationsMs": [1.0]} for version in versions
                    ],
                    "summary": {"totalFrames": 181, "measuredFramesMedianMs": 14.06},
                }
            },
        }

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
        report = self._report()
        report["method"]["plannedScenarios"] = ["agenda-scroll", "calendar-periods"]
        with tempfile.TemporaryDirectory() as tmp:
            result = self._publish(tmp, report)
            self.assertEqual(result.returncode, 1)
            self.assertIn("calendar-periods", result.stderr)
            self.assertFalse((Path(tmp) / "evidence.json").exists())


class UpdateDocTablesTest(unittest.TestCase):
    """The tables and the spread of the document come from the artifacts."""

    def _android_report(self, samples: int = 1, measured_runs: int = 1) -> dict:
        return {
            "method": {"measuredRuns": measured_runs, "plannedScenarios": ["agenda-scroll"]},
            "scenarios": {
                "agenda-scroll": {
                    "samples": [{"appVersionName": "0.1.0+gc9bd6166a5d3"} for _ in range(samples)],
                    "summary": {
                        "framesPerRunMedian": 181,
                        "jankyFramesPerRunMedian": 6,
                        "jankyPercentOverall": 3.07,
                        "gfxinfoP50MedianMs": 14,
                        "gfxinfoP95MedianMs": 21,
                        "measuredFramesMedianMs": 14.06,
                        "measuredFramesP95Ms": 21.18,
                    },
                }
            },
        }

    def _desktop_evidence(self) -> dict:
        return {
            "method": {"hostLoadAveragePerRun": [3.5, 3.5]},
            "results": {
                "openTimelineMedianMs": 78.0,
                "scrollMedianMs": 78.0,
                "scrollP95Ms": 90.0,
            },
            "runs": [
                {
                    "openTimelineMedianMs": 80.0,
                    "openTimelineSamplesMs": [80.0],
                    "scrollMedianMs": 80.0,
                    "scrollSamplesMs": [70.0, 80.0, 90.0],
                },
                {
                    "openTimelineMedianMs": 76.0,
                    "openTimelineSamplesMs": [76.0],
                    "scrollMedianMs": 76.0,
                    "scrollSamplesMs": [60.0, 76.0, 80.0],
                },
            ],
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
            self.assertIn("Прогоны подряд дали медиану шага 80,0 / 76,0 мс", text)
            self.assertIn("load average 3,5, 3,5", text)

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
        return {
            "method": {"measuredRuns": measured_runs, "plannedScenarios": ["agenda-scroll"]},
            "scenarios": {
                "agenda-scroll": {
                    "samples": [{"appVersionName": "0.1.0+gc9bd6166a5d3"} for _ in range(samples)],
                    "summary": {
                        "framesPerRunMedian": 181,
                        "jankyFramesPerRunMedian": 6,
                        "jankyPercentOverall": 3.07,
                        "gfxinfoP50MedianMs": 14,
                        "gfxinfoP95MedianMs": 21,
                        "measuredFramesMedianMs": 14.06,
                        "measuredFramesP95Ms": 21.18,
                    },
                }
            },
        }

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
            self.assertEqual(len(rows), 1)
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
        report = self._report()
        report["method"]["plannedScenarios"] = ["agenda-scroll", "calendar-periods"]
        with tempfile.TemporaryDirectory() as tmp:
            result = self._render(tmp, report)
            self.assertEqual(result.returncode, 1)
            self.assertNotIn("| `", result.stdout)
