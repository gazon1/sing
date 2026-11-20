"""Tests for scripts/check-maestro-flow-qualification.py."""

import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import yaml

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent
    / "check-maestro-flow-qualification.py"
)
spec = importlib.util.spec_from_file_location(
    "check_maestro_flow_qualification", MODULE_PATH
)
mod = importlib.util.module_from_spec(spec)
assert spec and spec.loader
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)


def write_flow(path: pathlib.Path, body: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


FLOW_OK = "appId: com.singularity.todo\n---\n- assertVisible: ok\n"
FLOW_SMOKE = "tags:\n- smoke\n---\nappId: com.singularity.todo\n---\n- assertVisible: ok\n"
FLOW_MANUAL = "tags:\n- manual\n---\nappId: com.singularity.todo\n---\n- assertVisible: ok\n"
FLOW_AUTH_LIVE = "tags:\n- auth-live\n---\nappId: com.singularity.todo\n---\n- assertVisible: ok\n"


class FingerprintTest(unittest.TestCase):
    def test_helper_change_changes_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root / "Maestro/helpers/h.yaml", "appId: x\n---\n- tapOn: a\n")
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- runFlow: ../../helpers/h.yaml\n- assertVisible: a\n",
            )
            fp1 = mod.fingerprint("Maestro/flows/tasks/01-a.yaml", root)
            write_flow(root / "Maestro/helpers/h.yaml", "appId: x\n---\n- tapOn: b\n")
            fp2 = mod.fingerprint("Maestro/flows/tasks/01-a.yaml", root)
        self.assertNotEqual(fp1, fp2)

    def test_fingerprint_includes_transitive_helpers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root / "Maestro/helpers/b.yaml", "appId: x\n---\n- tapOn: b\n")
            write_flow(
                root / "Maestro/helpers/a.yaml",
                "appId: x\n---\n- runFlow:\n    file: b.yaml\n",
            )
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- runFlow: ../../helpers/a.yaml\n",
            )
            deps = mod.flow_dependencies("Maestro/flows/tasks/01-a.yaml", root)
        self.assertIn("Maestro/helpers/a.yaml", deps)
        self.assertIn("Maestro/helpers/b.yaml", deps)


class AdmissionTest(unittest.TestCase):
    def _tree(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        flow = "Maestro/flows/tasks/01-a.yaml"
        write_flow(root / flow, FLOW_OK)
        catalogue = root / "catalogue.txt"
        catalogue.write_text(flow + "\n")
        baseline = root / "baseline.yaml"
        evidence = root / "evidence.yaml"
        evidence.write_text(
            yaml.safe_dump(
                {"schema": 1, "required_attempts": 3, "qualifications": {}, "exceptions": {}},
                sort_keys=False,
            )
        )
        return root, flow, catalogue, baseline, evidence

    def _scan(self, root, catalogue, baseline, evidence):
        return mod.scan(root, catalogue, baseline, evidence)

    def test_new_flow_cannot_be_admitted_by_baseline_expansion(self):
        root, _flow, catalogue, baseline, evidence = self._tree()
        flow = "Maestro/flows/tasks/02-new.yaml"
        write_flow(root / flow, FLOW_OK)
        catalogue.write_text(flow + "\n")
        fp = mod.fingerprint(flow, root)
        baseline.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "initial_flows": [],
                    "flows": {flow: fp},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["baseline expansion"])

    def test_new_blocking_flow_is_unqualified(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["unqualified"])
        self.assertEqual(findings[0].flow, flow)

    def test_baselined_flow_is_admitted(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        fp = mod.fingerprint(flow, root)
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {flow: fp}}, sort_keys=False))
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["baselined"], 1)

    def test_changed_flow_needs_requalification(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(
            yaml.safe_dump({"schema": 1, "flows": {flow: "sha256:" + "0" * 64}}, sort_keys=False)
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["stale baseline"])

    def test_manual_flow_excluded(self):
        root, _flow, catalogue, baseline, evidence = self._tree()
        flow = "Maestro/flows/auth/01-manual.yaml"
        write_flow(root / flow, FLOW_MANUAL)
        catalogue.write_text(flow + "\n")
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["flows"], 0)

    def test_auth_live_flow_excluded(self):
        root, _flow, catalogue, baseline, evidence = self._tree()
        flow = "Maestro/flows/auth/01-live.yaml"
        write_flow(root / flow, FLOW_AUTH_LIVE)
        catalogue.write_text(flow + "\n")
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["flows"], 0)

    def _qualified_record(self, fp, outcomes):
        attempts = []
        for i, outcome in enumerate(outcomes, start=1):
            attempts.append(
                {
                    "attempt": i,
                    "outcome": outcome,
                    "environment": {"kind": "device", "api": 35, "device": "RMX3938", "serial": "s"},
                    "commit": "abc123",
                    "apk_commit_sha": "abc123",
                    "maestro_version": "2.0.0",
                }
            )
        return {
            "fingerprint": fp,
            "environment": {"kind": "device", "api": 35, "device": "RMX3938"},
            "commit": "abc123",
            "apk_commit_sha": "abc123",
            "maestro_version": "2.0.0",
            "attempts": attempts,
        }

    def test_qualified_record_admitted(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {flow: self._qualified_record(fp, ["pass", "pass", "pass"])},
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["qualified"], 1)

    def test_top_level_metadata_does_not_fill_missing_attempt_environment(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        record = self._qualified_record(fp, ["pass", "pass", "pass"])
        record["attempts"][1].pop("environment", None)
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {flow: record},
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["not qualified"])
        self.assertIn("attempt 2 does not record a documented environment", findings[0].detail)

    def test_top_level_metadata_does_not_fill_missing_attempt_build_identity(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        record = self._qualified_record(fp, ["pass", "pass", "pass"])
        record["attempts"][0].pop("commit", None)
        record["attempts"][0].pop("apk_commit_sha", None)
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {flow: record},
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["not qualified"])
        self.assertIn("attempt 1 does not record build identity", findings[0].detail)

    def test_top_level_metadata_does_not_fill_missing_attempt_maestro_version(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        record = self._qualified_record(fp, ["pass", "pass", "pass"])
        record["attempts"][2].pop("maestro_version", None)
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {flow: record},
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["not qualified"])
        self.assertIn("attempt 3 does not record the Maestro version", findings[0].detail)

    def test_duplicate_attempt_numbers_not_qualified(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        record = self._qualified_record(fp, ["pass", "pass", "pass"])
        # duplicate attempt 1 instead of sequential 1,2,3
        record["attempts"][1] = dict(record["attempts"][0])
        record["attempts"][1]["attempt"] = 1
        record["attempts"][2] = dict(record["attempts"][0])
        record["attempts"][2]["attempt"] = 1
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {flow: record},
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["not qualified"])
        self.assertIn("sequential attempt number", findings[0].detail)

    def test_missing_attempt_number_not_qualified(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        record = self._qualified_record(fp, ["pass", "pass", "pass"])
        record["attempts"][1].pop("attempt", None)
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {flow: record},
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["not qualified"])
        self.assertIn("sequential attempt number", findings[0].detail)

    def test_boolean_required_attempts_defaults_to_three(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        record = self._qualified_record(fp, ["pass"])
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": True,
                    "qualifications": {flow: record},
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["not qualified"])
        self.assertIn("3 required", findings[0].detail)

    def test_flaky_not_qualified(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {
                        flow: self._qualified_record(fp, ["test_failure", "pass", "pass"])
                    },
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["not qualified"])

    def test_infra_failure_not_qualified(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {
                        flow: self._qualified_record(fp, ["pass", "infra_failure", "pass"])
                    },
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["not qualified"])

    def test_exception_admitted_when_pinned(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        fp = mod.fingerprint(flow, root)
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {},
                    "exceptions": {
                        flow: {"fingerprint": fp, "reason": "known env issue", "evidence": "#3052"}
                    },
                },
                sort_keys=False,
            )
        )
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["excepted"], 1)

    def test_exception_without_fingerprint_rejected(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        baseline.write_text(yaml.safe_dump({"schema": 1, "flows": {}}, sort_keys=False))
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {},
                    "exceptions": {flow: {"reason": "x", "evidence": "#1"}},
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["invalid exception"])


class RepositoryStateTest(unittest.TestCase):
    def test_gate_passes_on_repository(self):
        root = pathlib.Path(__file__).resolve().parent.parent.parent
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH)],
            cwd=root,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class QualificationRunnerPolicyTest(unittest.TestCase):
    def test_attempts_below_policy_cannot_record_qualification(self):
        root = pathlib.Path(__file__).resolve().parent.parent.parent
        runner = root / "scripts" / "maestro-qualify-flows.py"
        flow = "Maestro/flows/tasks/01-set-priority.yaml"
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            evidence = tmp_path / "evidence.yaml"
            evidence.write_text(
                yaml.safe_dump(
                    {"schema": 1, "required_attempts": 3, "qualifications": {}, "exceptions": {}},
                    sort_keys=False,
                ),
                encoding="utf-8",
            )
            out = tmp_path / "out"
            result = subprocess.run(
                [
                    sys.executable,
                    str(runner),
                    "--flow",
                    flow,
                    "--attempts",
                    "1",
                    "--record",
                    "--evidence",
                    str(evidence),
                    "--out",
                    str(out),
                ],
                cwd=root,
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            combined = result.stdout + result.stderr
            self.assertIn("below required_attempts=3", combined)
            data = yaml.safe_load(evidence.read_text(encoding="utf-8")) or {}
            self.assertEqual((data.get("qualifications") or {}), {})
            self.assertFalse(out.exists(), "runner must not execute attempts or create artifacts")

    def test_extra_args_cannot_override_maestro_max_retries(self):
        root = pathlib.Path(__file__).resolve().parent.parent.parent
        runner = root / "scripts" / "maestro-qualify-flows.py"
        flow = "Maestro/flows/tasks/01-set-priority.yaml"
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            evidence = tmp_path / "evidence.yaml"
            evidence.write_text(
                yaml.safe_dump(
                    {"schema": 1, "required_attempts": 3, "qualifications": {}, "exceptions": {}},
                    sort_keys=False,
                ),
                encoding="utf-8",
            )
            for extra in (["--", "--maestro-max-retries", "2"], ["--", "--maestro-max-retries=2"]):
                result = subprocess.run(
                    [
                        sys.executable,
                        str(runner),
                        "--flow",
                        flow,
                        "--attempts",
                        "3",
                        "--evidence",
                        str(evidence),
                    ]
                    + extra,
                    cwd=root,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertIn("must not include --maestro-max-retries", result.stdout + result.stderr)


class QualificationRunnerOutcomeTest(unittest.TestCase):
    def setUp(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows", runner_path)
        self.mod = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = self.mod
        spec.loader.exec_module(self.mod)

    def test_retried_flow_in_run_summary_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "01-a.xml").write_text(
                "<?xml version='1.0'?>\n"
                "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>",
                encoding="utf-8",
            )
            (d / "run-summary.json").write_text(
                json.dumps(
                    {
                        "retried": ["01-a"],
                        "not_measured": [],
                        "passed": ["01-a"],
                        "failed": [],
                    }
                ),
                encoding="utf-8",
            )
            attempt = self.mod.classify_attempt("Maestro/flows/tasks/01-a.yaml", d, 0)
        self.assertNotEqual(attempt.outcome, "pass")
        self.assertEqual(attempt.outcome, "test_failure")
        self.assertIn("retried internally", attempt.reason)

    def test_missing_run_summary_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "01-a.xml").write_text(
                "<?xml version='1.0'?>\n"
                "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>",
                encoding="utf-8",
            )
            attempt = self.mod.classify_attempt("Maestro/flows/tasks/01-a.yaml", d, 0)
        self.assertEqual(attempt.outcome, "infra_failure")
        self.assertIn("run-summary.json missing or invalid", attempt.reason)

    def test_malformed_run_summary_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "01-a.xml").write_text(
                "<?xml version='1.0'?>\n"
                "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>",
                encoding="utf-8",
            )
            (d / "run-summary.json").write_text("{not-json", encoding="utf-8")
            attempt = self.mod.classify_attempt("Maestro/flows/tasks/01-a.yaml", d, 0)
        self.assertEqual(attempt.outcome, "infra_failure")
        self.assertIn("run-summary.json missing or invalid", attempt.reason)

    def test_missing_passed_membership_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "01-a.xml").write_text(
                "<?xml version='1.0'?>\n"
                "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>",
                encoding="utf-8",
            )
            (d / "run-summary.json").write_text(
                json.dumps({"passed": [], "failed": [], "retried": [], "not_measured": []}),
                encoding="utf-8",
            )
            attempt = self.mod.classify_attempt("Maestro/flows/tasks/01-a.yaml", d, 0)
        self.assertEqual(attempt.outcome, "infra_failure")
        self.assertIn("does not confirm flow passed", attempt.reason)

    def test_contradictory_failed_membership_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "01-a.xml").write_text(
                "<?xml version='1.0'?>\n"
                "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>",
                encoding="utf-8",
            )
            (d / "run-summary.json").write_text(
                json.dumps({"passed": ["01-a"], "failed": ["01-a"], "retried": [], "not_measured": []}),
                encoding="utf-8",
            )
            attempt = self.mod.classify_attempt("Maestro/flows/tasks/01-a.yaml", d, 0)
        self.assertEqual(attempt.outcome, "test_failure")
        self.assertIn("reports flow as failed", attempt.reason)

    def test_stale_passing_artifacts_not_used_on_failed_invocation(self):
        flow = "Maestro/flows/tasks/01-a.yaml"
        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp)
            attempt_dir = out / "01-a" / "attempt-1"
            attempt_dir.mkdir(parents=True)
            passing_junit = (
                "<?xml version='1.0'?>\n"
                "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>"
            )
            (attempt_dir / "01-a.xml").write_text(passing_junit, encoding="utf-8")
            (attempt_dir / "run-summary.json").write_text(
                json.dumps({"passed": ["01-a"], "failed": [], "retried": [], "not_measured": []}),
                encoding="utf-8",
            )
            (attempt_dir / "environment.json").write_text("{}", encoding="utf-8")
            # Failed new invocation produces no fresh reports; stale files must not count.
            with mock.patch.object(self.mod.subprocess, "run", return_value=mock.Mock(returncode=2)):
                attempt = self.mod.run_one_attempt(flow, 1, out, None, None, False, False, [])
        self.assertEqual(attempt.outcome, "infra_failure")
        self.assertNotIn("SUCCESS", attempt.reason or "")

    def test_nonzero_return_code_with_passing_report_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "01-a.xml").write_text(
                "<?xml version='1.0'?>\n"
                "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>",
                encoding="utf-8",
            )
            (d / "run-summary.json").write_text(
                json.dumps({"passed": ["01-a"], "failed": [], "retried": [], "not_measured": []}),
                encoding="utf-8",
            )
            attempt = self.mod.classify_attempt("Maestro/flows/tasks/01-a.yaml", d, 2)
        self.assertEqual(attempt.outcome, "infra_failure")

    def test_attempt_artifacts_do_not_collide_for_same_basename(self):
        flow_a = "Maestro/flows/area1/01-same.yaml"
        flow_b = "Maestro/flows/area2/01-same.yaml"
        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp)
            with mock.patch.object(self.mod.subprocess, "run", return_value=mock.Mock(returncode=2)):
                self.mod.run_one_attempt(flow_a, 1, out, None, None, False, False, [])
                self.mod.run_one_attempt(flow_b, 1, out, None, None, False, False, [])
            dir_a = out / "Maestro" / "flows" / "area1" / "01-same" / "attempt-1"
            dir_b = out / "Maestro" / "flows" / "area2" / "01-same" / "attempt-1"
            self.assertTrue(dir_a.is_dir(), "artifacts for first flow must survive")
            self.assertTrue(dir_b.is_dir(), "artifacts for second flow must be written separately")
            self.assertNotEqual(dir_a, dir_b)

    def test_missing_environment_or_build_identity_prevents_admissible_record(self):
        flow = "Maestro/flows/tasks/01-a.yaml"
        attempts = []
        for i in range(1, 4):
            d = tempfile.TemporaryDirectory()
            self.addCleanup(d.cleanup)
            p = pathlib.Path(d.name)
            (p / "01-a.xml").write_text(
                "<?xml version='1.0'?>\n"
                "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>",
                encoding="utf-8",
            )
            (p / "run-summary.json").write_text(
                json.dumps({"passed": ["01-a"], "failed": [], "retried": [], "not_measured": []}),
                encoding="utf-8",
            )
            # no environment.json -> environment/build identity missing per attempt
            attempt = self.mod.classify_attempt(flow, p, 0)
            attempt.attempt = i
            attempts.append(attempt)
        record = self.mod.build_qualification_record(flow, attempts, self.mod.ROOT)
        issues = self.mod.record_issues(record)
        self.assertTrue(any("documented environment" in x for x in issues), issues)
        self.assertTrue(any("build identity" in x for x in issues), issues)

    def test_version_change_between_attempts_prevents_admissible_record(self):
        flow = "Maestro/flows/tasks/01-a.yaml"
        attempts = []
        for i, mv in enumerate(["2.10.0", "2.11.0", "2.10.0"], start=1):
            attempts.append(
                self.mod.Attempt(
                    attempt=i,
                    outcome="pass",
                    reason="ok",
                    junit="x/01-a.xml",
                    results_dir="x",
                    environment={
                        "kind": "device",
                        "api": 35,
                        "device": "d",
                        "serial": "s",
                        "commit": "c",
                        "apk_commit_sha": "c",
                    },
                    run_summary={"passed": ["01-a"], "failed": [], "retried": [], "not_measured": []},
                    return_code=0,
                    maestro_version=mv,
                )
            )
        record = self.mod.build_qualification_record(flow, attempts, self.mod.ROOT)
        issues = self.mod.record_issues(record)
        self.assertTrue(any("do not share one Maestro version" in x for x in issues), issues)
        self.assertIsNone(record.get("maestro_version"))

    def test_maestro_engine_env_used_for_version_when_no_explicit_engine(self):
        flow = "Maestro/flows/tasks/01-a.yaml"
        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp)
            with mock.patch.dict(os.environ, {"MAESTRO_ENGINE": "maestro-runner"}, clear=False):
                with mock.patch.object(
                    self.mod, "maestro_version", return_value="maestro-runner 1.2.3"
                ) as mv_mock:
                    with mock.patch.object(
                        self.mod.subprocess, "run", return_value=mock.Mock(returncode=2)
                    ):
                        attempt = self.mod.run_one_attempt(
                            flow, 1, out, None, None, False, False, []
                        )
        mv_mock.assert_called_once_with("maestro-runner")
        self.assertEqual(attempt.maestro_version, "maestro-runner 1.2.3")


if __name__ == "__main__":
    unittest.main()
