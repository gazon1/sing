"""Tests for scripts/maestro-allure.py — the Maestro → Allure contract.

The contract (`docs/decisions/2026-11-10-maestro-allure-integration-contract.md`)
is only worth anything if these properties hold:

* a testcase is identified by its flow path, not by its display name, and the
  identity is stable across conversions;
* a failure is never lost — not to a duplicate record and not to a missing file;
* a retry is a label on the flow that was retried, never a shard-wide claim and
  never a fabricated Allure attempt;
* shards that disagree about the run are refused, not merged;
* the committed fixtures have the shape the mapping depends on.

Run with: python3 -m unittest discover -s scripts/tests
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / "maestro-allure.py"
spec = importlib.util.spec_from_file_location("maestro_allure", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules["maestro_allure"] = mod  # must precede exec_module
spec.loader.exec_module(mod)

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures" / "maestro-allure"

REPORT = """<?xml version='1.0' encoding='UTF-8'?>
<testsuites>
  <testsuite name="Test Suite" device="AVD" tests="1" failures="{failures}" time="{time}">
    <testcase id="{name}" name="{name}" classname="{name}" file="{file}" time="{time}" status="{status}">
      {body}
    </testcase>
  </testsuite>
</testsuites>
"""

FLOW = """# a flow
appId: com.singularity.todo
name: {name}
tags:
{tags}---
- launchApp
"""


def write_flow(root: pathlib.Path, flow: str, name: str, tags: list[str]) -> None:
    path = root / flow
    path.parent.mkdir(parents=True, exist_ok=True)
    body = "".join(f"  - {tag}\n" for tag in tags)
    path.write_text(FLOW.format(name=name, tags=body), encoding="utf-8")


def write_report(shard: pathlib.Path, flow: str, name: str, status: str = "SUCCESS",
                 failure: str = "", time: float = 1.0) -> None:
    body = f"<failure>{failure}</failure>" if failure else ""
    shard.mkdir(parents=True, exist_ok=True)
    (shard / f"{pathlib.PurePosixPath(flow).stem}.xml").write_text(
        REPORT.format(name=name, file=flow, status=status, time=time, body=body,
                      failures=1 if failure else 0),
        encoding="utf-8",
    )


def write_shard(root: pathlib.Path, name: str, environment: dict | None = None,
                summary: dict | None = None) -> pathlib.Path:
    shard = root / name
    shard.mkdir(parents=True, exist_ok=True)
    (shard / "environment.json").write_text(
        json.dumps(environment or {"kind": "emulator", "api": 36}), encoding="utf-8"
    )
    (shard / "run-summary.json").write_text(
        json.dumps(summary or {"passed": [], "failed": [], "not_measured": [], "retried": []}),
        encoding="utf-8",
    )
    return shard


def run(args: list[str]) -> tuple[int, str]:
    err = io.StringIO()
    with contextlib.redirect_stderr(err):
        code = mod.main(args)
    return code, err.getvalue()


def results(out: pathlib.Path) -> dict[str, dict]:
    return {
        json.loads(path.read_text())["fullName"]: json.loads(path.read_text())
        for path in sorted(out.glob("*-result.json"))
    }


def manifest(out: pathlib.Path) -> dict:
    return json.loads((out / mod.MANIFEST_NAME).read_text())


class IdentityTest(unittest.TestCase):
    def test_conversion_is_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root, "Maestro/flows/tasks/01-create.yaml", "tasks-create", ["smoke"])
            shard = write_shard(root, "s1")
            write_report(shard, "Maestro/flows/tasks/01-create.yaml", "tasks-create")
            (root / "catalogue.txt").write_text("Maestro/flows/tasks/01-create.yaml\n")
            args = ["--results", str(shard), "--scope", "full", "--root", str(root),
                    "--catalogue", str(root / "catalogue.txt"), "--now", "2026-11-10T00:00:00Z"]
            first, second = root / "a", root / "b"
            self.assertEqual(run(args + ["--out", str(first)])[0], 0)
            self.assertEqual(run(args + ["--out", str(second)])[0], 0)
            self.assertEqual(sorted(p.name for p in first.glob("*-result.json")),
                             sorted(p.name for p in second.glob("*-result.json")))
            one, two = results(first), results(second)
            self.assertEqual(one.keys(), two.keys())
            for key in one:
                self.assertEqual(one[key]["uuid"], two[key]["uuid"])
                self.assertEqual(one[key]["historyId"], two[key]["historyId"])

    def test_absolute_file_normalises_and_outside_root_is_a_finding(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root, "Maestro/flows/tasks/01-create.yaml", "tasks-create", ["smoke"])
            shard = write_shard(root, "s1")
            write_report(shard, "Maestro/flows/tasks/01-create.yaml", "tasks-create")
            # An absolute path inside the checkout is the same flow.
            xml = (shard / "01-create.xml").read_text().replace(
                'file="Maestro/flows/tasks/01-create.yaml"',
                f'file="{root / "Maestro/flows/tasks/01-create.yaml"}"',
            )
            (shard / "01-create.xml").write_text(xml)
            (root / "catalogue.txt").write_text("Maestro/flows/tasks/01-create.yaml\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertIn("Maestro/flows/tasks/01-create.yaml", results(out))

            (shard / "01-create.xml").write_text(
                xml.replace(str(root), "/somewhere/else")
            )
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertEqual(results(out), {})
            self.assertIn("unknown flow", err)


class StatusTest(unittest.TestCase):
    def test_failure_is_real_text_and_no_fabricated_trace(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/tasks/01-create.yaml"
            write_flow(root, flow, "tasks-create", ["smoke", "scenario:TASK-CREATE-01"])
            shard = write_shard(root, "s1")
            write_report(shard, flow, "tasks-create", status="ERROR",
                         failure="Assertion is false: id: task_item buy milk is visible")
            write_report(shard, "Maestro/flows/tasks/02-pass.yaml", "tasks-pass")
            write_flow(root, "Maestro/flows/tasks/02-pass.yaml", "tasks-pass", ["smoke"])
            (root / "catalogue.txt").write_text(f"{flow}\nMaestro/flows/tasks/02-pass.yaml\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            failed = results(out)[flow]
            self.assertEqual(failed["status"], "failed")
            self.assertEqual(failed["statusDetails"]["message"],
                             "Assertion is false: id: task_item buy milk is visible")
            # The element text is the run's own words: carried through as the
            # trace, never replaced by an invented stack.
            self.assertEqual(failed["statusDetails"]["trace"],
                             "Assertion is false: id: task_item buy milk is visible")
            tags = {label["value"] for label in failed["labels"] if label["name"] == "tag"}
            self.assertEqual(tags, {"smoke", "scenario:TASK-CREATE-01"})
            passed = results(out)["Maestro/flows/tasks/02-pass.yaml"]
            self.assertEqual(passed["status"], "passed")
            self.assertEqual(passed["statusDetails"], {})

    def test_file_less_report_resolves_through_the_catalogue(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/attachments/01-add-url.yaml"
            write_flow(root, flow, "attachments-add-url", ["attachments"])
            shard = write_shard(root, "s1")
            write_report(shard, flow, "attachments-add-url")
            # No `file`: the declared name cannot identify the flow, the report's
            # own stem can.
            xml = (shard / "01-add-url.xml").read_text().replace(f' file="{flow}"', "")
            (shard / "01-add-url.xml").write_text(xml)
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertEqual(list(results(out)), [flow])

    def test_error_is_broken_and_skipped_is_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            shard = write_shard(root, "s1")
            for flow, name, status, body in (
                ("Maestro/flows/a.yaml", "a", "ERROR", "<error>device died</error>"),
                ("Maestro/flows/b.yaml", "b", "SUCCESS", "<skipped/>"),
            ):
                shard.mkdir(parents=True, exist_ok=True)
                (shard / f"{name}.xml").write_text(
                    REPORT.format(name=name, file=flow, status=status, time=1.0, body=body,
                                  failures=0)
                )
            (root / "catalogue.txt").write_text("Maestro/flows/a.yaml\nMaestro/flows/b.yaml\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(results(out)["Maestro/flows/a.yaml"]["status"], "broken")
            self.assertEqual(results(out)["Maestro/flows/b.yaml"]["status"], "skipped")


class DuplicateAndMissingTest(unittest.TestCase):
    def test_a_pass_never_replaces_a_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/tasks/01-create.yaml"
            write_flow(root, flow, "tasks-create", ["smoke"])
            one = write_shard(root, "s1", {"shard": 1, "shards": 2, "commit": "abc"})
            two = write_shard(root, "s2", {"shard": 2, "shards": 2, "commit": "abc"})
            write_report(one, flow, "tasks-create", status="SUCCESS")
            write_report(two, flow, "tasks-create", status="ERROR", failure="it broke")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(one), "--results", str(two), "--scope", "full",
                           "--root", str(root), "--catalogue", str(root / "catalogue.txt"),
                           "--run-id", "1", "--run-number", "1", "--run-attempt", "1",
                           "--commit", "abc", "--task-outcome", "passed", "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertEqual(results(out)[flow]["status"], "failed")
            data = manifest(out)
            self.assertTrue(any(f["kind"] == "duplicate conflict" for f in data["findings"]))
            self.assertFalse(data["publishable"])

    def test_equal_duplicates_keep_one_and_report_it(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/tasks/01-create.yaml"
            write_flow(root, flow, "tasks-create", [])
            one = write_shard(root, "s1")
            two = write_shard(root, "s2")
            write_report(one, flow, "tasks-create")
            write_report(two, flow, "tasks-create")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            run(["--results", str(one), "--results", str(two), "--scope", "full",
                 "--root", str(root), "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(len(results(out)), 1)
            kinds = [f["kind"] for f in manifest(out)["findings"]]
            self.assertIn("duplicate testcase", kinds)
            self.assertNotIn("duplicate conflict", kinds)

    def test_flow_with_no_report_is_missing_and_not_invented(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root, "Maestro/flows/a.yaml", "a", [])
            write_flow(root, "Maestro/flows/b.yaml", "b", [])
            shard = write_shard(root, "s1")
            write_report(shard, "Maestro/flows/a.yaml", "a")
            (root / "catalogue.txt").write_text("Maestro/flows/a.yaml\nMaestro/flows/b.yaml\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            data = manifest(out)
            self.assertEqual(data["missing"], ["Maestro/flows/b.yaml"])
            self.assertNotIn("Maestro/flows/b.yaml", results(out))

    def test_empty_report_is_a_finding(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            shard = write_shard(root, "s1")
            (shard / "empty.xml").write_text(
                '<?xml version="1.0"?><testsuites><testsuite name="Test Suite" tests="0"/>'
                "</testsuites>"
            )
            (root / "catalogue.txt").write_text("Maestro/flows/a.yaml\n")
            out = root / "out"
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertIn("empty report", err)
            self.assertTrue(any(f["kind"] == "empty report" for f in manifest(out)["findings"]))

    def test_non_object_environment_is_a_finding_not_a_crash(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1")
            (shard / "environment.json").write_text("null")
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertIn("environment.json unreadable", err)
            self.assertEqual(list(results(out)), [flow])
            self.assertFalse(manifest(out)["publishable"])

    def test_refuses_to_clean_a_directory_it_did_not_write(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1")
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            out.mkdir()
            foreign = out / "jvm-result.json"
            foreign.write_text('{"name": "a JVM test"}')
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 1)
            self.assertIn("refusing to clean", err)
            self.assertTrue(foreign.is_file())

    def test_unreadable_report_is_a_finding_not_a_crash(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            shard = write_shard(root, "s1")
            (shard / "broken.xml").write_text("<not xml")
            (root / "catalogue.txt").write_text("")
            out = root / "out"
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertEqual(results(out), {})
            self.assertIn("unreadable report", err)


class RetryAndIdentityTest(unittest.TestCase):
    def test_only_the_retried_flow_is_labelled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for name in ("a", "b"):
                write_flow(root, f"Maestro/flows/{name}.yaml", name, [])
            shard = write_shard(root, "s1", summary={
                "passed": ["a", "b"], "failed": [], "not_measured": [], "retried": ["b"],
            })
            write_report(shard, "Maestro/flows/a.yaml", "a")
            write_report(shard, "Maestro/flows/b.yaml", "b")
            (root / "catalogue.txt").write_text("Maestro/flows/a.yaml\nMaestro/flows/b.yaml\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            by_flow = results(out)
            a = {label["name"] for label in by_flow["Maestro/flows/a.yaml"]["labels"]}
            b = {label["name"]: label["value"] for label in by_flow["Maestro/flows/b.yaml"]["labels"]}
            self.assertNotIn("retried", a)
            self.assertEqual(b.get("retried"), "true")

    def test_shards_that_disagree_are_refused_and_write_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            one = write_shard(root, "s1", {"shard": 1, "shards": 2, "commit": "abc"})
            two = write_shard(root, "s2", {"shard": 2, "shards": 2, "commit": "def"})
            write_report(one, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, err = run(["--results", str(one), "--results", str(two), "--scope", "full",
                             "--root", str(root), "--catalogue", str(root / "catalogue.txt"),
                             "--commit", "abc", "--out", str(out)])
            self.assertEqual(code, 1)
            self.assertIn("identity disagreement", err)
            self.assertFalse(out.exists())

    def test_complete_identity_and_shard_set_is_publishable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", ["smoke"])
            shards = [write_shard(root, f"s{n}", {"shard": n, "shards": 2, "commit": "abc"})
                      for n in (1, 2)]
            for shard in shards:
                write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shards[0]), "--results", str(shards[1]),
                           "--scope", "smoke", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"),
                           "--run-id", "42", "--run-number", "7", "--run-attempt", "1",
                           "--commit", "abc", "--maestro-version", "2.10.0",
                           "--apk-sha256", "f" * 64, "--task-outcome", "passed",
                           "--out", str(out)])
            self.assertEqual(code, 0)
            data = manifest(out)
            self.assertTrue(data["complete_shards"])
            self.assertEqual(data["shards"], [1, 2])
            self.assertTrue(data["publishable"])
            self.assertEqual(json.loads((out / "executor.json").read_text())["type"], "github")

    def test_a_missing_run_summary_blocks_publication(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc"})
            (shard / "run-summary.json").unlink()
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"),
                           "--run-id", "42", "--run-number", "7", "--run-attempt", "1",
                           "--commit", "abc", "--task-outcome", "passed", "--out", str(out)])
            self.assertEqual(code, 0)
            data = manifest(out)
            self.assertTrue(data["complete_shards"])
            self.assertFalse(data["publishable"])
            self.assertTrue(any(f["kind"] == "run-summary.json missing" for f in data["findings"]))


class FixtureShapeTest(unittest.TestCase):
    """The mapping depends on the XML shape; a Maestro upgrade must fail here."""

    def test_no_attribute_only_outcome_and_file_is_a_relative_path(self) -> None:
        reports = sorted(FIXTURES.rglob("*.xml"))
        self.assertTrue(reports)
        for report in reports:
            for case in ET.parse(report).getroot().iter("testcase"):
                children = [child.tag for child in case]
                self.assertIn(
                    case.get("status"), ("SUCCESS", "ERROR"),
                    f"{report}: an unexpected status means the mapping table must be revisited",
                )
                if case.get("status") != "SUCCESS":
                    self.assertIn("failure", children, f"{report}: a failure without a failure element")
                self.assertFalse(case.get("file", "").startswith("/"), f"{report}: absolute flow path")

    def test_fixtures_convert_to_the_measured_counts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp) / "out"
            code, _ = run([
                "--results", str(FIXTURES / "full-shard1"),
                "--results", str(FIXTURES / "full-shard2"),
                "--scope", "full", "--out", str(out), "--commit", "deadbeef",
                "--now", "2026-11-10T00:00:00Z",
            ])
            self.assertEqual(code, 0)
            counts: dict[str, int] = {}
            for result in results(out).values():
                counts[result["status"]] = counts.get(result["status"], 0) + 1
            self.assertEqual(counts, {"failed": 3, "passed": 2})
            data = manifest(out)
            self.assertFalse(data["publishable"])
            self.assertEqual(data["counts"], counts)
            self.assertFalse(any(f["kind"] == "run-summary.json missing" for f in data["findings"]))


if __name__ == "__main__":
    unittest.main()
