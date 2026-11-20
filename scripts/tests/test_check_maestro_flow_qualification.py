"""Tests for scripts/check-maestro-flow-qualification.py."""

import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

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


if __name__ == "__main__":
    unittest.main()
