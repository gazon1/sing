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
import click

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


def write_baseline(path: pathlib.Path, flows: dict[str, str]) -> None:
    """Write a baseline: the fingerprint map, and nothing else.

    The frozen set is a separate artifact now, pinned by a digest — a baseline
    that named its own frozen set could be edited once to admit a flow nobody
    ever ran.
    """
    path.write_text(
        yaml.safe_dump({"schema": 1, "flows": flows}, sort_keys=False),
        encoding="utf-8",
    )


def write_initial_set(path: pathlib.Path, flows: list[str]) -> None:
    """Write the frozen set: the flows that were in blocking scopes at rule introduction."""
    path.write_text(
        yaml.safe_dump({"schema": 1, "initial_flows": sorted(flows)}, sort_keys=False),
        encoding="utf-8",
    )


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
        # The frozen set starts empty: a flow is in it only if it was in a
        # blocking scope when the rule was introduced.
        write_initial_set(root / "initial.yaml", [])
        evidence = root / "evidence.yaml"
        evidence.write_text(
            yaml.safe_dump(
                {"schema": 1, "required_attempts": 3, "qualifications": {}, "exceptions": {}},
                sort_keys=False,
            )
        )
        return root, flow, catalogue, baseline, evidence

    def _initial(self, root):
        return root / "initial.yaml"

    def _scan(self, root, catalogue, baseline, evidence, expected_digest=None):
        initial = self._initial(root)
        if expected_digest is None:
            expected_digest = mod.initial_set_digest(mod.read_initial_set(initial))
        return mod.scan(root, catalogue, baseline, evidence, initial, expected_digest)

    def test_new_flow_cannot_be_admitted_by_baseline_expansion(self):
        root, _flow, catalogue, baseline, evidence = self._tree()
        flow = "Maestro/flows/tasks/02-new.yaml"
        write_flow(root / flow, FLOW_OK)
        catalogue.write_text(flow + "\n")
        fp = mod.fingerprint(flow, root)
        write_baseline(baseline, {flow: fp})
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["baseline expansion"])

    def test_new_blocking_flow_is_unqualified(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        write_baseline(baseline, {})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["unqualified"])
        self.assertEqual(findings[0].flow, flow)

    def test_baselined_flow_is_admitted(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        fp = mod.fingerprint(flow, root)
        write_baseline(baseline, {flow: fp})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["baselined"], 1)

    def test_baseline_that_names_its_own_frozen_set_is_rejected(self):
        """A baseline that carries `initial_flows` is refused.

        The frozen set is a separate, pinned artifact. A baseline that names
        its own frozen set is a baseline one edit away from admitting a flow
        nobody ever ran.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        fp = mod.fingerprint(flow, root)
        baseline.write_text(
            yaml.safe_dump(
                {"schema": 1, "initial_flows": [flow], "flows": {flow: fp}},
                sort_keys=False,
            )
        )
        with self.assertRaises(click.ClickException) as raised:
            self._scan(root, catalogue, baseline, evidence)
        self.assertIn("initial_flows does not belong in the baseline", str(raised.exception))

    def test_frozen_set_with_duplicate_names_is_rejected(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow, flow])
        write_baseline(baseline, {})
        with self.assertRaises(click.ClickException) as raised:
            self._scan(root, catalogue, baseline, evidence, expected_digest="sha256:" + "0" * 64)
        self.assertIn("unique strings", str(raised.exception))

    def test_new_flow_cannot_be_admitted_by_expanding_the_frozen_set(self):
        """The positive control: editing the frozen set and the baseline together.

        The attack the rule exists against: a new flow is added to the
        baseline map *and* to the frozen set, so that the gate reads it as a
        flow that was there when the rule was introduced. The frozen set is
        pinned by a digest, so the pair of edits does not add up to an
        admission — the gate refuses before it looks at any flow.
        """
        root, _flow, catalogue, baseline, evidence = self._tree()
        flow = "Maestro/flows/tasks/02-new.yaml"
        write_flow(root / flow, FLOW_OK)
        catalogue.write_text(flow + "\n")
        fp = mod.fingerprint(flow, root)
        pinned = mod.initial_set_digest(mod.read_initial_set(self._initial(root)))
        # The attacker edits both files; the pin stays where it was.
        write_initial_set(self._initial(root), [flow])
        write_baseline(baseline, {flow: fp})
        with self.assertRaises(click.ClickException) as raised:
            self._scan(root, catalogue, baseline, evidence, expected_digest=pinned)
        self.assertIn("pinned digest", str(raised.exception))

    def test_changed_flow_needs_requalification(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        write_baseline(baseline, {flow: "sha256:" + "0" * 64})
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["stale baseline"])

    def test_changed_baseline_flow_admitted_by_valid_qualification(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        old_fp = mod.fingerprint(flow, root)
        write_flow(root / flow, FLOW_OK + "\n# changed\n")
        new_fp = mod.fingerprint(flow, root)
        self.assertNotEqual(old_fp, new_fp)
        write_baseline(baseline, {flow: old_fp})
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {flow: self._qualified_record(new_fp, ["pass", "pass", "pass"])},
                    "exceptions": {},
                },
                sort_keys=False,
            )
        )
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["qualified"], 1)

    def test_changed_baseline_flow_admitted_by_valid_exception(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        old_fp = mod.fingerprint(flow, root)
        write_flow(root / flow, FLOW_OK + "\n# changed\n")
        new_fp = mod.fingerprint(flow, root)
        self.assertNotEqual(old_fp, new_fp)
        write_baseline(baseline, {flow: old_fp})
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {},
                    "exceptions": {
                        flow: {"fingerprint": new_fp, "reason": "known env issue", "evidence": "#3052"}
                    },
                },
                sort_keys=False,
            )
        )
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["excepted"], 1)

    def test_manual_flow_excluded(self):
        root, _flow, catalogue, baseline, evidence = self._tree()
        flow = "Maestro/flows/auth/01-manual.yaml"
        write_flow(root / flow, FLOW_MANUAL)
        catalogue.write_text(flow + "\n")
        write_baseline(baseline, {})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["flows"], 0)

    def test_auth_live_flow_excluded(self):
        root, _flow, catalogue, baseline, evidence = self._tree()
        flow = "Maestro/flows/auth/01-live.yaml"
        write_flow(root / flow, FLOW_AUTH_LIVE)
        catalogue.write_text(flow + "\n")
        write_baseline(baseline, {})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["flows"], 0)

    def test_missing_required_attempts_defaults_to_three(self):
        """A policy that says nothing is a policy of three attempts."""
        root, flow, catalogue, baseline, evidence = self._tree()
        evidence.write_text(
            yaml.safe_dump({"schema": 1, "qualifications": {}, "exceptions": {}}, sort_keys=False)
        )
        self.assertEqual(mod.read_evidence(evidence)[0], 3)

    def test_invalid_required_attempts_is_refused(self):
        """An invalid policy is a configuration error, not a smaller requirement.

        Silently falling back to three would let a typo decide what counts as
        a qualification.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        evidence.write_text(
            yaml.safe_dump(
                {"schema": 1, "required_attempts": "three", "qualifications": {}, "exceptions": {}},
                sort_keys=False,
            )
        )
        with self.assertRaises(click.ClickException) as raised:
            self._scan(root, catalogue, baseline, evidence)
        self.assertIn("required_attempts", str(raised.exception))

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
        write_baseline(baseline, {})
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
        write_baseline(baseline, {})
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
        write_baseline(baseline, {})
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
        write_baseline(baseline, {})
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
        write_baseline(baseline, {})
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
        write_baseline(baseline, {})
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

    def test_boolean_required_attempts_is_refused(self):
        """A boolean is not a number of attempts: the policy is a configuration error."""
        root, flow, catalogue, baseline, evidence = self._tree()
        write_baseline(baseline, {})
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
        with self.assertRaises(click.ClickException) as raised:
            self._scan(root, catalogue, baseline, evidence)
        self.assertIn("required_attempts", str(raised.exception))

    def test_flaky_not_qualified(self):
        root, flow, catalogue, baseline, evidence = self._tree()
        write_baseline(baseline, {})
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
        write_baseline(baseline, {})
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
        write_baseline(baseline, {})
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
        write_baseline(baseline, {})
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


class FrozenSetPolicyTest(unittest.TestCase):
    """The frozen set is anchored outside the file it guards."""

    def test_init_baseline_cannot_expand_the_frozen_set(self):
        """Writing the baseline does not touch the set of flows it admits.

        `--init-baseline` used to rebuild the frozen set from the current
        catalogue, which made it a way to admit flows nobody ever ran.
        """
        repo = pathlib.Path(__file__).resolve().parent.parent.parent
        initial = repo / "config" / "docs" / "maestro-flow-qualification-initial.yaml"
        before = initial.read_text(encoding="utf-8")
        frozen = mod.read_initial_set(initial)
        with tempfile.TemporaryDirectory() as tmp:
            baseline = pathlib.Path(tmp) / "baseline.yaml"
            result = subprocess.run(
                [
                    sys.executable,
                    str(MODULE_PATH),
                    "--root",
                    str(repo),
                    "--catalogue",
                    str(repo / "config" / "docs" / "maestro-flows.txt"),
                    "--initial",
                    str(initial),
                    "--baseline",
                    str(baseline),
                    "--init-baseline",
                ],
                cwd=repo,
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            data = yaml.safe_load(baseline.read_text(encoding="utf-8")) or {}
        self.assertNotIn("initial_flows", data)
        self.assertTrue(data.get("flows"))
        self.assertLessEqual(set(data["flows"]), set(frozen))
        self.assertEqual(initial.read_text(encoding="utf-8"), before, "the frozen set must not be rewritten")

    def test_init_baseline_refuses_to_overwrite_an_existing_baseline(self):
        """The baseline is written once: a changed flow is requalified, not re-baselined."""
        repo = pathlib.Path(__file__).resolve().parent.parent.parent
        with tempfile.TemporaryDirectory() as tmp:
            baseline = pathlib.Path(tmp) / "baseline.yaml"
            baseline.write_text("schema: 1\nflows: {}\n", encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable,
                    str(MODULE_PATH),
                    "--root",
                    str(repo),
                    "--catalogue",
                    str(repo / "config" / "docs" / "maestro-flows.txt"),
                    "--initial",
                    str(repo / "config" / "docs" / "maestro-flow-qualification-initial.yaml"),
                    "--baseline",
                    str(baseline),
                    "--init-baseline",
                ],
                cwd=repo,
                capture_output=True,
                text=True,
            )
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("written once", result.stdout + result.stderr)


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

    def test_extra_args_cannot_override_runner_owned_options(self):
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
            cases = [
                (["--", "--maestro-max-retries", "2"], "--maestro-max-retries"),
                (["--", "--maestro-max-retries=2"], "--maestro-max-retries"),
                (["--", "--engine", "maestro-runner"], "--engine"),
                (["--", "--engine=maestro-runner"], "--engine"),
                (["--", "--maestro-cleanup", "off"], "--maestro-cleanup"),
                (["--", "--maestro-cleanup=off"], "--maestro-cleanup"),
            ]
            for extra, forbidden in cases:
                with self.subTest(extra=extra):
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
                    self.assertIn(forbidden, result.stdout + result.stderr)
                    self.assertIn("not allowed", result.stdout + result.stderr)


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
        record = self.mod.build_qualification_record(flow, attempts, "sha256:" + "0" * 64)
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
        record = self.mod.build_qualification_record(flow, attempts, "sha256:" + "0" * 64)
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


class QualificationRecordWritingTest(unittest.TestCase):
    """The record the runner writes has to be one the gate can admit."""

    def setUp(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows", runner_path)
        self.mod = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = self.mod
        spec.loader.exec_module(self.mod)

    def _fake_run(self, version: str | None):
        """A runner whose attempts pass and whose engine version is as told."""

        def run(cmd, *args, **kwargs):
            if "--version" in cmd:
                if version is None:
                    return mock.Mock(returncode=1, stdout="", stderr="")
                return mock.Mock(returncode=0, stdout=f"Maestro {version}\n", stderr="")
            attempt_dir = pathlib.Path(cmd[cmd.index("--junit-dir") + 1])
            attempt_dir.mkdir(parents=True, exist_ok=True)
            (attempt_dir / "01-a.xml").write_text(
                "<?xml version='1.0'?>\n"
                "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>",
                encoding="utf-8",
            )
            (attempt_dir / "run-summary.json").write_text(
                json.dumps({"passed": ["01-a"], "failed": [], "retried": [], "not_measured": []}),
                encoding="utf-8",
            )
            (attempt_dir / "environment.json").write_text(
                json.dumps(
                    {
                        "kind": "emulator",
                        "api": 35,
                        "device": "Pixel",
                        "serial": "emulator-5554",
                        "commit": "abc123",
                        "apk_commit_sha": "abc123",
                    }
                ),
                encoding="utf-8",
            )
            return mock.Mock(returncode=0)

        return run

    def _run(self, version: str | None, tmp_path: pathlib.Path) -> tuple[int, dict]:
        evidence = tmp_path / "evidence.yaml"
        evidence.write_text(
            yaml.safe_dump(
                {"schema": 1, "required_attempts": 3, "qualifications": {}, "exceptions": {}},
                sort_keys=False,
            ),
            encoding="utf-8",
        )
        flow = "Maestro/flows/tasks/01-a.yaml"
        with mock.patch.object(self.mod.subprocess, "run", side_effect=self._fake_run(version)):
            code = self.mod.main(
                [
                    "--flow",
                    flow,
                    "--attempts",
                    "3",
                    "--record",
                    "--evidence",
                    str(evidence),
                    "--out",
                    str(tmp_path / "out"),
                ]
            )
        data = yaml.safe_load(evidence.read_text(encoding="utf-8")) or {}
        return code, data

    def test_undetectable_version_is_not_recorded_as_qualified(self):
        """Three passing attempts and no engine version are not a qualification.

        The record would be rejected by the admission gate, so the runner does
        not write one: the user is told at record time, not at admission time.
        """
        with tempfile.TemporaryDirectory() as tmp:
            code, data = self._run(None, pathlib.Path(tmp))
        self.assertEqual(code, 1)
        self.assertEqual(data.get("qualifications") or {}, {})

    def test_detected_version_is_recorded_as_qualified(self):
        """The positive control: the same run with a version is recorded."""
        with tempfile.TemporaryDirectory() as tmp:
            code, data = self._run("1.39.0", pathlib.Path(tmp))
        self.assertEqual(code, 0)
        record = (data.get("qualifications") or {}).get("Maestro/flows/tasks/01-a.yaml")
        self.assertIsNotNone(record)
        self.assertEqual(record["maestro_version"], "1.39.0")
        self.assertEqual(len(record["attempts"]), 3)

    def test_flow_that_changes_during_qualification_is_not_recorded(self):
        """The attempts certify the flow they ran, not the one in the tree.

        A flow edited while it is being qualified produces attempts for one
        version and a record naming another. The record would then claim
        three passing runs for a fingerprint nobody ran, so it is not written.
        """
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
            flow = "Maestro/flows/tasks/01-a.yaml"
            calls: list[int] = []

            def fake_fingerprint(*args, **kwargs):
                calls.append(1)
                return "sha256:" + ("a" if len(calls) == 1 else "b") * 64

            with mock.patch.object(self.mod.GATE, "fingerprint", side_effect=fake_fingerprint):
                with mock.patch.object(
                    self.mod.subprocess, "run", side_effect=self._fake_run("1.39.0")
                ):
                    code = self.mod.main(
                        [
                            "--flow",
                            flow,
                            "--attempts",
                            "3",
                            "--record",
                            "--evidence",
                            str(evidence),
                            "--out",
                            str(tmp_path / "out"),
                        ]
                    )
            data = yaml.safe_load(evidence.read_text(encoding="utf-8")) or {}
        self.assertEqual(code, 1)
        self.assertEqual(data.get("qualifications") or {}, {})


    def test_transient_edit_during_qualification_does_not_reach_the_attempts(self):
        """The attempts run a snapshot, so a transient edit never reaches them.

        A helper edited between two attempts and restored before the last one
        leaves the tree looking unchanged, and a runner that read the tree
        would certify three passing runs for a version only one attempt saw.
        The attempts read the immutable snapshot, so every attempt sees the
        same content -- and the record describes exactly that content.
        """
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            root = tmp_path / "tree"
            flow_rel = "Maestro/flows/tasks/01-a.yaml"
            helper_rel = "Maestro/helpers/open.yaml"
            flow_path = root / flow_rel
            helper_path = root / helper_rel
            flow_path.parent.mkdir(parents=True, exist_ok=True)
            helper_path.parent.mkdir(parents=True, exist_ok=True)
            flow_path.write_text(
                "appId: com.example\n---\n- runFlow: ../../helpers/open.yaml\n", encoding="utf-8"
            )
            original_helper = "appId: com.example\n---\n- launchApp\n"
            helper_path.write_text(original_helper, encoding="utf-8")
            edited_helper = original_helper + "- tapOn: edited\n"

            evidence = tmp_path / "evidence.yaml"
            evidence.write_text(
                yaml.safe_dump(
                    {"schema": 1, "required_attempts": 3, "qualifications": {}, "exceptions": {}},
                    sort_keys=False,
                ),
                encoding="utf-8",
            )

            seen_helpers: list[str] = []
            seen_flow_paths: list[pathlib.Path] = []

            def run(cmd, *args, **kwargs):
                if "--version" in cmd:
                    return mock.Mock(returncode=0, stdout="Maestro 1.39.0\n", stderr="")
                flow_under_test = pathlib.Path(cmd[cmd.index("--flow") + 1])
                seen_flow_paths.append(flow_under_test)
                # The helper the attempt would read, next to the flow it reads.
                seen_helpers.append(
                    (flow_under_test.parent.parent.parent / "helpers" / "open.yaml").read_text(
                        encoding="utf-8"
                    )
                )
                # The tree is edited under the run and restored before the last attempt.
                if len(seen_helpers) == 1:
                    helper_path.write_text(edited_helper, encoding="utf-8")
                elif len(seen_helpers) == 2:
                    helper_path.write_text(original_helper, encoding="utf-8")
                attempt_dir = pathlib.Path(cmd[cmd.index("--junit-dir") + 1])
                attempt_dir.mkdir(parents=True, exist_ok=True)
                (attempt_dir / "01-a.xml").write_text(
                    "<?xml version='1.0'?>\n"
                    "<testsuites><testsuite tests=\"1\" failures=\"0\">"
                    "<testcase id=\"a\" name=\"a\" status=\"SUCCESS\"/></testsuite></testsuites>",
                    encoding="utf-8",
                )
                (attempt_dir / "run-summary.json").write_text(
                    json.dumps({"passed": ["01-a"], "failed": [], "retried": [], "not_measured": []}),
                    encoding="utf-8",
                )
                (attempt_dir / "environment.json").write_text(
                    json.dumps(
                        {
                            "kind": "emulator",
                            "api": 35,
                            "device": "Pixel",
                            "serial": "emulator-5554",
                            "commit": "abc123",
                            "apk_commit_sha": "abc123",
                        }
                    ),
                    encoding="utf-8",
                )
                return mock.Mock(returncode=0)

            with mock.patch.object(self.mod, "ROOT", root):
                with mock.patch.object(self.mod.subprocess, "run", side_effect=run):
                    code = self.mod.main(
                        [
                            "--flow",
                            flow_rel,
                            "--attempts",
                            "3",
                            "--record",
                            "--evidence",
                            str(evidence),
                            "--out",
                            str(tmp_path / "out"),
                        ]
                    )
            data = yaml.safe_load(evidence.read_text(encoding="utf-8")) or {}
            fingerprint_of_snapshot = self.mod.GATE.fingerprint(flow_rel, root)

        self.assertEqual(code, 0)
        # No attempt saw the edited helper.
        self.assertEqual(seen_helpers, [original_helper] * 3)
        # No attempt ran the flow in the working tree.
        self.assertNotIn(root / flow_rel, seen_flow_paths)
        for path in seen_flow_paths:
            self.assertIn("maestro-qualification-snapshot", path.parts)
        record = (data.get("qualifications") or {}).get(flow_rel)
        self.assertIsNotNone(record)
        self.assertEqual(record["fingerprint"], fingerprint_of_snapshot)


class UntrackedDependencyTest(unittest.TestCase):
    """A flow that reads content outside the tree cannot be qualified.

    The fingerprint describes the tree, and the attempts run a snapshot of the
    tree. A reference that leaves it -- an absolute path, or a relative path
    that climbs out -- points at content neither of them covers: an edit
    there would go unnoticed, and the evidence would attest to a flow nobody
    ran in full.
    """

    def _tree(self, flow_body: str):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        flow = "Maestro/flows/tasks/01-a.yaml"
        write_flow(root / flow, flow_body)
        catalogue = root / "catalogue.txt"
        catalogue.write_text(flow + "\n")
        baseline = root / "baseline.yaml"
        write_initial_set(root / "initial.yaml", [flow])
        evidence = root / "evidence.yaml"
        evidence.write_text(
            yaml.safe_dump(
                {"schema": 1, "required_attempts": 3, "qualifications": {}, "exceptions": {}},
                sort_keys=False,
            )
        )
        return root, flow, catalogue, baseline, evidence

    def _scan(self, root, catalogue, baseline, evidence):
        initial = root / "initial.yaml"
        digest = mod.initial_set_digest(mod.read_initial_set(initial))
        return mod.scan(root, catalogue, baseline, evidence, initial, digest)

    def test_absolute_reference_is_refused(self):
        root, flow, catalogue, baseline, evidence = self._tree(
            "appId: x\n---\n- runFlow: /etc/other.yaml\n"
        )
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["untracked dependency"])
        self.assertIn("absolute path", findings[0].detail)
        self.assertEqual(counts["baselined"], 0)

    def test_reference_leaving_the_tree_is_refused(self):
        root, flow, catalogue, baseline, evidence = self._tree(
            "appId: x\n---\n- runFlow: ../../../../outside/helper.yaml\n"
        )
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["untracked dependency"])
        self.assertIn("leaves the tree", findings[0].detail)

    def test_baseline_does_not_admit_a_flow_with_an_untracked_dependency(self):
        """The baseline admits a flow by fingerprint; it does not admit a lie.

        The fingerprint of a flow that reads outside the tree is a fingerprint
        of the part the tree can see. Admitting the flow on it would grant a
        qualification for content the gate never looks at.
        """
        root, flow, catalogue, baseline, evidence = self._tree(
            "appId: x\n---\n- runScript: /opt/tools/helper.js\n"
        )
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["untracked dependency"])
        self.assertEqual(counts["baselined"], 0)

    def test_helper_script_is_part_of_the_fingerprint(self):
        """A helper script is content the qualification has to attest to.

        A flow runs scripts through `runScript`; an edit to the script
        changes what the flow does, so it has to change the fingerprint.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root / "Maestro/helpers/scripts/tool.js", "console.log('a');\n")
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- runScript: ../../helpers/scripts/tool.js\n",
            )
            deps = mod.flow_dependencies("Maestro/flows/tasks/01-a.yaml", root)
            self.assertIn("Maestro/helpers/scripts/tool.js", deps)
            fp1 = mod.fingerprint("Maestro/flows/tasks/01-a.yaml", root)
            write_flow(root / "Maestro/helpers/scripts/tool.js", "console.log('b');\n")
            fp2 = mod.fingerprint("Maestro/flows/tasks/01-a.yaml", root)
        self.assertNotEqual(fp1, fp2)

    def test_a_flow_inside_the_tree_is_not_refused(self):
        """The positive control: relative references inside the tree stay legal."""
        root, flow, catalogue, baseline, evidence = self._tree(
            "appId: x\n---\n- runFlow: ../../helpers/h.yaml\n"
        )
        write_flow(root / "Maestro/helpers/h.yaml", "appId: x\n---\n- tapOn: a\n")
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["baselined"], 1)


class RunnerRefusalTest(unittest.TestCase):
    """The runner refuses a flow it cannot qualify, before spending attempts."""

    def setUp(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows", runner_path)
        self.mod = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = self.mod
        spec.loader.exec_module(self.mod)

    def test_flow_with_untracked_dependency_is_refused_without_attempts(self):
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
            flow = "Maestro/flows/tasks/01-a.yaml"
            run = mock.Mock(returncode=0)
            with mock.patch.object(
                self.mod.GATE, "dependency_issues", return_value=["01-a.yaml references /etc/x.yaml"]
            ):
                with mock.patch.object(self.mod.subprocess, "run", side_effect=run):
                    code = self.mod.main(
                        [
                            "--flow",
                            flow,
                            "--attempts",
                            "3",
                            "--record",
                            "--evidence",
                            str(evidence),
                            "--out",
                            str(tmp_path / "out"),
                        ]
                    )
            data = yaml.safe_load(evidence.read_text(encoding="utf-8")) or {}
        self.assertEqual(code, 1)
        # The only command that ran is the engine version probe: no attempt ran.
        for call_args, _ in run.call_args_list:
            self.assertIn("--version", call_args[0])
        self.assertEqual(data.get("qualifications") or {}, {})


if __name__ == "__main__":
    unittest.main()
