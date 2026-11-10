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
import os
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
                             "--catalogue", str(root / "catalogue.txt"),
                             "--run-id", "1", "--run-number", "1", "--run-attempt", "1",
                             "--commit", "abc", "--maestro-version", "2.10.0",
                             "--apk-sha256", "f" * 64, "--task-outcome", "passed",
                             "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertEqual(results(out), {})
            self.assertIn("unknown flow", err)
            self.assertFalse(manifest(out)["publishable"])


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
                 "--root", str(root), "--catalogue", str(root / "catalogue.txt"),
                 "--run-id", "1", "--run-number", "1", "--run-attempt", "1",
                 "--commit", "abc", "--maestro-version", "2.10.0",
                 "--apk-sha256", "f" * 64, "--task-outcome", "passed", "--out", str(out)])
            self.assertEqual(len(results(out)), 1)
            data = manifest(out)
            kinds = [f["kind"] for f in data["findings"]]
            self.assertIn("duplicate testcase", kinds)
            self.assertNotIn("duplicate conflict", kinds)
            self.assertFalse(data["publishable"])

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
            write_report(shards[0], flow, "a")
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

    def test_missing_binary_identity_blocks_publication(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc"})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"),
                 "--run-id", "42", "--run-number", "7", "--run-attempt", "1",
                 "--commit", "abc", "--task-outcome", "passed", "--out", str(out)])
            data = manifest(out)
            self.assertTrue(data["complete_shards"])
            self.assertIsNone(data["identity"]["maestro_version"])
            self.assertFalse(data["publishable"])

    def test_unhashable_commit_is_a_disagreement_not_a_crash(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            one = write_shard(root, "s1", {"shard": 1, "shards": 2, "commit": ["abc"]})
            two = write_shard(root, "s2", {"shard": 2, "shards": 2, "commit": "abc"})
            write_report(one, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, err = run(["--results", str(one), "--results", str(two), "--scope", "full",
                             "--root", str(root), "--catalogue", str(root / "catalogue.txt"),
                             "--out", str(out)])
            self.assertEqual(code, 1)
            self.assertIn("identity disagreement", err)

    def test_dangling_symlink_out_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1")
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            os.symlink(root / "missing-target", out)
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 1)
            self.assertIn("is not a directory", err)

    def test_boolean_shard_fields_are_not_shard_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": True, "shards": True, "commit": "abc"})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                 "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                 "--maestro-version", "2.10.0", "--apk-sha256", "f" * 64,
                 "--task-outcome", "passed", "--out", str(out)])
            data = manifest(out)
            self.assertEqual(data["shards"], [])
            self.assertIsNone(data["shards_expected"])
            self.assertFalse(data["complete_shards"])
            self.assertFalse(data["publishable"])

    def test_empty_now_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            shard = write_shard(root, "s1")
            (root / "catalogue.txt").write_text("")
            out = root / "out"
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"),
                             "--now", "", "--out", str(out)])
            self.assertEqual(code, 1)
            self.assertIn("--now is empty", err)

    def test_malformed_now_is_an_error_not_a_traceback(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            shard = write_shard(root, "s1")
            (root / "catalogue.txt").write_text("")
            out = root / "out"
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"),
                             "--now", "yesterday", "--out", str(out)])
            self.assertEqual(code, 1)
            self.assertIn("--now is not an ISO8601 instant", err)

    def test_an_abbreviated_commit_is_the_same_commit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            full = "abc123def456" + "0" * 28
            # The runner records `git rev-parse --short=12`, the workflow the full SHA.
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc123def456"})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"),
                           "--run-id", "42", "--run-number", "7", "--run-attempt", "1",
                           "--commit", full, "--maestro-version", "2.10.0",
                           "--apk-sha256", "f" * 64, "--task-outcome", "passed",
                           "--out", str(out)])
            self.assertEqual(code, 0)
            data = manifest(out)
            self.assertEqual(data["identity"]["commit_sha"], full)
            self.assertTrue(data["publishable"])

    def test_a_second_attempt_keeps_the_history_and_gets_a_new_result(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc"})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            base = ["--results", str(shard), "--scope", "full", "--root", str(root),
                    "--catalogue", str(root / "catalogue.txt"), "--run-id", "42",
                    "--run-number", "7", "--run-attempt"]
            first_out, second_out = root / "one", root / "two"
            self.assertEqual(run(base + ["1", "--out", str(first_out)])[0], 0)
            self.assertEqual(run(base + ["2", "--out", str(second_out)])[0], 0)
            first, second = results(first_out)[flow], results(second_out)[flow]
            self.assertEqual(first["historyId"], second["historyId"])
            self.assertNotEqual(first["uuid"], second["uuid"])

    def test_the_runners_unknown_commit_is_incomplete_not_a_disagreement(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            # `_provenance.git_sha` writes this literal when git cannot read the
            # checkout: absent, not a different commit.
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "unknown"})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"),
                           "--run-id", "1", "--run-number", "1", "--run-attempt", "1",
                           "--commit", "abc123def456" + "0" * 28,
                           "--maestro-version", "2.10.0", "--apk-sha256", "f" * 64,
                           "--task-outcome", "passed", "--out", str(out)])
            self.assertEqual(code, 0)
            data = manifest(out)
            self.assertTrue(any(f["kind"] == "environment.json incomplete" for f in data["findings"]))
            self.assertFalse(data["publishable"])
            self.assertEqual(list(results(out)), [flow])

    def test_auto_outcome_reads_the_results_not_the_job(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc"})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            base = ["--results", str(shard), "--scope", "full", "--root", str(root),
                    "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                    "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                    "--maestro-version", "2.10.0", "--apk-sha256", "f" * 64,
                    "--task-outcome", "auto"]
            passing = root / "passing"
            self.assertEqual(run(base + ["--out", str(passing)])[0], 0)
            self.assertEqual(manifest(passing)["task_outcome"], "passed")

            write_report(shard, flow, "a", status="ERROR", failure="it broke")
            failing = root / "failing"
            self.assertEqual(run(base + ["--out", str(failing)])[0], 0)
            self.assertEqual(manifest(failing)["task_outcome"], "failed")

    def test_empty_binary_facts_are_recorded_as_absent(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc"})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                           "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                           "--maestro-version", "", "--apk-sha256", "",
                           "--task-outcome", "passed", "--out", str(out)])
            self.assertEqual(code, 0)
            data = manifest(out)
            self.assertIsNone(data["identity"]["maestro_version"])
            self.assertIsNone(data["identity"]["apk_sha256"])
            self.assertFalse(data["publishable"])

    def test_shard_commits_are_compared_pairwise(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            shards = [
                write_shard(root, f"s{n}", {"shard": n, "shards": 3, "commit": commit})
                for n, commit in enumerate(("abcdef0", "abcdef01", "abcdef02"), start=1)
            ]
            (root / "catalogue.txt").write_text("")
            args = ["--scope", "full", "--root", str(root),
                    "--catalogue", str(root / "catalogue.txt"), "--out", str(root / "out")]
            for shard in shards:
                args = ["--results", str(shard)] + args
            # The first abbreviation agrees with each of the others; only the
            # pairwise check sees that the other two are different commits.
            code, err = run(args)
            self.assertEqual(code, 1)
            self.assertIn("identity disagreement", err)

    def test_the_auto_outcome_of_nothing_is_unknown(self) -> None:
        self.assertEqual(mod.derived_outcome({}, []), "unknown")
        self.assertEqual(mod.derived_outcome({}, ["Maestro/flows/a.yaml"]), "failed")

    def test_auto_outcome_fails_a_run_that_stopped_early(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            measured, unmeasured = "Maestro/flows/a.yaml", "Maestro/flows/b.yaml"
            write_flow(root, measured, "a", [])
            write_flow(root, unmeasured, "b", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc"})
            write_report(shard, measured, "a")
            (root / "catalogue.txt").write_text(f"{measured}\n{unmeasured}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                           "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                           "--maestro-version", "2.10.0", "--apk-sha256", "f" * 64,
                           "--task-outcome", "auto", "--out", str(out)])
            self.assertEqual(code, 0)
            data = manifest(out)
            self.assertEqual(data["missing"], [unmeasured])
            # A clean set of results is not a pass: the flow the scope expected
            # and no report covers is the run failing to measure it.
            self.assertEqual(data["task_outcome"], "failed")

    def test_auto_outcome_fails_a_flow_the_summary_calls_unmeasured(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            first, second = "Maestro/flows/a.yaml", "Maestro/flows/b.yaml"
            for flow in (first, second):
                write_flow(root, flow, pathlib.PurePosixPath(flow).stem, [])
            shard = write_shard(
                root, "s1", {"shard": 1, "shards": 1, "commit": "abc"},
                summary={"passed": ["a"], "failed": [], "not_measured": ["b"], "retried": []},
            )
            # Both have a report; only the summary says the second went unmeasured.
            write_report(shard, first, "a")
            write_report(shard, second, "b")
            (root / "catalogue.txt").write_text(f"{first}\n{second}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                           "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                           "--maestro-version", "2.10.0", "--apk-sha256", "f" * 64,
                           "--task-outcome", "auto", "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertEqual(manifest(out)["not_measured"], [second])
            self.assertEqual(manifest(out)["task_outcome"], "failed")

    def test_a_falsy_commit_is_incomplete_not_known(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": 0})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"),
                           "--run-id", "1", "--run-number", "1", "--run-attempt", "1",
                           "--commit", "abc", "--maestro-version", "2.10.0",
                           "--apk-sha256", "f" * 64, "--task-outcome", "passed",
                           "--out", str(out)])
            self.assertEqual(code, 0)
            data = manifest(out)
            self.assertTrue(any(f["kind"] == "environment.json incomplete" for f in data["findings"]))
            self.assertFalse(data["publishable"])

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


class SameCommitTest(unittest.TestCase):
    """The cross-check sees git abbreviations, and only those, as the same commit."""

    def test_abbreviations_of_one_commit_agree(self) -> None:
        full = "abc123def456" + "0" * 28
        self.assertTrue(mod.same_commit(full, "abc123def456"))
        self.assertTrue(mod.same_commit("abc123def456", full))
        self.assertTrue(mod.same_commit(full, full))

    def test_no_other_pair_is_an_abbreviation(self) -> None:
        # `git rev-parse --short` prints at least seven characters, so a shorter
        # prefix is a different string; values of different types differ even
        # when they print alike; and two equal-length SHAs never share a prefix.
        self.assertFalse(mod.same_commit("abc", "abc123def456"))
        self.assertFalse(mod.same_commit("123", 123))
        self.assertFalse(mod.same_commit("unknown", "abc123def456"))
        self.assertFalse(mod.same_commit("abc123def456", "def456abc123"))

    def test_prefixes_of_one_commit_can_disagree_with_each_other(self) -> None:
        # Seven- and eight-character abbreviations are both legal, so the first
        # agrees with each of the others while those two disagree with each
        # other — which is why the shard check compares every pair.
        self.assertTrue(mod.same_commit("abcdef0", "abcdef01"))
        self.assertTrue(mod.same_commit("abcdef0", "abcdef02"))
        self.assertFalse(mod.same_commit("abcdef01", "abcdef02"))


class RobustnessTest(unittest.TestCase):
    def test_non_finite_duration_does_not_crash(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1")
            write_report(shard, flow, "a", time="NaN")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"),
                           "--now", "2026-11-10T00:00:00Z", "--out", str(out)])
            self.assertEqual(code, 0)
            result = results(out)[flow]
            self.assertEqual(result["start"], result["stop"])

    def test_relative_file_with_parent_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            shard = write_shard(root, "s1")
            write_report(shard, "../../etc/passwd", "x")
            (root / "catalogue.txt").write_text("")
            out = root / "out"
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"),
                             "--run-id", "1", "--run-number", "1", "--run-attempt", "1",
                             "--commit", "abc", "--maestro-version", "2.10.0",
                             "--apk-sha256", "f" * 64, "--task-outcome", "passed",
                             "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertEqual(results(out), {})
            self.assertIn("unknown flow", err)
            self.assertFalse(manifest(out)["publishable"])

    def test_unreadable_flow_file_is_a_finding(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            path = root / flow
            path.parent.mkdir(parents=True)
            path.write_bytes(b"\xff\xfe\x00bad")
            shard = write_shard(root, "s1")
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertIn("flow unreadable", err)
            self.assertEqual(list(results(out)), [flow])

    def test_an_interrupted_conversion_is_cleanable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1")
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            out.mkdir()
            # A conversion that wrote results and then died before the manifest.
            (out / "x-result.json").write_text(json.dumps({"testCaseId": "maestro:" + flow}))
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertTrue((out / mod.MANIFEST_NAME).is_file())
            self.assertFalse((out / "x-result.json").exists())

    def test_each_report_keeps_its_own_stop_time(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flows = {}
            for name in ("a", "b"):
                flow = f"Maestro/flows/{name}.yaml"
                flows[name] = flow
                write_flow(root, flow, name, [])
            shards = {}
            for name, shard_name in (("a", "s1"), ("b", "s2")):
                shards[name] = write_shard(root, shard_name, {"shard": 1 if name == "a" else 2,
                                                              "shards": 2, "commit": "abc"})
                write_report(shards[name], flows[name], name)
            os.utime(shards["a"] / "a.xml", (1_700_000_000, 1_700_000_000))
            os.utime(shards["b"] / "b.xml", (1_800_000_000, 1_800_000_000))
            (root / "catalogue.txt").write_text(f"{flows['a']}\n{flows['b']}\n")
            out = root / "out"
            run(["--results", str(shards["a"]), "--results", str(shards["b"]), "--scope", "full",
                 "--root", str(root), "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            a = results(out)[flows["a"]]
            b = results(out)[flows["b"]]
            self.assertNotEqual(a["stop"], b["stop"])

    def test_shard_count_disagreement_is_a_finding(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            one = write_shard(root, "s1", {"shard": 1, "shards": 2, "commit": "abc"})
            two = write_shard(root, "s2", {"shard": 2, "shards": 3, "commit": "abc"})
            write_report(one, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            run(["--results", str(one), "--results", str(two), "--scope", "full",
                 "--root", str(root), "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            kinds = [f["kind"] for f in manifest(out)["findings"]]
            self.assertIn("shard count disagreement", kinds)
            self.assertFalse(manifest(out)["publishable"])

    def test_zero_results_are_not_publishable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            # A clean shard that wrote no report at all: no findings, one missing
            # flow, zero results. Only the "at least one result" rule stops it.
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc"})
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"),
                           "--run-id", "1", "--run-number", "1", "--run-attempt", "1",
                           "--commit", "abc", "--task-outcome", "passed", "--out", str(out)])
            self.assertEqual(code, 0)
            data = manifest(out)
            self.assertEqual(results(out), {})
            self.assertEqual(data["missing"], [flow])
            self.assertEqual(data["findings"], [])
            self.assertFalse(data["publishable"])

    def test_zero_shard_count_is_not_publishable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 0, "commit": "abc"})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                 "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                 "--task-outcome", "passed", "--out", str(out)])
            data = manifest(out)
            self.assertTrue(any(f["kind"] == "shard count invalid" for f in data["findings"]))
            self.assertFalse(data["complete_shards"])
            self.assertFalse(data["publishable"])

    def test_malformed_summary_fields_are_a_blocking_finding(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc"},
                                summary={"passed": [], "failed": [], "not_measured": 5, "retried": 5})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            code, _ = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                           "--catalogue", str(root / "catalogue.txt"),
                           "--run-id", "1", "--run-number", "1", "--run-attempt", "1",
                           "--commit", "abc", "--maestro-version", "2.10.0",
                           "--apk-sha256", "f" * 64, "--task-outcome", "passed", "--out", str(out)])
            self.assertEqual(code, 0)
            self.assertEqual(list(results(out)), [flow])
            data = manifest(out)
            self.assertTrue(any(f["kind"] == "run-summary.json invalid" for f in data["findings"]))
            self.assertFalse(data["publishable"])

    def test_ambiguous_retry_stem_blocks_publication(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            first, second = ("Maestro/flows/tasks/01-create.yaml",
                             "Maestro/flows/projects/01-create.yaml")
            write_flow(root, first, "tasks-create", [])
            write_flow(root, second, "projects-create", [])
            one = write_shard(root, "s1", {"shard": 1, "shards": 2, "commit": "abc"},
                              summary={"passed": ["01-create"], "failed": [],
                                       "not_measured": [], "retried": ["01-create"]})
            two = write_shard(root, "s2", {"shard": 2, "shards": 2, "commit": "abc"})
            write_report(one, first, "tasks-create")
            write_report(two, second, "projects-create")
            (root / "catalogue.txt").write_text(f"{first}\n{second}\n")
            out = root / "out"
            run(["--results", str(one), "--results", str(two), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                 "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                 "--maestro-version", "2.10.0", "--apk-sha256", "f" * 64,
                 "--task-outcome", "passed", "--out", str(out)])
            data = manifest(out)
            self.assertTrue(any(f["kind"] == "summary flow ambiguous" for f in data["findings"]))
            self.assertFalse(data["publishable"])
            for result in results(out).values():
                labels = {l["name"]: l["value"] for l in result["labels"]}
                self.assertNotEqual(labels.get("retried"), "true")

    def test_unknown_retry_flow_blocks_publication(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1, "commit": "abc"},
                                summary={"passed": [], "failed": [], "not_measured": [],
                                         "retried": ["gone"]})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                 "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                 "--maestro-version", "2.10.0", "--apk-sha256", "f" * 64,
                 "--task-outcome", "passed", "--out", str(out)])
            data = manifest(out)
            self.assertTrue(any(f["kind"] == "summary flow unknown" for f in data["findings"]))
            self.assertFalse(data["publishable"])

    def test_environment_without_commit_blocks_publication(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1", {"shard": 1, "shards": 1})
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                 "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                 "--maestro-version", "2.10.0", "--apk-sha256", "f" * 64,
                 "--task-outcome", "passed", "--out", str(out)])
            data = manifest(out)
            self.assertTrue(any(f["kind"] == "environment.json incomplete" for f in data["findings"]))
            self.assertFalse(data["publishable"])

    def test_unsupported_status_is_a_finding_not_a_result(self) -> None:
        findings: list[dict] = []
        case = mod.TestCaseResult(classname="x", name="x", status="weird")
        self.assertIsNone(mod.status_of(case, "Maestro/flows/a.yaml", findings))
        self.assertEqual(findings[0]["kind"], "unsupported status")

    def test_xml_timestamp_beats_file_mtime(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1")
            write_report(shard, flow, "a")
            xml = (shard / "a.xml").read_text().replace(
                'time="1.0"', 'time="1.0" timestamp="2020-01-01T00:00:00"'
            )
            (shard / "a.xml").write_text(xml)
            os.utime(shard / "a.xml", (1_800_000_000, 1_800_000_000))
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            run(["--results", str(shard), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(results(out)[flow]["stop"], 1_577_836_800_000)

    def test_foreign_executor_without_results_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1")
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            out.mkdir()
            (out / "executor.json").write_text('{"name": "another producer"}')
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 1)
            self.assertIn("refusing to clean", err)
            self.assertTrue((out / "executor.json").is_file())

    def test_duplicate_shard_number_is_a_finding(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flows = {"a": "Maestro/flows/a.yaml", "b": "Maestro/flows/b.yaml"}
            for name, flow in flows.items():
                write_flow(root, flow, name, [])
            one = write_shard(root, "s1", {"shard": 1, "shards": 2, "commit": "abc"})
            two = write_shard(root, "s2", {"shard": 1, "shards": 2, "commit": "abc"})
            write_report(one, flows["a"], "a")
            write_report(two, flows["b"], "b")
            (root / "catalogue.txt").write_text(f"{flows['a']}\n{flows['b']}\n")
            out = root / "out"
            run(["--results", str(one), "--results", str(two), "--scope", "full", "--root", str(root),
                 "--catalogue", str(root / "catalogue.txt"), "--run-id", "1",
                 "--run-number", "1", "--run-attempt", "1", "--commit", "abc",
                 "--maestro-version", "2.10.0", "--apk-sha256", "f" * 64,
                 "--task-outcome", "passed", "--out", str(out)])
            data = manifest(out)
            self.assertTrue(any(f["kind"] == "incomplete shard set" for f in data["findings"]))
            self.assertFalse(data["publishable"])

    def test_out_naming_a_file_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            flow = "Maestro/flows/a.yaml"
            write_flow(root, flow, "a", [])
            shard = write_shard(root, "s1")
            write_report(shard, flow, "a")
            (root / "catalogue.txt").write_text(f"{flow}\n")
            out = root / "out"
            out.write_text("not a directory")
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(root / "catalogue.txt"), "--out", str(out)])
            self.assertEqual(code, 1)
            self.assertIn("is not a directory", err)

    def test_non_utf8_catalogue_is_an_error_not_a_traceback(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            shard = write_shard(root, "s1")
            catalogue = root / "catalogue.txt"
            catalogue.write_bytes(b"\xff\xfe\x00bad")
            out = root / "out"
            code, err = run(["--results", str(shard), "--scope", "full", "--root", str(root),
                             "--catalogue", str(catalogue), "--out", str(out)])
            self.assertEqual(code, 1)
            self.assertIn("cannot read the catalogue", err)


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
