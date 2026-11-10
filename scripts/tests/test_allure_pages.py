import importlib.util
import io
import json
import pathlib
import sys
import tarfile
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / "ci" / "allure_pages.py"
spec = importlib.util.spec_from_file_location("allure_pages", MODULE_PATH)
ap = importlib.util.module_from_spec(spec)
sys.modules["allure_pages"] = ap
spec.loader.exec_module(ap)


def pub(
    run_id=100,
    run_number=10,
    run_attempt=1,
    event="push",
    sha="abc",
    population="fast",
    report_path=None,
):
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
        self.root = pathlib.Path(self.tmp.name)
        self.site = self.root / "site"
        self.site.mkdir()
        self.gh = self.root / "gh-pages"
        self.gh.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def pop_dir(self, site, population="fast"):
        d = pathlib.Path(site) / population
        d.mkdir(parents=True, exist_ok=True)
        (d / "last-history").mkdir(exist_ok=True)
        return d

    def make_report(self, site, population="fast", path="10"):
        pdir = self.pop_dir(site, population)
        report_dir = pdir / path
        report_dir.mkdir(parents=True, exist_ok=True)
        (report_dir / "index.html").write_text("<html>report</html>", encoding="utf-8")
        return report_dir

    def manifest(self, site, population="fast"):
        return ap.load_manifest(ap.manifest_path(pathlib.Path(site) / population), population)

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

    def test_rejects_unknown_population(self):
        with self.assertRaises(ap.PublishError):
            pub(population="canary")
        with self.assertRaises(ap.PublishError):
            ap.prepare_namespace(self.gh, "canary")

    def test_empty_manifest_publishes(self):
        pdir = self.pop_dir(self.site, "fast")
        manifest = ap.load_manifest(ap.manifest_path(pdir), "fast")
        action, _ = ap.decide(manifest, pub(population="fast"))
        self.assertEqual(action, "publish")

    def test_ordering_newer_run_number_wins(self):
        latest = pub(run_id=1, run_number=10, population="fast")
        manifest = {
            "schema_version": 2,
            "population": "fast",
            "latest": latest.to_dict(),
            "publications": {latest.identity: latest.to_dict()},
        }
        action, _ = ap.decide(manifest, pub(run_id=2, run_number=11, population="fast"))
        self.assertEqual(action, "publish")

    def test_late_older_run_is_stale(self):
        latest = pub(run_id=2, run_number=11, population="fast")
        manifest = {
            "schema_version": 2,
            "population": "fast",
            "latest": latest.to_dict(),
            "publications": {latest.identity: latest.to_dict()},
        }
        action, reason = ap.decide(manifest, pub(run_id=1, run_number=10, population="fast"))
        self.assertEqual(action, "skip-stale")
        self.assertIn("not newer", reason)

    def test_same_run_higher_attempt_wins(self):
        first = pub(run_id=5, run_number=10, run_attempt=1, population="fast")
        manifest = {
            "schema_version": 2,
            "population": "fast",
            "latest": first.to_dict(),
            "publications": {first.identity: first.to_dict()},
        }
        action, _ = ap.decide(manifest, pub(run_id=5, run_number=10, run_attempt=2, population="fast"))
        self.assertEqual(action, "publish")

    def test_same_identity_duplicate_is_idempotent(self):
        current = pub(run_id=5, run_number=10, run_attempt=1, population="fast")
        manifest = {
            "schema_version": 2,
            "population": "fast",
            "latest": current.to_dict(),
            "publications": {current.identity: current.to_dict()},
        }
        action, _ = ap.decide(manifest, current)
        self.assertEqual(action, "skip-duplicate")

    def test_same_identity_different_metadata_fails(self):
        current = pub(run_id=5, run_number=10, run_attempt=1, sha="abc", population="fast")
        manifest = {
            "schema_version": 2,
            "population": "fast",
            "latest": current.to_dict(),
            "publications": {current.identity: current.to_dict()},
        }
        with self.assertRaises(ap.PublishError):
            ap.decide(manifest, pub(run_id=5, run_number=10, run_attempt=1, sha="def", population="fast"))

    def test_apply_publish_updates_manifest_and_indexes(self):
        self.make_report(self.site, "fast", "10")
        result = ap.apply_publication(self.site, pub(run_id=1, run_number=10, population="fast", report_path="10"))
        self.assertEqual(result["action"], "publish")
        manifest = self.manifest(self.site, "fast")
        self.assertEqual(manifest["latest"]["run_id"], 1)
        self.assertIn("URL=10/index.html", (self.site / "fast" / "index.html").read_text(encoding="utf-8"))
        self.assertIn("fast/index.html", (self.site / "index.html").read_text(encoding="utf-8"))

    def test_apply_stale_does_not_regress_latest_or_indexes(self):
        self.make_report(self.site, "fast", "11")
        newer = pub(run_id=2, run_number=11, population="fast", report_path="11")
        ap.apply_publication(self.site, newer)
        # simulate late build overwriting population index toward older report
        self.make_report(self.site, "fast", "10")
        ap.write_population_index(self.site / "fast", "10")
        older = pub(run_id=1, run_number=10, population="fast", report_path="10")
        result = ap.apply_publication(self.site, older)
        self.assertEqual(result["action"], "skip-stale")
        manifest = self.manifest(self.site, "fast")
        self.assertEqual(manifest["latest"]["run_number"], 11)
        self.assertIn("URL=11/index.html", (self.site / "fast" / "index.html").read_text(encoding="utf-8"))
        self.assertNotIn("10-1", manifest["publications"])

    def test_apply_duplicate_is_idempotent(self):
        self.make_report(self.site, "fast", "10")
        current = pub(run_id=1, run_number=10, population="fast", report_path="10")
        ap.apply_publication(self.site, current)
        mpath = ap.manifest_path(self.site / "fast")
        before = mpath.read_text(encoding="utf-8")
        result = ap.apply_publication(self.site, current)
        self.assertEqual(result["action"], "skip-duplicate")
        self.assertEqual(before, mpath.read_text(encoding="utf-8"))

    def test_apply_missing_report_target_fails(self):
        self.pop_dir(self.site, "fast")
        with self.assertRaises(ap.PublishError):
            ap.apply_publication(self.site, pub(run_id=1, run_number=10, population="fast", report_path="10"))

    def test_validate_population_state_detects_missing_target(self):
        pdir = self.pop_dir(self.site, "fast")
        manifest = {
            "schema_version": 2,
            "population": "fast",
            "latest": pub(run_id=1, run_number=10, population="fast", report_path="10").to_dict(),
            "publications": {pub(run_id=1, run_number=10, population="fast", report_path="10").identity: pub(run_id=1, run_number=10, population="fast", report_path="10").to_dict()},
        }
        ap.save_manifest(ap.manifest_path(pdir), manifest)
        ap.write_population_index(pdir, "10")
        ap.write_root_index(self.site, ["fast"])
        with self.assertRaises(ap.PublishError):
            ap.validate_population_state(pdir, "fast", manifest)

    def test_malformed_manifest_fails(self):
        pdir = self.pop_dir(self.site, "fast")
        ap.manifest_path(pdir).write_text("{not json", encoding="utf-8")
        with self.assertRaises(ap.PublishError):
            ap.load_manifest(ap.manifest_path(pdir), "fast")

    def test_fast_full_histories_are_independent(self):
        self.make_report(self.site, "fast", "10")
        self.make_report(self.site, "full", "10")
        ap.apply_publication(self.site, pub(run_id=1, run_number=10, population="fast", report_path="10"))
        ap.apply_publication(self.site, pub(run_id=2, run_number=10, population="full", report_path="10", sha="def"))
        fast = self.manifest(self.site, "fast")
        full = self.manifest(self.site, "full")
        self.assertEqual(fast["latest"]["sha"], "abc")
        self.assertEqual(full["latest"]["sha"], "def")
        self.assertEqual(set(fast["publications"]), {"1-1"})
        self.assertEqual(set(full["publications"]), {"2-1"})
        # interleaving: fast update does not affect full latest/history state
        self.make_report(self.site, "fast", "11")
        ap.apply_publication(self.site, pub(run_id=3, run_number=11, population="fast", report_path="11"))
        full2 = self.manifest(self.site, "full")
        self.assertEqual(full2["latest"]["run_id"], 2)

    def test_sequence_fast_full_fast(self):
        for n, (pop, rid) in enumerate([("fast", 1), ("full", 2), ("fast", 3)], start=10):
            self.make_report(self.site, pop, str(n))
            ap.apply_publication(self.site, pub(run_id=rid, run_number=n, population=pop, report_path=str(n)))
        self.assertEqual(self.manifest(self.site, "fast")["latest"]["run_number"], 12)
        self.assertEqual(self.manifest(self.site, "full")["latest"]["run_number"], 11)
        self.assertNotIn("2-1", self.manifest(self.site, "fast")["publications"])

    def test_sequence_full_fast_full(self):
        for n, (pop, rid) in enumerate([("full", 1), ("fast", 2), ("full", 3)], start=10):
            self.make_report(self.site, pop, str(n))
            ap.apply_publication(self.site, pub(run_id=rid, run_number=n, population=pop, report_path=str(n)))
        self.assertEqual(self.manifest(self.site, "full")["latest"]["run_number"], 12)
        self.assertEqual(self.manifest(self.site, "fast")["latest"]["run_number"], 11)

    def test_prepare_namespace_resets_mixed_history_baseline(self):
        # simulate existing gh-pages with mixed root last-history
        (self.gh / "last-history").mkdir()
        (self.gh / "last-history" / "history.json").write_text("mixed", encoding="utf-8")
        result = ap.prepare_namespace(self.gh, "fast")
        self.assertTrue((self.gh / "fast" / "last-history").is_dir())
        self.assertFalse((self.gh / "fast" / "last-history" / "history.json").exists())
        self.assertTrue(result["clean_history"])

    def test_pruning_is_population_scoped_and_keeps_latest_and_history(self):
        for n in range(1, 8):  # 7 reports, keep 5
            self.make_report(self.site, "fast", str(n))
            ap.apply_publication(self.site, pub(run_id=n, run_number=n, population="fast", report_path=str(n)), keep_reports=5)
        pdir = self.site / "fast"
        remaining = sorted(d.name for d in pdir.iterdir() if d.is_dir() and d.name.isdigit())
        self.assertEqual(remaining, ["3", "4", "5", "6", "7"])
        self.assertTrue((pdir / "last-history").is_dir())
        self.assertTrue((pdir / "7" / "index.html").is_file())
        manifest = self.manifest(self.site, "fast")
        self.assertEqual(manifest["latest"]["run_number"], 7)
        self.assertNotIn("1-1", manifest["publications"])
        self.assertIn("7-1", manifest["publications"])

    def test_fast_pruning_cannot_remove_full_reports(self):
        for n in range(1, 8):
            self.make_report(self.site, "fast", str(n))
            ap.apply_publication(self.site, pub(run_id=n, run_number=n, population="fast", report_path=str(n)), keep_reports=5)
            self.make_report(self.site, "full", str(n))
            ap.apply_publication(self.site, pub(run_id=100 + n, run_number=n, population="full", report_path=str(n)), keep_reports=20)
        fast_remaining = sorted(d.name for d in (self.site / "fast").iterdir() if d.is_dir() and d.name.isdigit())
        full_remaining = sorted(d.name for d in (self.site / "full").iterdir() if d.is_dir() and d.name.isdigit())
        self.assertEqual(fast_remaining, ["3", "4", "5", "6", "7"])
        self.assertEqual(full_remaining, ["1", "2", "3", "4", "5", "6", "7"])

    def test_publish_tree_validation_rejects_git_and_temp_archives(self):
        self.make_report(self.site, "fast", "10")
        ap.apply_publication(self.site, pub(run_id=1, run_number=10, population="fast", report_path="10"))
        (self.site / ".git").mkdir()
        with self.assertRaises(ap.PublishError):
            ap.validate_publish_tree(self.site, ["fast"])
        import shutil
        shutil.rmtree(self.site / ".git")
        ap.validate_publish_tree(self.site, ["fast"])
        (self.site / "tmp.tar.gz").write_text("x", encoding="utf-8")
        with self.assertRaises(ap.PublishError):
            ap.validate_publish_tree(self.site, ["fast"])

    def test_publish_tree_requires_root_and_population_files(self):
        self.make_report(self.site, "fast", "10")
        ap.apply_publication(self.site, pub(run_id=1, run_number=10, population="fast", report_path="10"))
        ap.validate_publish_tree(self.site, ["fast"])
        (self.site / "fast" / "index.html").unlink()
        with self.assertRaises(ap.PublishError):
            ap.validate_publish_tree(self.site, ["fast"])

    def make_tarball(self, files, top="repo-gh-pages"):
        tar_path = self.root / "gh-pages.tar.gz"
        with tarfile.open(tar_path, "w:gz") as tar:
            for name, content in files.items():
                data = content.encode("utf-8")
                info = tarfile.TarInfo(name=f"{top}/{name}")
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        return tar_path

    def test_bootstrap_success_extracts_tarball(self):
        tar = self.make_tarball({"10/index.html": "ok", "last-history/x": "h", "index.html": "root"})
        result = ap.bootstrap_history_from_tarball(self.gh, tar, 200)
        self.assertEqual(result["status"], "ok")
        self.assertTrue((self.gh / "10" / "index.html").is_file())
        self.assertTrue((self.gh / "last-history").is_dir())

    def test_bootstrap_missing_branch_is_first_publication(self):
        result = ap.bootstrap_history_from_tarball(self.gh, None, 404)
        self.assertEqual(result["status"], "missing-branch")
        self.assertTrue((self.gh / "last-history").is_dir())
        self.assertFalse((self.gh / ".git").exists())

    def test_bootstrap_failed_http_leaves_clean_state(self):
        (self.gh / "junk.txt").write_text("partial", encoding="utf-8")
        result = ap.bootstrap_history_from_tarball(self.gh, None, 500)
        self.assertEqual(result["status"], "download-failed")
        self.assertFalse((self.gh / "junk.txt").exists())
        self.assertTrue((self.gh / "last-history").is_dir())

    def test_bootstrap_malformed_tarball_leaves_clean_state(self):
        bad = self.root / "gh-pages.tar.gz"
        bad.write_text("not a tarball", encoding="utf-8")
        (self.gh / "junk.txt").write_text("partial", encoding="utf-8")
        result = ap.bootstrap_history_from_tarball(self.gh, bad, 200)
        self.assertEqual(result["status"], "malformed-tarball")
        self.assertFalse((self.gh / "junk.txt").exists())
        self.assertTrue((self.gh / "last-history").is_dir())

    def test_bootstrap_cleans_partial_extraction_on_unsafe_path(self):
        tar_path = self.root / "gh-pages.tar.gz"
        with tarfile.open(tar_path, "w:gz") as tar:
            data = b"x"
            info = tarfile.TarInfo(name="top/../escape.txt")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        (self.gh / "junk.txt").write_text("partial", encoding="utf-8")
        result = ap.bootstrap_history_from_tarball(self.gh, tar_path, 200)
        self.assertIn(result["status"], {"malformed-tarball"})
        self.assertFalse((self.gh / "junk.txt").exists())
        self.assertFalse((self.root / "escape.txt").exists())

    def test_tree_stats_counts_files_and_bytes(self):
        self.make_report(self.site, "fast", "10")
        stats = ap.tree_stats(self.site)
        self.assertGreater(stats["files"], 0)
        self.assertGreater(stats["bytes"], 0)


if __name__ == "__main__":
    unittest.main()
