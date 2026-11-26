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


def baseline_digest_of(path: pathlib.Path) -> str | None:
    """The digest a baseline written at `path` has to match, or None if there is none."""
    if not path.is_file():
        return None
    try:
        return mod.baseline_digest(mod.read_baseline(path))
    except ValueError:
        return None


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


class ReferenceDiscoveryTest(unittest.TestCase):
    """The references a flow makes are read from the document, not from lines.

    A reference the scanner misses is a dependency the fingerprint does not
    cover: the helper changes, the fingerprint does not, and a changed helper
    enters a blocking scope without anyone qualifying it again.
    """

    def test_trailing_comment_does_not_hide_a_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root / "Maestro/helpers/h.yaml", "appId: x\n---\n- tapOn: a\n")
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- runFlow:\n    file: ../../helpers/h.yaml # shared setup\n",
            )
            refs = mod.flow_refs(root / "Maestro/flows/tasks/01-a.yaml")
        self.assertEqual(["../../helpers/h.yaml"], refs)

    def test_trailing_comment_on_an_inline_runflow(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- runFlow: ../../helpers/h.yaml # shared setup\n",
            )
            refs = mod.flow_refs(root / "Maestro/flows/tasks/01-a.yaml")
        self.assertEqual(["../../helpers/h.yaml"], refs)

    def test_trailing_comment_on_a_quoted_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                'appId: x\n---\n- runFlow:\n    file: "../../helpers/h.yaml" # shared setup\n',
            )
            refs = mod.flow_refs(root / "Maestro/flows/tasks/01-a.yaml")
        self.assertEqual(["../../helpers/h.yaml"], refs)

    def test_trailing_comment_on_a_script_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root / "Maestro/helpers/s.js", "function main() {}\n")
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- runScript: ../../helpers/s.js # cleanup\n",
            )
            deps = mod.flow_dependencies("Maestro/flows/tasks/01-a.yaml", root)
        self.assertIn("Maestro/helpers/s.js", deps)

    def test_a_commented_helper_is_part_of_the_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root / "Maestro/helpers/h.yaml", "appId: x\n---\n- tapOn: a\n")
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- runFlow:\n    file: ../../helpers/h.yaml # shared setup\n",
            )
            fp1 = mod.fingerprint("Maestro/flows/tasks/01-a.yaml", root)
            write_flow(root / "Maestro/helpers/h.yaml", "appId: x\n---\n- tapOn: b\n")
            fp2 = mod.fingerprint("Maestro/flows/tasks/01-a.yaml", root)
        self.assertNotEqual(fp1, fp2)

    def test_a_flow_named_instead_of_pathed_is_a_dependency(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root / "Maestro/flows/tasks/sub.yaml", "appId: x\n---\n- tapOn: a\n")
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- runFlow: sub\n",
            )
            deps = mod.flow_dependencies("Maestro/flows/tasks/01-a.yaml", root)
        self.assertIn("Maestro/flows/tasks/sub.yaml", deps)

    def test_a_reference_that_is_not_a_flow_file_is_not_a_dependency(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root / "Maestro/capture/01-today.png", "not a flow\n")
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- copyTextFrom: ../../capture/01-today.png\n",
            )
            deps = mod.flow_dependencies("Maestro/flows/tasks/01-a.yaml", root)
        self.assertEqual(["Maestro/flows/tasks/01-a.yaml"], deps)

    def test_a_document_yaml_cannot_parse_falls_back_to_a_line_scan(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(
                root / "Maestro/flows/tasks/01-a.yaml",
                "appId: x\n---\n- runFlow: ../../helpers/h.yaml\n  broken: [unclosed\n",
            )
            refs = mod.flow_refs(root / "Maestro/flows/tasks/01-a.yaml")
        self.assertEqual(["../../helpers/h.yaml"], refs)


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

    def _scan(self, root, catalogue, baseline, evidence, expected_digest=None, expected_baseline_digest=None):
        initial = self._initial(root)
        if expected_digest is None:
            expected_digest = mod.initial_set_digest(mod.read_initial_set(initial))
        if expected_baseline_digest is None:
            # The baseline is pinned; the test writes its own baseline, so it
            # pins it too. A tampered baseline is its own test.
            expected_baseline_digest = baseline_digest_of(baseline)
        return mod.scan(
            root,
            catalogue,
            baseline,
            evidence,
            initial,
            expected_digest,
            expected_baseline_digest,
        )

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

    def test_a_baseline_entry_for_a_retired_flow_is_reported(self):
        """A fingerprint of a flow that left the catalogue is reported.

        The map answers "which flow content may enter a blocking scope
        without being qualified". A fingerprint of a flow that no longer
        exists answers nothing while it is gone, and admits it again the
        moment it comes back with the same content. Retiring a flow
        therefore drops its fingerprint in the same change that removes it
        from the catalogue — and the gate says so when it did not.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        retired = "Maestro/flows/tasks/09-retired.yaml"
        write_flow(root / retired, FLOW_OK)
        write_initial_set(self._initial(root), [flow, retired])
        write_baseline(
            baseline,
            {flow: mod.fingerprint(flow, root), retired: mod.fingerprint(retired, root)},
        )
        # The retired flow is gone from the catalogue: only the map still
        # remembers it.
        catalogue.write_text(flow + "\n")
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["orphaned baseline entry"])
        self.assertEqual(findings[0].flow, retired)

    def test_a_baseline_entry_for_a_catalogue_flow_is_not_reported(self):
        """The counterpart: an entry that points at a live flow is fine."""
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
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

    def test_stale_qualification_does_not_shadow_a_valid_exception(self):
        """A qualification and an exception are evaluated independently.

        A qualification recorded for an older fingerprint is not an admission
        -- but it must not hide a valid exception either. The exception
        admits the flow, and a stale record is not a finding on its own.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        fp = mod.fingerprint(flow, root)
        write_baseline(baseline, {flow: "sha256:" + "0" * 64})
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {
                        flow: {"fingerprint": "sha256:" + "1" * 64, "attempts": []}
                    },
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

    def test_failed_qualification_and_failed_exception_are_both_reported(self):
        """Neither admission holds: both reasons are reported.

        The qualification failed and the exception failed. Reporting only the
        qualification would hide the exception that a reader has to fix.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        # The baseline no longer matches, so the flow needs a qualification
        # or an exception to be admitted at all.
        write_baseline(baseline, {flow: "sha256:" + "0" * 64})
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {
                        flow: {"fingerprint": "sha256:" + "1" * 64, "attempts": []}
                    },
                    "exceptions": {
                        flow: {"fingerprint": "sha256:" + "2" * 64, "reason": "x", "evidence": "#1"}
                    },
                },
                sort_keys=False,
            )
        )
        findings, _ = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["not qualified", "invalid exception"])

    def test_stale_exception_on_a_qualified_flow_is_reported(self):
        """An exception that admits nothing is a finding, not silence.

        The flow is qualified against the current fingerprint, so the
        exception never runs -- it names a fingerprint the flow has left
        behind. It is read as a reason the flow was admitted *instead* of
        being qualified, and the file elsewhere says the opposite. The record
        has to be reissued or deleted, so the gate says so.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        fp = mod.fingerprint(flow, root)
        write_baseline(baseline, {flow: "sha256:" + "0" * 64})
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {flow: self._qualified_record(fp, ["pass", "pass", "pass"])},
                    "exceptions": {
                        flow: {"fingerprint": "sha256:" + "2" * 64, "reason": "x", "evidence": "#1"}
                    },
                },
                sort_keys=False,
            )
        )
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["stale exception"])
        self.assertEqual(counts["qualified"], 1)

    def test_stale_exception_on_a_baselined_flow_is_reported(self):
        """The same, for a flow the baseline admits."""
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        fp = mod.fingerprint(flow, root)
        write_baseline(baseline, {flow: fp})
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {},
                    "exceptions": {
                        flow: {"fingerprint": "sha256:" + "2" * 64, "reason": "x", "evidence": "#1"}
                    },
                },
                sort_keys=False,
            )
        )
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["stale exception"])
        self.assertEqual(counts["baselined"], 1)

    def test_a_current_exception_beside_a_qualification_is_not_a_finding(self):
        """An exception that matches the current fingerprint is not stale.

        The flow is qualified and also carries an exception for the current
        fingerprint. The exception names no fingerprint the flow has left
        behind, so the gate has nothing to say about it.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        write_initial_set(self._initial(root), [flow])
        fp = mod.fingerprint(flow, root)
        write_baseline(baseline, {flow: "sha256:" + "0" * 64})
        evidence.write_text(
            yaml.safe_dump(
                {
                    "schema": 1,
                    "required_attempts": 3,
                    "qualifications": {flow: self._qualified_record(fp, ["pass", "pass", "pass"])},
                    "exceptions": {
                        flow: {"fingerprint": fp, "reason": "x", "evidence": "#1"}
                    },
                },
                sort_keys=False,
            )
        )
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual(findings, [])
        self.assertEqual(counts["qualified"], 1)

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

    def test_required_attempts_below_the_floor_is_refused(self):
        """The evidence file may ask for more attempts, not for fewer.

        A qualification is a *repeated* run: that is the whole rule. If the
        file could ask for one attempt, a flow would enter a blocking scope on
        its first green run -- exactly the failure the rule exists to
        prevent. The floor lives in the gate, so lowering it is a source
        change that a review can see, not a line in an editable file.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        write_baseline(baseline, {})
        evidence.write_text(
            yaml.safe_dump(
                {"schema": 1, "required_attempts": 1, "qualifications": {}, "exceptions": {}},
                sort_keys=False,
            )
        )
        with self.assertRaises(click.ClickException) as raised:
            self._scan(root, catalogue, baseline, evidence)
        self.assertIn("required_attempts", str(raised.exception))

    def test_required_attempts_above_the_floor_is_allowed(self):
        """A stricter policy is the file's own business: the floor is a floor, not a ceiling."""
        root, flow, catalogue, baseline, evidence = self._tree()
        write_baseline(baseline, {})
        evidence.write_text(
            yaml.safe_dump(
                {"schema": 1, "required_attempts": 5, "qualifications": {}, "exceptions": {}},
                sort_keys=False,
            )
        )
        self.assertEqual(mod.read_evidence(evidence)[0], 5)

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
        self.assertIn(
            "attempt 1 does not record both the checkout and the APK identity",
            findings[0].detail,
        )

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


class BaselinePinTest(unittest.TestCase):
    """The baseline map is anchored outside the file it guards.

    The baseline says which flow content the rule was introduced for. Editing
    it -- changing a fingerprint, adding a flow -- is how a changed flow would
    be admitted without anyone qualifying it. So the map is pinned by a digest
    that lives in the gate, the same way the frozen set is.
    """

    def _tree(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        flow = "Maestro/flows/tasks/01-a.yaml"
        write_flow(root / flow, FLOW_OK)
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

    def _scan(self, root, catalogue, baseline, evidence, expected_baseline_digest):
        initial = root / "initial.yaml"
        digest = mod.initial_set_digest(mod.read_initial_set(initial))
        return mod.scan(
            root, catalogue, baseline, evidence, initial, digest, expected_baseline_digest
        )

    def test_edited_fingerprint_is_refused(self):
        """The attack the pin exists for.

        A changed flow is given the fingerprint it has now, in the baseline
        file, without anybody qualifying it. The pin does not move, so the
        gate refuses before it looks at a single flow.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        old_fp = mod.fingerprint(flow, root)
        write_baseline(baseline, {flow: old_fp})
        pinned = mod.baseline_digest(mod.read_baseline(baseline))
        # The flow changes, and the baseline is edited to match it.
        write_flow(root / flow, FLOW_OK + "\n# changed\n")
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        with self.assertRaises(click.ClickException) as raised:
            self._scan(root, catalogue, baseline, evidence, pinned)
        self.assertIn("pinned digest", str(raised.exception))

    def test_flow_added_to_the_baseline_is_refused(self):
        """A flow added to the map is refused as well.

        The map is pinned as a whole: a new entry changes the digest, so it
        cannot be added without touching the pin in the gate.
        """
        root, flow, catalogue, baseline, evidence = self._tree()
        write_baseline(baseline, {})
        pinned = mod.baseline_digest(mod.read_baseline(baseline))
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        with self.assertRaises(click.ClickException) as raised:
            self._scan(root, catalogue, baseline, evidence, pinned)
        self.assertIn("pinned digest", str(raised.exception))

    def test_repository_baseline_matches_the_pinned_digest(self):
        """The pin in the gate is the digest of the baseline in the repository."""
        repo = pathlib.Path(__file__).resolve().parent.parent.parent
        baseline = repo / "config" / "docs" / "maestro-flow-qualification-baseline.yaml"
        self.assertEqual(mod.baseline_digest(mod.read_baseline(baseline)), mod.BASELINE_DIGEST)

    def test_print_baseline_digest_prints_the_pin(self):
        """The digest is printed on demand, the way the frozen set's is."""
        repo = pathlib.Path(__file__).resolve().parent.parent.parent
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--print-baseline-digest"],
            cwd=repo,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        printed = [line for line in result.stdout.splitlines() if line.startswith("sha256:")]
        self.assertEqual(printed, [mod.BASELINE_DIGEST])


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
                attempt = self.mod.run_one_attempt(flow, 1, out, None, None, False, [])
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
                self.mod.run_one_attempt(flow_a, 1, out, None, None, False, [])
                self.mod.run_one_attempt(flow_b, 1, out, None, None, False, [])
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
        self.assertTrue(any("checkout and the APK identity" in x for x in issues), issues)

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
                            flow, 1, out, None, None, False, []
                        )
        mv_mock.assert_called_once_with("maestro-runner")
        self.assertEqual(attempt.maestro_version, "maestro-runner 1.2.3")


class EngineVersionTest(unittest.TestCase):
    """The engine version is a prerequisite, so it has to be *recognised*.

    A record has to name the version of the engine that ran the attempt, and
    the admission gate rejects a record without it. If any output at all could
    be read as a version, the prerequisite would hold for free: the runner
    would record whatever the tool printed and the gate would accept it.

    Both engines are read the same way -- the build is asked for its version,
    and the answer has to be recognised as *that engine's* version. The
    recognition lives in one place (`emulator.maestro._engine`), so the
    runner and the diagnostic helper cannot drift apart.
    """

    def setUp(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows_version", runner_path)
        self.mod = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = self.mod
        spec.loader.exec_module(self.mod)
        from emulator.maestro import _engine as eng

        self.eng = eng

    def test_the_banner_version_is_read(self):
        self.assertEqual(self.eng.parse_engine_version("Maestro 1.39.0", "maestro"), "1.39.0")

    def test_the_runner_banner_version_is_read(self):
        self.assertEqual(self.eng.parse_engine_version("maestro-runner 1.2.3", "maestro-runner"), "1.2.3")

    def test_a_banner_that_says_version_is_read(self):
        self.assertEqual(self.eng.parse_engine_version("Maestro version 1.39.0", "maestro"), "1.39.0")

    def test_a_bare_version_output_is_read(self):
        """The CLI prints its version alone; that output is a version."""
        self.assertEqual(self.eng.parse_engine_version("1.39.0", "maestro"), "1.39.0")

    def test_the_banner_wins_over_a_jvm_warning(self):
        """A warning printed above the banner is not the engine's version."""
        text = "OpenJDK 64-Bit Server VM warning: Use -Xmx\nMaestro 1.39.0\n"
        self.assertEqual(self.eng.parse_engine_version(text, "maestro"), "1.39.0")

    def test_the_jvm_version_is_not_the_engines_version(self):
        """A JVM that names its version does not name the engine's."""
        text = 'OpenJDK Runtime Environment (build 17.0.1+12)\n"version 11.0.2" 2021-10-19 LTS'
        self.assertIsNone(self.eng.parse_engine_version(text, "maestro"))

    def test_output_that_names_no_version_names_no_version(self):
        """The failure mode this pins: any output was read as a version."""
        for text in ("Maestro version unavailable", "Maestro", "command not found", ""):
            with self.subTest(text=text):
                self.assertIsNone(self.eng.parse_engine_version(text, "maestro"))

    def test_a_version_number_after_other_words_is_not_the_engines(self):
        """A number that belongs to something else is not a version."""
        self.assertIsNone(self.eng.parse_engine_version("Maestro version unavailable (build 1.2)", "maestro"))

    def test_an_empty_output_names_no_version(self):
        self.assertIsNone(self.eng.parse_engine_version("\n\n", "maestro"))

    def test_a_version_that_shares_its_line_with_words_is_not_a_version(self):
        """The whole line has to be the version -- a prefix is not enough."""
        for text in (
            "1.39.0 is not an engine version",
            "Maestro 1.39.0 version unavailable",
            "maestro-fake-engine 9.9.9",
            "9.9.9 maestro",
        ):
            with self.subTest(text=text):
                self.assertIsNone(self.eng.parse_engine_version(text, "maestro"))

    def test_a_version_of_another_engine_is_not_this_engines(self):
        """The banner has to name the engine that was probed."""
        self.assertIsNone(self.eng.parse_engine_version("maestro-runner 1.2.3", "maestro"))
        self.assertIsNone(self.eng.parse_engine_version("maestro 1.2.3", "maestro-runner"))

    def test_a_bare_version_mixed_into_other_output_is_not_a_version(self):
        """A number among other lines belongs to somebody else."""
        self.assertIsNone(self.eng.parse_engine_version("17.0.1\nsomething else\n", "maestro"))

    def test_a_build_that_fails_has_named_no_version(self):
        """`--version` exiting non-zero is not a version."""
        with mock.patch.object(
            self.eng.subprocess,
            "run",
            return_value=mock.Mock(returncode=1, stdout="Maestro 1.39.0\n", stderr=""),
        ):
            self.assertIsNone(self.eng.engine_version("maestro"))

    def test_a_build_that_names_no_version_has_named_no_version(self):
        with mock.patch.object(
            self.eng.subprocess,
            "run",
            return_value=mock.Mock(returncode=0, stdout="Maestro version unavailable\n", stderr=""),
        ):
            self.assertIsNone(self.eng.engine_version("maestro"))

    def test_the_runner_probes_the_selected_engine_the_same_way(self):
        """The runner engine is read by the same recognition as the CLI."""
        with mock.patch.object(self.eng, "engine_version", return_value="1.2.3") as probe:
            self.assertEqual(self.mod.maestro_version("maestro-runner"), "1.2.3")
        self.assertEqual(probe.call_args.args[0], "maestro-runner")

    def test_the_runner_version_helper_is_not_a_second_way_in(self):
        """The old bypass -- a helper with looser rules -- is gone."""
        with mock.patch.object(self.eng, "runner_version", return_value="9.9.9"):
            with mock.patch.object(self.eng, "engine_version", return_value=None):
                self.assertIsNone(self.mod.maestro_version("maestro-runner"))


class SnapshotFailureTest(unittest.TestCase):
    """A half-copied snapshot is removed, not left behind.

    The snapshot is what the attempts run, so a copy that failed halfway is
    a directory that looks like a snapshot and holds part of the tree.
    Nothing may read it, and nothing may clean it up later -- the caller
    never gets the path, because the copy never returned.
    """

    def setUp(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows_snapshot", runner_path)
        self.mod = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = self.mod
        spec.loader.exec_module(self.mod)

    def test_a_failed_copy_leaves_nothing_behind(self):
        flow = "Maestro/flows/tasks/01-a.yaml"
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "Maestro" / "flows" / "tasks").mkdir(parents=True)
            (root / "Maestro" / "flows" / "tasks" / "01-a.yaml").write_text("appId: x\n", encoding="utf-8")
            with mock.patch.object(self.mod, "ROOT", root):
                with mock.patch.object(
                    self.mod.shutil,
                    "copytree",
                    side_effect=OSError("the copy failed"),
                ):
                    with self.assertRaises(OSError):
                        self.mod.snapshot_flow(flow)
                snapshot_root = root / "build" / "maestro-qualification-snapshot" / "Maestro__flows__tasks__01-a"
                self.assertFalse(snapshot_root.exists(), "a half-copied snapshot must not survive")


class StaleApkTest(unittest.TestCase):
    """The qualification runs the APK built from this checkout, or it runs nothing.

    The runner refuses to run a flow against an installed APK that is not the
    checkout's build, and `--allow-stale-apk` is the switch that turns that
    check off. A qualification is evidence about *this* checkout, so the
    qualification runner never forwards the switch: a record that certified an
    older APK would certify a different program than the one the change is
    about.
    """

    def setUp(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows_apk", runner_path)
        self.mod = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = self.mod
        spec.loader.exec_module(self.mod)

    def test_the_switch_is_refused(self):
        """The run refuses before it spends a device run on a stale APK."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            with mock.patch.object(self.mod.subprocess, "run") as run_mock:
                code = self.mod.main(
                    [
                        "--flow",
                        "Maestro/flows/tasks/01-a.yaml",
                        "--allow-stale-apk",
                        "--record",
                        "--evidence",
                        str(tmp_path / "evidence.yaml"),
                        "--out",
                        str(tmp_path / "out"),
                    ]
                )
        self.assertEqual(code, 2)
        run_mock.assert_not_called()

    def test_the_attempt_never_carries_the_switch(self):
        """The switch is not forwarded to the runner that runs the attempt."""
        captured: list[list[str]] = []

        def run(cmd, *args, **kwargs):
            captured.append(list(cmd))
            return mock.Mock(returncode=2, stdout="", stderr="")

        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp)
            with mock.patch.object(self.mod, "maestro_version", return_value="1.39.0"):
                with mock.patch.object(self.mod.subprocess, "run", side_effect=run):
                    self.mod.run_one_attempt(
                        "Maestro/flows/tasks/01-a.yaml", 1, out, None, None, False, []
                    )
        self.assertTrue(captured, "the attempt has to run the runner")
        for cmd in captured:
            self.assertNotIn("--allow-stale-apk", cmd)


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
        """No engine version, no qualification — and no attempts spent on it.

        A record has to name the build that ran the attempt, and the admission
        gate rejects a record without it. The version is therefore a
        prerequisite, not a note: the run refuses before the attempts instead
        of spending three device runs per flow on evidence nothing can accept.
        """
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            attempts: list[list[str]] = []

            def fake_run(cmd, *args, **kwargs):
                if "--version" not in cmd:
                    attempts.append(list(cmd))
                return mock.Mock(returncode=1, stdout="", stderr="")

            with mock.patch.object(self.mod.subprocess, "run", side_effect=fake_run):
                code = self.mod.main(
                    [
                        "--flow",
                        "Maestro/flows/tasks/01-a.yaml",
                        "--attempts",
                        "3",
                        "--record",
                        "--evidence",
                        str(tmp_path / "evidence.yaml"),
                        "--out",
                        str(tmp_path / "out"),
                    ]
                )
        self.assertEqual(code, 2)
        self.assertEqual(attempts, [], "no attempt may run without a version to record")

    def test_an_attempt_without_a_version_never_runs_the_device(self):
        """The per-attempt probe is a prerequisite too, not a warning."""
        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp)
            with mock.patch.object(self.mod, "maestro_version", return_value=None):
                with mock.patch.object(self.mod.subprocess, "run") as run_mock:
                    attempt = self.mod.run_one_attempt(
                        "Maestro/flows/tasks/01-a.yaml", 1, out, None, None, False, []
                    )
        run_mock.assert_not_called()
        self.assertEqual(attempt.outcome, "infra_failure")
        self.assertIn("version could not be detected", attempt.reason)
        self.assertIsNone(attempt.maestro_version)

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
    """A flow that reads content outside the qualification tree cannot be qualified.

    The fingerprint describes the tree, and the attempts run a snapshot of the
    tree. A reference that leaves it -- an absolute path, a relative path that
    climbs out of the repository, or a path that stays in the repository but
    leaves `Maestro/` -- points at content none of them covers: an edit there
    would go unnoticed, and the evidence would attest to a flow nobody ran
    in full.
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
        return mod.scan(
            root,
            catalogue,
            baseline,
            evidence,
            initial,
            digest,
            baseline_digest_of(baseline),
        )

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
        self.assertIn("leaves the repository", findings[0].detail)

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

    def test_reference_leaving_the_qualification_tree_is_refused(self):
        """A reference inside the repository but outside `Maestro/` is refused.

        The validator, the fingerprint and the snapshot have to name the same
        tree. A reference that stays in the repository but leaves `Maestro/`
        is content the snapshot does not contain: the gate would admit the
        flow and the attempt would fail on a file the qualification never
        covered.
        """
        root, flow, catalogue, baseline, evidence = self._tree(
            "appId: x\n---\n- runScript: ../../../scripts/helper.js\n"
        )
        write_flow(root / "scripts/helper.js", "console.log('x');\n")
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["untracked dependency"])
        self.assertIn("leaves the qualification tree", findings[0].detail)
        self.assertEqual(counts["baselined"], 0)

    def test_reference_to_a_missing_file_is_refused(self):
        """A reference to a file that is not there is refused.

        The validator used to drop such a reference silently: the flow reads
        something that does not exist, the attempt cannot run, and the
        qualification spends three runs on a failure the gate could have
        named before the first attempt.
        """
        root, flow, catalogue, baseline, evidence = self._tree(
            "appId: x\n---\n- runFlow: ../../helpers/missing.yaml\n"
        )
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["untracked dependency"])
        self.assertIn("is not a file", findings[0].detail)
        self.assertEqual(counts["baselined"], 0)

    def test_reference_to_a_directory_is_refused(self):
        """A reference to a directory is not a dependency either."""
        root, flow, catalogue, baseline, evidence = self._tree(
            "appId: x\n---\n- runFlow: ../../helpers/h.yaml\n"
        )
        (root / "Maestro/helpers/h.yaml").mkdir(parents=True, exist_ok=True)
        write_baseline(baseline, {flow: mod.fingerprint(flow, root)})
        findings, counts = self._scan(root, catalogue, baseline, evidence)
        self.assertEqual([f.kind for f in findings], ["untracked dependency"])
        self.assertIn("is not a file", findings[0].detail)
        self.assertEqual(counts["baselined"], 0)

    def test_a_reference_outside_the_tree_is_not_in_the_fingerprint(self):
        """The fingerprint covers the qualification tree and nothing else.

        A file the flow reaches outside `Maestro/` is content the snapshot
        does not contain, so it must not be part of the fingerprint the
        record claims to attest to.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_flow(root / "Maestro/flows/tasks/01-a.yaml", "appId: x\n---\n")
            write_flow(root / "scripts/helper.js", "console.log('x');\n")
            deps = mod.flow_dependencies("Maestro/flows/tasks/01-a.yaml", root)
        self.assertNotIn("scripts/helper.js", deps)


class EnvironmentIdentityTest(unittest.TestCase):
    """The device is part of the identity of a qualification run."""

    def _record(self, serials):
        return {
            "fingerprint": "sha256:" + "a" * 64,
            "attempts": [
                {
                    "attempt": i + 1,
                    "outcome": "pass",
                    "environment": {
                        "kind": "emulator",
                        "api": 35,
                        "device": "Pixel",
                        "serial": serial,
                    },
                    "commit": "abc123",
                    "apk_commit_sha": "abc123",
                    "maestro_version": "1.39.0",
                }
                for i, serial in enumerate(serials)
            ],
        }

    def test_two_devices_are_not_one_environment(self):
        ok, reason = mod.is_qualified(
            self._record(["emulator-5554", "emulator-5554", "emulator-5556"]),
            "sha256:" + "a" * 64,
            3,
        )
        self.assertFalse(ok)
        self.assertIn("environment", reason)

    def test_one_device_is_one_environment(self):
        """The positive control: three runs on one device are one environment."""
        ok, reason = mod.is_qualified(
            self._record(["emulator-5554"] * 3), "sha256:" + "a" * 64, 3
        )
        self.assertTrue(ok, reason)

    def test_attempt_without_a_device_is_not_a_qualification(self):
        """The serial is required, not merely compared.

        An attempt that does not name the device it ran on cannot be told
        apart from a run on another device, so it does not establish the
        identity the qualification is about.
        """
        record = self._record(["emulator-5554"] * 3)
        for attempt in record["attempts"]:
            attempt["environment"].pop("serial")
        ok, reason = mod.is_qualified(record, "sha256:" + "a" * 64, 3)
        self.assertFalse(ok)
        self.assertIn("serial", reason)

    def test_runner_flags_attempts_on_two_devices(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows", runner_path)
        runner = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = runner
        spec.loader.exec_module(runner)
        record = {
            "attempts": [
                {
                    "attempt": 1,
                    "outcome": "pass",
                    "environment": {
                        "kind": "emulator",
                        "api": 35,
                        "device": "Pixel",
                        "serial": "emulator-5554",
                    },
                    "commit": "abc123",
                    "apk_commit_sha": "abc123",
                    "maestro_version": "1.39.0",
                },
                {
                    "attempt": 2,
                    "outcome": "pass",
                    "environment": {
                        "kind": "emulator",
                        "api": 35,
                        "device": "Pixel",
                        "serial": "emulator-5556",
                    },
                    "commit": "abc123",
                    "apk_commit_sha": "abc123",
                    "maestro_version": "1.39.0",
                },
            ]
        }
        issues = runner.record_issues(record)
        self.assertTrue(any("environment" in issue for issue in issues), issues)


class ApkProvenanceTest(unittest.TestCase):
    """The APK that ran the attempt has to be the build of this checkout.

    A qualification certifies that the flow in *this* checkout passes on a
    device. If the attempt ran an APK built from another commit, the
    qualification says nothing about this checkout -- so the gate refuses a
    record whose two identities disagree, and the runner says so before it
    writes one.
    """

    def _record(self, checkout="abc123", apk="abc123"):
        return {
            "fingerprint": "sha256:" + "a" * 64,
            "attempts": [
                {
                    "attempt": i + 1,
                    "outcome": "pass",
                    "environment": {
                        "kind": "emulator",
                        "api": 35,
                        "device": "Pixel",
                        "serial": "emulator-5554",
                    },
                    "commit": checkout,
                    "apk_commit_sha": apk,
                    "maestro_version": "1.39.0",
                }
                for i in range(3)
            ],
        }

    def _runner(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows_provenance", runner_path)
        runner = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = runner
        spec.loader.exec_module(runner)
        return runner

    def test_the_same_build_on_both_sides_is_qualified(self):
        """The positive control: the APK is this checkout's build."""
        ok, reason = mod.is_qualified(self._record(), "sha256:" + "a" * 64, 3)
        self.assertTrue(ok, reason)

    def test_an_apk_from_another_commit_is_not_qualified(self):
        """The failure mode this pins: a stale APK qualified the flow."""
        ok, reason = mod.is_qualified(
            self._record(checkout="abc123", apk="def456"), "sha256:" + "a" * 64, 3
        )
        self.assertFalse(ok)
        self.assertIn("not the checkout build", reason)

    def test_a_record_without_the_apk_identity_is_not_qualified(self):
        """Without the APK identity there is nothing to compare, so nothing is certified."""
        record = self._record()
        for attempt in record["attempts"]:
            attempt.pop("apk_commit_sha")
        ok, reason = mod.is_qualified(record, "sha256:" + "a" * 64, 3)
        self.assertFalse(ok)
        self.assertIn("checkout and the APK identity", reason)

    def test_a_record_without_the_checkout_identity_is_not_qualified(self):
        record = self._record()
        for attempt in record["attempts"]:
            attempt.pop("commit")
        ok, reason = mod.is_qualified(record, "sha256:" + "a" * 64, 3)
        self.assertFalse(ok)
        self.assertIn("checkout and the APK identity", reason)

    def test_the_runner_refuses_to_record_a_stale_apk(self):
        issues = self._runner().record_issues(self._record(checkout="abc123", apk="def456"))
        self.assertTrue(any("not the checkout build" in issue for issue in issues), issues)

    def test_the_runner_refuses_to_record_a_missing_apk_identity(self):
        record = self._record()
        for attempt in record["attempts"]:
            attempt.pop("apk_commit_sha")
        issues = self._runner().record_issues(record)
        self.assertTrue(
            any("checkout and the APK identity" in issue for issue in issues), issues
        )


class VersionRecognitionTest(unittest.TestCase):
    """The recorded engine version has to be a version the engines recognise.

    A record that names "Maestro version unavailable" as the engine that
    ran the flow names a build that never ran. The recognition is the
    runner's contract -- the same one `emulator.maestro._engine` applies
    before the runner writes a record -- and the gate applies it too, so
    the two cannot disagree about what a version is.
    """

    def _record(self, version="1.39.0"):
        return {
            "fingerprint": "sha256:" + "a" * 64,
            "attempts": [
                {
                    "attempt": i + 1,
                    "outcome": "pass",
                    "environment": {
                        "kind": "emulator",
                        "api": 35,
                        "device": "Pixel",
                        "serial": "emulator-5554",
                    },
                    "commit": "abc123",
                    "apk_commit_sha": "abc123",
                    "maestro_version": version,
                }
                for i in range(3)
            ],
        }

    def _runner(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows_version", runner_path)
        runner = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = runner
        spec.loader.exec_module(runner)
        return runner

    def test_a_recognised_version_is_qualified(self):
        ok, reason = mod.is_qualified(self._record(), "sha256:" + "a" * 64, 3)
        self.assertTrue(ok, reason)

    def test_a_string_that_is_not_a_version_is_not_qualified(self):
        """The failure mode this pins: a record naming something that is not a version."""
        ok, reason = mod.is_qualified(
            self._record(version="not-a-version"), "sha256:" + "a" * 64, 3
        )
        self.assertFalse(ok)
        self.assertIn("not a recognised version", reason)

    def test_a_sentence_is_not_a_version(self):
        ok, reason = mod.is_qualified(
            self._record(version="Maestro version unavailable"), "sha256:" + "a" * 64, 3
        )
        self.assertFalse(ok)
        self.assertIn("not a recognised version", reason)

    def test_a_banner_is_not_a_version_number(self):
        """The record carries the version, not the banner it was read from."""
        ok, reason = mod.is_qualified(
            self._record(version="Maestro 1.39.0"), "sha256:" + "a" * 64, 3
        )
        self.assertFalse(ok)
        self.assertIn("not a recognised version", reason)

    def test_a_missing_version_is_not_qualified(self):
        record = self._record()
        for attempt in record["attempts"]:
            attempt.pop("maestro_version")
        ok, reason = mod.is_qualified(record, "sha256:" + "a" * 64, 3)
        self.assertFalse(ok)
        self.assertIn("does not record the Maestro version", reason)

    def test_the_runner_refuses_to_record_an_unrecognised_version(self):
        issues = self._runner().record_issues(self._record(version="not-a-version"))
        self.assertTrue(any("not a recognised version" in issue for issue in issues), issues)


class GateRunnerParityTest(unittest.TestCase):
    """The gate and the runner apply one contract to one record.

    The rules live in the gate module, and the runner asks the gate
    module for them instead of keeping a second copy. A record the
    runner would refuse to write is a record the gate refuses to admit —
    otherwise the runner's refusal and the gate's admission would be two
    different contracts.
    """

    def _runner(self):
        runner_path = pathlib.Path(__file__).resolve().parent.parent / "maestro-qualify-flows.py"
        spec = importlib.util.spec_from_file_location("maestro_qualify_flows_parity", runner_path)
        runner = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        sys.modules[spec.name] = runner
        spec.loader.exec_module(runner)
        return runner

    def _record(self):
        return {
            "fingerprint": "sha256:" + "a" * 64,
            "attempts": [
                {
                    "attempt": i + 1,
                    "outcome": "pass",
                    "environment": {
                        "kind": "emulator",
                        "api": 35,
                        "device": "Pixel",
                        "serial": "emulator-5554",
                    },
                    "commit": "abc123",
                    "apk_commit_sha": "abc123",
                    "maestro_version": "1.39.0",
                }
                for i in range(3)
            ],
        }

    def _assert_both_refuse(self, record, expected):
        ok, reason = mod.is_qualified(record, "sha256:" + "a" * 64, 3)
        self.assertFalse(ok)
        self.assertIn(expected, reason)
        issues = self._runner().record_issues(record)
        self.assertTrue(
            any(expected in issue for issue in issues),
            f"the runner did not report {expected!r}: {issues}",
        )

    def test_a_missing_checkout_identity_is_refused_by_both(self):
        record = self._record()
        for attempt in record["attempts"]:
            attempt.pop("commit")
        self._assert_both_refuse(record, "checkout and the APK identity")

    def test_a_missing_apk_identity_is_refused_by_both(self):
        record = self._record()
        for attempt in record["attempts"]:
            attempt.pop("apk_commit_sha")
        self._assert_both_refuse(record, "checkout and the APK identity")

    def test_two_builds_are_refused_by_both(self):
        record = self._record()
        record["attempts"][2]["commit"] = "def456"
        record["attempts"][2]["apk_commit_sha"] = "def456"
        self._assert_both_refuse(record, "do not share one build identity")

    def test_two_devices_are_refused_by_both(self):
        record = self._record()
        record["attempts"][2]["environment"]["serial"] = "emulator-5556"
        self._assert_both_refuse(record, "do not share one documented environment")

    def test_two_versions_are_refused_by_both(self):
        record = self._record()
        record["attempts"][2]["maestro_version"] = "1.40.0"
        self._assert_both_refuse(record, "do not share one Maestro version")

    def test_a_valid_record_is_refused_by_neither(self):
        record = self._record()
        ok, reason = mod.is_qualified(record, "sha256:" + "a" * 64, 3)
        self.assertTrue(ok, reason)
        self.assertEqual([], self._runner().record_issues(record))


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
            with mock.patch.object(self.mod, "maestro_version", return_value="1.39.0"):
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
        # No attempt ran: the flow was refused before the device was touched.
        for call_args, _ in run.call_args_list:
            self.assertNotIn("--junit-dir", call_args[0])
        self.assertEqual(data.get("qualifications") or {}, {})


    def test_flow_that_only_the_snapshot_reaches_is_refused_without_attempts(self):
        """The snapshot is validated, not only the working tree.

        A reference that leaves the qualification tree can be introduced
        between the check of the working tree and the copy of the snapshot.
        What the attempts would run is the snapshot, so the snapshot is what
        has to be checked -- otherwise the evidence covers content the gate
        never saw.
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
            run = mock.Mock(returncode=0)
            checked: list[pathlib.Path] = []

            def fake_dependency_issues(flow_rel, root):
                checked.append(root)
                # The working tree is clean; the snapshot is not.
                if root == self.mod.ROOT:
                    return []
                return ["01-a.yaml references /etc/x.yaml"]

            with mock.patch.object(self.mod, "maestro_version", return_value="1.39.0"):
                with mock.patch.object(
                    self.mod.GATE, "dependency_issues", side_effect=fake_dependency_issues
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
        # The tree was checked, and so was the snapshot.
        self.assertGreaterEqual(len(checked), 2)
        self.assertNotEqual(checked[0], checked[1])
        # No attempt ran: the snapshot was refused before the device was touched.
        for call_args, _ in run.call_args_list:
            self.assertNotIn("--junit-dir", call_args[0])
        self.assertEqual(data.get("qualifications") or {}, {})


    def test_runner_reports_a_baseline_that_does_not_match_its_pin(self):
        """The runner says why it cannot run, instead of raising a traceback.

        The runner reads the admission through the gate, and the gate refuses
        a baseline that does not match its pinned digest. The refusal has to
        reach the person who ran the command, not as a stack trace.
        """
        repo = pathlib.Path(__file__).resolve().parent.parent.parent
        runner = repo / "scripts" / "maestro-qualify-flows.py"
        source = repo / "config" / "docs" / "maestro-flow-qualification-baseline.yaml"
        with tempfile.TemporaryDirectory() as tmp:
            baseline = pathlib.Path(tmp) / "baseline.yaml"
            data = yaml.safe_load(source.read_text(encoding="utf-8")) or {}
            flows = data.get("flows") or {}
            first = sorted(flows)[0]
            flows[first] = "sha256:" + "0" * 64
            baseline.write_text(
                yaml.safe_dump({"schema": 1, "flows": flows}, sort_keys=False), encoding="utf-8"
            )
            result = subprocess.run(
                [
                    sys.executable,
                    str(runner),
                    "--admission-needed",
                    "--list",
                    "--baseline",
                    str(baseline),
                ],
                cwd=repo,
                capture_output=True,
                text=True,
            )
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("pinned digest", result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
