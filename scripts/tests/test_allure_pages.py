import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / "ci" / "allure_pages.py"
spec = importlib.util.spec_from_file_location("allure_pages", MODULE_PATH)
ap = importlib.util.module_from_spec(spec)
sys.modules["allure_pages"] = ap
spec.loader.exec_module(ap)


def pub(run_id=100, run_number=10, run_attempt=1, event="push", sha="abc", population="fast", report_path=None):
    if report_path is None:
        report_path = str(run_number)
    return ap.Publication(
        run_id=run_id,
        run_number=run_number,
        run_attempt=run_attempt,
        event=event,
        sha=sha,
        population=population,
        report_path=report_path,
    )


class AllurePagesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.site = pathlib.Path(self.tmp.name)
        self.manifest_path = self.site / "allure-manifest.json"

    def tearDown(self):
        self.tmp.cleanup()

    def make_report(self, path="10"):
        report_dir = self.site / path
        report_dir.mkdir(parents=True, exist_ok=True)
        (report_dir / "index.html").write_text("<html>report</html>", encoding="utf-8")
        return report_dir

    def test_empty_manifest_publishes(self):
        manifest = ap.load_manifest(self.manifest_path)
        action, _ = ap.decide(manifest, pub())
        self.assertEqual(action, "publish")

    def test_ordering_newer_run_number_wins(self):
        manifest = {"schema_version": 1, "latest": pub(run_id=1, run_number=10).to_dict(), "publications": {pub(run_id=1, run_number=10).identity: pub(run_id=1, run_number=10).to_dict()}}
        action, _ = ap.decide(manifest, pub(run_id=2, run_number=11))
        self.assertEqual(action, "publish")

    def test_late_older_run_is_stale(self):
        latest = pub(run_id=2, run_number=11)
        manifest = {"schema_version": 1, "latest": latest.to_dict(), "publications": {latest.identity: latest.to_dict()}}
        action, reason = ap.decide(manifest, pub(run_id=1, run_number=10))
        self.assertEqual(action, "skip-stale")
        self.assertIn("not newer", reason)

    def test_same_run_higher_attempt_wins(self):
        first = pub(run_id=5, run_number=10, run_attempt=1)
        manifest = {"schema_version": 1, "latest": first.to_dict(), "publications": {first.identity: first.to_dict()}}
        action, _ = ap.decide(manifest, pub(run_id=5, run_number=10, run_attempt=2))
        self.assertEqual(action, "publish")

    def test_same_identity_duplicate_is_idempotent(self):
        current = pub(run_id=5, run_number=10, run_attempt=1)
        manifest = {"schema_version": 1, "latest": current.to_dict(), "publications": {current.identity: current.to_dict()}}
        action, reason = ap.decide(manifest, current)
        self.assertEqual(action, "skip-duplicate")
        self.assertIn("already", reason)

    def test_same_identity_different_metadata_fails(self):
        current = pub(run_id=5, run_number=10, run_attempt=1, sha="abc")
        manifest = {"schema_version": 1, "latest": current.to_dict(), "publications": {current.identity: current.to_dict()}}
        with self.assertRaises(ap.PublishError):
            ap.decide(manifest, pub(run_id=5, run_number=10, run_attempt=1, sha="def"))

    def test_apply_publish_updates_manifest_and_root_index(self):
        self.make_report("10")
        result = ap.apply_publication(self.site, self.manifest_path, pub(run_id=1, run_number=10))
        self.assertEqual(result["action"], "publish")
        manifest = ap.load_manifest(self.manifest_path)
        self.assertEqual(manifest["latest"]["run_id"], 1)
        self.assertIn("URL=10/index.html", (self.site / "index.html").read_text(encoding="utf-8"))

    def test_apply_stale_does_not_regress_latest_or_root(self):
        self.make_report("11")
        newer = pub(run_id=2, run_number=11)
        ap.apply_publication(self.site, self.manifest_path, newer)
        # simulate action overwriting root index to older report
        self.make_report("10")
        ap.write_root_index(self.site, "10")
        older = pub(run_id=1, run_number=10)
        result = ap.apply_publication(self.site, self.manifest_path, older)
        self.assertEqual(result["action"], "skip-stale")
        manifest = ap.load_manifest(self.manifest_path)
        self.assertEqual(manifest["latest"]["run_number"], 11)
        self.assertIn("URL=11/index.html", (self.site / "index.html").read_text(encoding="utf-8"))
        self.assertNotIn("10-1", manifest["publications"])

    def test_apply_duplicate_is_idempotent(self):
        self.make_report("10")
        current = pub(run_id=1, run_number=10)
        ap.apply_publication(self.site, self.manifest_path, current)
        before = self.manifest_path.read_text(encoding="utf-8")
        result = ap.apply_publication(self.site, self.manifest_path, current)
        self.assertEqual(result["action"], "skip-duplicate")
        self.assertEqual(before, self.manifest_path.read_text(encoding="utf-8"))

    def test_apply_missing_report_target_fails(self):
        with self.assertRaises(ap.PublishError):
            ap.apply_publication(self.site, self.manifest_path, pub(run_id=1, run_number=10))

    def test_validate_manifest_state_detects_missing_target(self):
        manifest = {
            "schema_version": 1,
            "latest": pub(run_id=1, run_number=10).to_dict(),
            "publications": {pub(run_id=1, run_number=10).identity: pub(run_id=1, run_number=10).to_dict()},
        }
        ap.save_manifest(self.manifest_path, manifest)
        ap.write_root_index(self.site, "10")
        with self.assertRaises(ap.PublishError):
            ap.validate_manifest_state(self.site, manifest)

    def test_validate_pages_input_requires_result_json(self):
        with tempfile.TemporaryDirectory() as td:
            d = pathlib.Path(td)
            with self.assertRaises(ap.PublishError):
                ap.validate_pages_input(d)
            (d / "foo-result.json").write_text("{}", encoding="utf-8")
            ap.validate_pages_input(d)

    def test_validate_pages_input_missing_dir_fails(self):
        with self.assertRaises(ap.PublishError):
            ap.validate_pages_input(self.site / "missing")

    def test_malformed_manifest_fails(self):
        self.manifest_path.write_text("{not json", encoding="utf-8")
        with self.assertRaises(ap.PublishError):
            ap.load_manifest(self.manifest_path)


if __name__ == "__main__":
    unittest.main()
