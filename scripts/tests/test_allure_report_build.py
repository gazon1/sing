"""Tests for `scripts/ci/allure_report_build.py`.

The publication used to be built by a third-party Docker action that rebuilt
its image on every run and downloaded the Allure CLI from Maven Central
without a retry. These tests pin the replacement: the CLI is installed from a
pinned artifact with a pinned digest, a mirror that fails is skipped, and the
publish tree is assembled out of the generated report.

Every test is offline: the download is a fake, the CLI is a fake runner, and
the tarball is one this module builds itself.
"""

from __future__ import annotations

import hashlib
import importlib.util
import io
import os
import pathlib
import sys
import tarfile
import tempfile
import unittest
import urllib.error

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / "ci" / "allure_report_build.py"
spec = importlib.util.spec_from_file_location("allure_report_build", MODULE_PATH)
arb = importlib.util.module_from_spec(spec)
sys.modules["allure_report_build"] = arb
spec.loader.exec_module(arb)

VERSION = "2.44.0"


def _tarball(members: dict[str, bytes] | None = None, mode: int = 0o755) -> bytes:
    """A tarball of an Allure CLI, or of whatever the test needs."""
    if members is None:
        members = {f"allure-{VERSION}/bin/allure": b"#!/bin/sh\nexit 0\n"}
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for name, payload in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            info.mode = mode
            tar.addfile(info, io.BytesIO(payload))
    return buffer.getvalue()


class FakeOpener:
    """A `urlopen` that answers from a table of URLs and records its calls."""

    def __init__(self, answers: dict[str, bytes | Exception]):
        self.answers = answers
        self.calls: list[str] = []

    def __call__(self, request, timeout=None):
        url = request.full_url if hasattr(request, "full_url") else str(request)
        self.calls.append(url)
        answer = self.answers.get(url)
        if isinstance(answer, Exception):
            raise answer
        if answer is None:
            raise urllib.error.URLError(f"{url}: 404 Not Found")
        return io.BytesIO(answer)


def _mirror_url(mirror: str, version: str = VERSION) -> str:
    return arb.artifact_url(mirror, version)


class ArtifactUrlTest(unittest.TestCase):
    """The address of the CLI on a mirror."""

    def test_the_url_names_the_version_and_the_artifact(self):
        url = _mirror_url("https://example.test/maven2")
        self.assertEqual(
            f"https://example.test/maven2/{VERSION}/allure-commandline-{VERSION}.tgz",
            url,
        )

    def test_a_trailing_slash_does_not_double(self):
        self.assertEqual(
            _mirror_url("https://example.test/maven2"),
            _mirror_url("https://example.test/maven2/"),
        )


class InstallTest(unittest.TestCase):
    """Installing the CLI: retries, mirrors and the pinned digest."""

    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.dest = pathlib.Path(self.workspace.name) / "cli"
        # The archive this test serves is its own, so the digest it is
        # accepted under is the digest of that archive. The digest the module
        # pins is the digest of the real artifact, and it is what the mirrors
        # of the workflow serve.
        self.payload = _tarball()
        self.sha256 = hashlib.sha256(self.payload).hexdigest()

    def _install(self, opener, **kwargs):
        return arb.install_allure(
            self.dest,
            sha256=kwargs.pop("sha256", self.sha256),
            opener=opener,
            sleeper=lambda _seconds: None,
            **kwargs,
        )

    def test_the_cli_is_installed_and_the_executable_is_where_it_is_expected(self):
        opener = FakeOpener({_mirror_url(arb.MAVEN_MIRRORS[0]): self.payload})
        executable = self._install(opener)
        self.assertTrue(executable.is_file())
        self.assertTrue(str(executable).endswith(f"allure-{VERSION}/bin/allure"))
        self.assertTrue(os.access(executable, os.X_OK))

    def test_the_pinned_digest_is_a_digest_and_not_a_placeholder(self):
        # The install refuses every download whose digest is not the pinned
        # one, so a placeholder here would refuse every download at runtime.
        self.assertRegex(arb.ALLURE_SHA256, r"^[0-9a-f]{64}$")
        self.assertNotEqual("0" * 64, arb.ALLURE_SHA256)

    def test_a_mirror_that_fails_is_skipped_for_the_next_one(self):
        opener = FakeOpener(
            {
                _mirror_url(arb.MAVEN_MIRRORS[0]): urllib.error.URLError("429 Too Many Requests"),
                _mirror_url(arb.MAVEN_MIRRORS[1]): self.payload,
            }
        )
        executable = self._install(opener)
        self.assertTrue(executable.is_file())
        # The failing mirror is retried before the next one is tried.
        self.assertEqual(3, sum(1 for call in opener.calls if call == _mirror_url(arb.MAVEN_MIRRORS[0])))
        self.assertEqual(1, sum(1 for call in opener.calls if call == _mirror_url(arb.MAVEN_MIRRORS[1])))

    def test_a_mirror_that_serves_other_bytes_is_not_trusted(self):
        opener = FakeOpener(
            {
                _mirror_url(arb.MAVEN_MIRRORS[0]): b"not the pinned artifact",
                _mirror_url(arb.MAVEN_MIRRORS[1]): self.payload,
            }
        )
        executable = self._install(opener)
        self.assertTrue(executable.is_file())

    def test_a_mirror_is_tried_more_than_once_before_the_next_one(self):
        failing = urllib.error.URLError("connection reset")
        opener = FakeOpener(
            {
                _mirror_url(arb.MAVEN_MIRRORS[0]): failing,
                _mirror_url(arb.MAVEN_MIRRORS[1]): self.payload,
            }
        )
        self._install(opener, attempts=3)
        self.assertEqual(4, len(opener.calls))
        self.assertEqual(3, sum(1 for call in opener.calls if call == _mirror_url(arb.MAVEN_MIRRORS[0])))

    def test_the_install_fails_when_every_mirror_failed(self):
        opener = FakeOpener({})
        with self.assertRaises(arb.BuildError) as caught:
            self._install(opener, attempts=1)
        message = str(caught.exception)
        for mirror in arb.MAVEN_MIRRORS:
            self.assertIn(mirror, message)

    def test_an_install_that_is_there_is_not_repeated(self):
        opener = FakeOpener({_mirror_url(arb.MAVEN_MIRRORS[0]): self.payload})
        first = self._install(opener)
        second = self._install(FakeOpener({}))
        self.assertEqual(first, second)

    def test_an_archive_that_leaves_the_tree_is_refused(self):
        payload = _tarball({"../escape": b"x"})
        opener = FakeOpener({_mirror_url(arb.MAVEN_MIRRORS[0]): payload})
        with self.assertRaises(arb.BuildError):
            self._install(opener, attempts=1)

    def test_an_archive_that_holds_a_link_is_refused(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
            info = tarfile.TarInfo(f"allure-{VERSION}/bin/allure")
            info.type = tarfile.SYMTYPE
            info.linkname = "/etc/passwd"
            tar.addfile(info)
        opener = FakeOpener({_mirror_url(arb.MAVEN_MIRRORS[0]): buffer.getvalue()})
        with self.assertRaises(arb.BuildError):
            self._install(opener, attempts=1)


class ExecutorDocumentTest(unittest.TestCase):
    """The executor the report carries."""

    def test_the_address_of_the_site_is_the_one_the_site_answers_with(self):
        document = arb.executor_document(
            report_name="Allure report (fast)",
            population="fast",
            run_number="42",
            site_url="http://maxdrobin.ru/sing",
        )
        self.assertEqual("http://maxdrobin.ru/sing/fast", document["url"])
        self.assertEqual("http://maxdrobin.ru/sing/fast/42", document["reportUrl"])

    def test_an_unknown_address_leaves_the_link_out(self):
        document = arb.executor_document(
            report_name="Allure report (fast)",
            population="fast",
            run_number="42",
        )
        self.assertNotIn("url", document)
        self.assertNotIn("reportUrl", document)

    def test_the_build_is_named(self):
        document = arb.executor_document(
            report_name="Allure report (fast)",
            population="fast",
            run_number="42",
            run_id="1234",
            repo="gazon1/sing",
        )
        self.assertEqual("https://github.com/gazon1/sing/actions/runs/1234", document["buildUrl"])
        self.assertEqual("GitHub Actions Run #1234", document["buildName"])


class ReportTreeTest(unittest.TestCase):
    """The publish tree the build assembles."""

    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.root = pathlib.Path(self.workspace.name)
        self.gh_pages = self.root / "gh-pages"
        self.results = self.root / "results"
        self.report = self.root / "report"
        self.history = self.root / "allure-history"
        (self.gh_pages / "fast" / "last-history").mkdir(parents=True)
        (self.gh_pages / "fast" / "last-history" / "old.json").write_text("{}", encoding="utf-8")
        (self.gh_pages / "CNAME").write_text("maxdrobin.ru\n", encoding="utf-8")
        self.results.mkdir()
        (self.results / "1-result.json").write_text("{}", encoding="utf-8")

    def _runner(self, calls):
        def run(argv, **_kwargs):
            calls.append(argv)
            target = pathlib.Path(argv[argv.index("-o") + 1])
            (target / "widgets").mkdir(parents=True, exist_ok=True)
            (target / "index.html").write_text("<html></html>", encoding="utf-8")
            (target / "history").mkdir(parents=True, exist_ok=True)
            (target / "history" / "new.json").write_text("{}", encoding="utf-8")
            return type("Result", (), {"returncode": 0, "stdout": "", "stderr": ""})()

        return run

    def test_the_history_of_the_population_is_seeded_into_the_results(self):
        arb.seed_results_history(self.gh_pages, "fast", self.results)
        self.assertTrue((self.results / "history" / "old.json").is_file())

    def test_a_population_without_a_history_seeds_nothing(self):
        self.assertEqual(0, arb.seed_results_history(self.gh_pages, "full", self.results))
        self.assertFalse((self.results / "history").exists())

    def test_the_report_is_published_under_the_population_and_the_run(self):
        (self.report / "widgets").mkdir(parents=True)
        (self.report / "index.html").write_text("<html></html>", encoding="utf-8")
        arb.publish_report(self.report, self.history, "fast", "42")
        self.assertTrue((self.history / "fast" / "42" / "index.html").is_file())

    def test_the_history_of_the_report_goes_to_last_history(self):
        (self.report / "history").mkdir(parents=True)
        (self.report / "history" / "new.json").write_text("{}", encoding="utf-8")
        arb.publish_report(self.report, self.history, "fast", "42")
        self.assertTrue((self.history / "fast" / "last-history" / "new.json").is_file())

    def test_the_build_puts_the_report_into_the_publish_tree(self):
        calls: list[list[str]] = []
        executor = arb.executor_document(
            report_name="Allure report (fast)",
            population="fast",
            run_number="42",
            site_url="http://maxdrobin.ru/sing",
        )
        result = arb.build_report_tree(
            executable=pathlib.Path("/bin/true"),
            results_dir=self.results,
            report_dir=self.report,
            gh_pages_dir=self.gh_pages,
            history_dir=self.history,
            population="fast",
            run_number="42",
            executor=executor,
            runner=self._runner(calls),
        )
        self.assertTrue((self.history / "fast" / "42" / "index.html").is_file())
        self.assertTrue((self.history / "CNAME").is_file())
        self.assertTrue((self.results / "executor.json").is_file())
        self.assertEqual("http://maxdrobin.ru/sing/fast", result["site_url"])
        self.assertEqual(1, len(calls))

    def test_a_cli_that_fails_is_a_failure_of_the_build(self):
        def run(argv, **_kwargs):
            return type("Result", (), {"returncode": 1, "stdout": "", "stderr": "boom"})()

        with self.assertRaises(arb.BuildError):
            arb.generate_report(pathlib.Path("/bin/true"), self.results, self.report, runner=run)

    def test_a_cli_that_produces_no_report_is_a_failure_of_the_build(self):
        def run(argv, **_kwargs):
            return type("Result", (), {"returncode": 0, "stdout": "", "stderr": ""})()

        with self.assertRaises(arb.BuildError):
            arb.generate_report(pathlib.Path("/bin/true"), self.results, self.report, runner=run)


class CliTest(unittest.TestCase):
    """The command line of the helper."""

    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.root = pathlib.Path(self.workspace.name)

    def test_the_build_refuses_to_run_without_a_cli(self):
        code = arb.main(
            [
                "build",
                "--allure-dir",
                str(self.root / "missing"),
                "--results-dir",
                str(self.root / "results"),
                "--report-dir",
                str(self.root / "report"),
                "--gh-pages-dir",
                str(self.root / "gh-pages"),
                "--history-dir",
                str(self.root / "history"),
                "--population",
                "fast",
                "--run-number",
                "42",
            ]
        )
        self.assertEqual(1, code)


if __name__ == "__main__":
    unittest.main()
