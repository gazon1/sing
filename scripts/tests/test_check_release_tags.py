"""Tests for scripts/check-release-tags.py.

The gate has to fire on the failure it exists for. Three of these cases are the
defects that actually happened on 2026-10-16, reproduced as data:

* `MANIFEST.txt` that names nothing (it once contained shell source and `files=` was
  blank) while assets were attached;
* a `SHA256SUMS.txt` that does not cover an attached artifact;
* a tag whose run produced nothing.

And one that matters just as much: a gate that can never go green is the same debt as
an always-red advisory, so `tags_without_release` has to be honoured, and an exception
that contradicts the snapshot has to be rejected.
"""
import importlib.util
import pathlib
import tempfile
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / "scripts" / "check-release-tags.py"
spec = importlib.util.spec_from_file_location("check_release_tags", MODULE_PATH)
crt = importlib.util.module_from_spec(spec)
sys.modules["check_release_tags"] = crt
spec.loader.exec_module(crt)


def _release(tag="v0.2.1-rc.1", assets=None, checksums=None, manifest=None, prerelease=True):
    # `if assets is None`, never `assets or [...]`: an empty list is falsy, and the
    # `or` form silently substituted a default — which is precisely the case
    # `test_release_with_no_assets` exists to check.
    names = ["singularity-0.2.1-rc.1-android.apk"] if assets is None else assets
    checks = checksums if checksums is not None else ("a" * 64 + "  ./" + names[0] + "\n" if names else "")
    man = manifest if manifest is not None else (
        f"version={tag.lstrip('v')}\nversion_code=2001\nprerelease={'true' if prerelease else 'false'}\n"
        f"files={' '.join(names)}\n"
    )
    return {
        "tag": tag,
        "draft": True,
        "prerelease": prerelease,
        "assets": [{"name": n} for n in names],
        "sha256sums": checks,
        "manifest": man,
    }


class HappyPathTest(unittest.TestCase):
    def test_consistent_release_passes(self):
        snapshot = {"tags": ["v0.2.1-rc.1"], "releases": [_release()]}
        self.assertEqual(crt.check(snapshot), [])

    def test_plain_release_passes(self):
        release = _release(tag="v0.2.1", assets=["singularity-0.2.1-linux-x64.rpm"], prerelease=False)
        snapshot = {"tags": ["v0.2.1"], "releases": [release]}
        self.assertEqual(crt.check(snapshot), [])


class ManifestTest(unittest.TestCase):
    def test_manifest_naming_nothing_is_a_finding(self):
        # The real defect: `files=` was blank while three artifacts were attached.
        manifest = "version=0.2.1-rc.1\nversion_code=2001\nprerelease=true\nfiles= \n"
        snapshot = {
            "tags": ["v0.2.1-rc.1"],
            "releases": [_release(manifest=manifest)],
        }
        findings = crt.check(snapshot)
        self.assertTrue(any("MANIFEST" in f for f in findings), findings)

    def test_manifest_mentioning_shell_source_is_a_finding(self):
        manifest = (
            "version=0.2.1-rc.1\nversion_code=2001\nprerelease=true\n"
            "shopt -s nullglob\nartifacts=(dist/*.apk)\nfiles= \n"
        )
        findings = crt.check({"tags": [], "releases": [_release(manifest=manifest)]})
        self.assertTrue(any("MANIFEST" in f for f in findings), findings)

    def test_manifest_with_wrong_version_is_a_finding(self):
        manifest = "version=0.2.0\nversion_code=2000\nprerelease=true\nfiles=singularity-0.2.1-rc.1-android.apk\n"
        findings = crt.check({"tags": [], "releases": [_release(manifest=manifest)]})
        self.assertTrue(any("version" in f for f in findings), findings)

    def test_missing_manifest_is_a_finding(self):
        findings = crt.check({"tags": [], "releases": [_release(manifest="")]})
        self.assertTrue(any("MANIFEST" in f for f in findings), findings)


class ChecksumTest(unittest.TestCase):
    def test_artifact_without_a_checksum_is_a_finding(self):
        release = _release(assets=["a.apk", "b.deb"], checksums="a" * 64 + "  ./a.apk\n")
        findings = crt.check({"tags": [], "releases": [release]})
        self.assertTrue(any("b.deb" in f for f in findings), findings)

    def test_checksum_naming_an_absent_file_is_a_finding(self):
        release = _release(assets=["a.apk"], checksums="a" * 64 + "  ./a.apk\n" + "b" * 64 + "  ./ghost.apk\n")
        findings = crt.check({"tags": [], "releases": [release]})
        self.assertTrue(any("ghost.apk" in f for f in findings), findings)

    def test_checksum_file_hashing_itself_is_a_finding(self):
        release = _release(
            assets=["a.apk", "SHA256SUMS.txt"],
            checksums="a" * 64 + "  ./a.apk\n" + "b" * 64 + "  ./SHA256SUMS.txt\n",
        )
        findings = crt.check({"tags": [], "releases": [release]})
        self.assertTrue(any("itself" in f for f in findings), findings)

    def test_missing_checksums_file_is_a_finding(self):
        findings = crt.check({"tags": [], "releases": [_release(checksums="")]})
        self.assertTrue(any("SHA256SUMS" in f for f in findings), findings)

    def test_bookkeeping_files_are_not_required_to_be_artifacts(self):
        release = _release(
            assets=["a.apk", "MANIFEST.txt", "SHA256SUMS.txt"],
            checksums="a" * 64 + "  ./a.apk\n" + "c" * 64 + "  ./MANIFEST.txt\n",
            manifest="version=0.2.1-rc.1\nversion_code=2001\nprerelease=true\nfiles=a.apk\n",
        )
        self.assertEqual(crt.check({"tags": [], "releases": [release]}), [])


class TagTest(unittest.TestCase):
    def test_tag_with_no_release_is_a_finding(self):
        findings = crt.check({"tags": ["v0.9.9"], "releases": [_release()]})
        self.assertTrue(any("v0.9.9" in f and "no release" in f for f in findings), findings)

    def test_recorded_exception_is_honoured(self):
        # v0.1.0 and v0.2.0 can never get a release. A gate that demands one is red
        # forever, which is the advisory debt #667 is about.
        snapshot = {
            "tags": ["v0.1.0", "v0.2.1-rc.1"],
            "releases": [_release()],
            "tags_without_release": {"v0.1.0": "meta gate rejected it; CI could not install itself"},
        }
        self.assertEqual(crt.check(snapshot), [])

    def test_exception_contradicting_the_snapshot_is_a_finding(self):
        snapshot = {
            "tags": ["v0.2.1-rc.1"],
            "releases": [_release()],
            "tags_without_release": {"v0.2.1-rc.1": "stale entry"},
        }
        findings = crt.check(snapshot)
        self.assertTrue(any("stale entry" not in f and "v0.2.1-rc.1" in f for f in findings), findings)


class PrereleaseFlagTest(unittest.TestCase):
    def test_prerelease_tag_not_marked_prerelease(self):
        release = _release(prerelease=False)
        release["manifest"] = "version=0.2.1-rc.1\nversion_code=2001\nprerelease=true\nfiles=singularity-0.2.1-rc.1-android.apk\n"
        findings = crt.check({"tags": [], "releases": [release]})
        self.assertTrue(any("prerelease flag" in f for f in findings), findings)


class StructureTest(unittest.TestCase):
    def test_release_with_no_assets(self):
        findings = crt.check({"tags": [], "releases": [_release(assets=[], checksums="")]})
        self.assertTrue(any("no assets" in f for f in findings), findings)

    def test_duplicate_tag(self):
        findings = crt.check({"tags": [], "releases": [_release(), _release()]})
        self.assertTrue(any("twice" in f for f in findings), findings)

    def test_tag_this_pipeline_could_not_build(self):
        # A `nightly` or `latest` release was not built here; failing on the version
        # comparison would be the wrong reason, so it is named as such.
        release = _release(tag="nightly")
        findings = crt.check({"tags": [], "releases": [release]})
        self.assertTrue(any("not a release version" in f for f in findings), findings)


class RealSnapshotTest(unittest.TestCase):
    """The committed snapshot must itself be consistent."""

    def test_committed_snapshot_passes(self):
        path = ROOT / "config/docs/releases-snapshot.json"
        self.assertTrue(path.is_file(), "run: python3 scripts/check-release-tags.py --refresh")
        import json

        findings = crt.check(json.loads(path.read_text(encoding="utf-8")))
        self.assertEqual(findings, [])

    def test_every_recorded_exception_has_a_reason(self):
        import json

        path = ROOT / "config/docs/releases-snapshot.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        for tag, reason in data.get("tags_without_release", {}).items():
            with self.subTest(tag=tag):
                self.assertTrue(reason.strip(), f"{tag} is excused with no reason given")


class RefreshPreservesExceptionsTest(unittest.TestCase):
    """`--refresh` must not delete the hand-written reasons.

    GitHub cannot tell anyone *why* v0.1.0 never produced a release, only that it did
    not. A refresh that rewrote the snapshot from live data alone would convert every
    documented exception into a red finding and send the next person hunting for a
    reason that no longer exists anywhere.
    """

    def _refresh(self, existing, releases=()):
        import json
        import os
        import subprocess

        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "snapshot.json"
            path.write_text(json.dumps(existing), encoding="utf-8")
            # `refresh` shells out to `gh` and `git`; the test supplies both so it
            # neither needs a token nor reaches the network.
            bin_dir = pathlib.Path(tmp) / "bin"
            bin_dir.mkdir()
            listed = json.dumps([{"tagName": r, "isDraft": False, "isPrerelease": False} for r in releases])
            fake = bin_dir / "gh"
            fake.write_text(
                "#!/usr/bin/env bash\n"
                "if [[ $2 == list ]]; then echo " + json.dumps(listed) + "; exit 0; fi\n"
                "echo '{\"assets\": []}'\n",
                encoding="utf-8",
            )
            fake.chmod(0o755)
            env = {**os.environ, "PATH": f"{bin_dir}:{os.environ.get('PATH', '')}"}
            script = (
                "import pathlib,sys\n"
                f"sys.path.insert(0, {str(ROOT / 'scripts')!r})\n"
                "import importlib.util\n"
                f"spec=importlib.util.spec_from_file_location('crt', {str(MODULE_PATH)!r})\n"
                "m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
                "m.refresh(pathlib.Path(sys.argv[1]))\n"
            )
            subprocess.run([sys.executable, "-c", script, str(path)], check=True, cwd=ROOT, env=env)
            return json.loads(path.read_text(encoding="utf-8"))

    def test_reason_survives_a_refresh_that_still_finds_no_release(self):
        out = self._refresh(
            {
                "tags": ["v0.1.0"],
                "releases": [],
                "tags_without_release": {"v0.1.0": "meta gate rejected it"},
            }
        )
        self.assertEqual(out["tags_without_release"], {"v0.1.0": "meta gate rejected it"})

    def test_exception_is_dropped_once_the_tag_has_a_release(self):
        out = self._refresh(
            {
                "tags": ["v0.1.0"],
                "releases": [{"tag": "v0.1.0"}],
                "tags_without_release": {"v0.1.0": "now released"},
            },
            releases=["v0.1.0"],
        )
        self.assertEqual(out["tags_without_release"], {})

    def test_no_exceptions_key_means_no_key_written(self):
        out = self._refresh({"tags": [], "releases": []})
        self.assertEqual(out["tags_without_release"], {})


if __name__ == "__main__":
    unittest.main()