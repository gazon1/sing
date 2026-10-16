"""Tests for scripts/release_version.py.

The cases that matter are the ones that cost a tag run. Each of the three release
defects below was found by CI, not by a test, and each has a test here that fails on
the code that shipped it:

* a dashed version passed to Gradle — rpm refuses it, and because `packageVersion` is
  validated against every declared format, the deb job fails too;
* an underscore as the replacement — satisfies rpm, rejected by deb;
* `RELEASE=1` read as part of the version, so `0.2.1` verified as `0.2.1-1`.

A version module that only checks the happy path would not have caught any of them.
"""
import pathlib
import sys
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / "release_version.py"
sys.path.insert(0, str(MODULE_PATH.parent))

import release_version as rv  # noqa: E402


class ParseTest(unittest.TestCase):
    def test_plain_release(self):
        v = rv.parse("0.2.1")
        self.assertEqual(v.version, "0.2.1")
        self.assertEqual(v.version_code, 2001)
        self.assertFalse(v.prerelease)
        self.assertEqual(v.suffix, "")

    def test_leading_v_is_stripped(self):
        self.assertEqual(rv.parse("v0.2.1-rc.1").version, "0.2.1-rc.1")

    def test_prerelease(self):
        v = rv.parse("v0.2.1-rc.1")
        self.assertTrue(v.prerelease)
        self.assertEqual(v.suffix, "rc.1")
        self.assertEqual(v.base, "0.2.1")
        self.assertEqual(v.version_code, 2001)

    def test_version_code_scheme(self):
        self.assertEqual(rv.parse("0.0.0").version_code, 0)
        self.assertEqual(rv.parse("1.2.3").version_code, 1002003)

    def test_leading_zeros_normalised(self):
        # The shell stripped them with ${maj#0}; int() subsumes it, but the behaviour
        # must not silently change the versionCode a published APK carries.
        self.assertEqual(rv.parse("01.02.03").version_code, rv.parse("1.2.3").version_code)

    def test_rejects_nonsense(self):
        for raw in ("", "0.2", "0.2.1.2", "a.b.c", "0.2.1-", "0.2.1-rc-1", "0.2.1_rc.1"):
            with self.subTest(raw=raw), self.assertRaises(rv.ReleaseVersionError):
                rv.parse(raw)

    def test_rejects_component_overflow(self):
        # minor >= 1000 would overlap major in the versionCode arithmetic.
        with self.assertRaises(rv.ReleaseVersionError) as ctx:
            rv.parse("0.1000.0")
        self.assertIn("minor", str(ctx.exception))
        with self.assertRaises(rv.ReleaseVersionError) as ctx:
            rv.parse("0.0.1000")
        self.assertIn("patch", str(ctx.exception))


class PackageVersionTest(unittest.TestCase):
    """`packageVersion` is validated against BOTH formats at configuration time."""

    def test_dash_is_dropped(self):
        # rpm: 'Illegal version ... must not contain a dash'
        self.assertEqual(rv.parse("0.2.1-rc.1").package_version, "0.2.1rc.1")

    def test_plain_release_unchanged(self):
        self.assertEqual(rv.parse("0.2.1").package_version, "0.2.1")

    def test_no_underscore_is_produced(self):
        # deb: UPSTREAM_VERSION may contain only alphanumerics and '.', '+', '-', '~'.
        # Mapping the dash to an underscore satisfied rpm and broke deb — this is the
        # regression that the second failed tag run was about.
        self.assertNotIn("_", rv.parse("0.2.1-rc.1").package_version)

    def test_only_one_dash_can_be_present(self):
        # Guaranteed by the regex, but the substitution depends on it: a second dash
        # would need replacing too and the single replace() would leave it behind.
        with self.assertRaises(rv.ReleaseVersionError):
            rv.parse("0.2.1-rc-1")


class RpmEmbeddedTest(unittest.TestCase):
    def test_default_release_is_not_part_of_the_version(self):
        # jpackage stamps RELEASE=1; appending it made 0.2.1 fail as '0.2.1-1'.
        self.assertEqual(rv.rpm_embedded("0.2.1", "1"), "0.2.1")

    def test_non_default_release_counts(self):
        self.assertEqual(rv.rpm_embedded("0.2.1", "rc.1"), "0.2.1-rc.1")
        self.assertEqual(rv.rpm_embedded("0.2.1", "2"), "0.2.1-2")

    def test_empty_release(self):
        self.assertEqual(rv.rpm_embedded("0.2.1", ""), "0.2.1")


class DebEmbeddedTest(unittest.TestCase):
    def test_plain(self):
        self.assertEqual(rv.deb_embedded("0.2.1rc.1"), "0.2.1rc.1")

    def test_strips_debian_revision(self):
        self.assertEqual(rv.deb_embedded("0.2.1rc.1-1"), "0.2.1rc.1")


class VerifyTest(unittest.TestCase):
    def test_accepts_what_the_pipeline_builds(self):
        for tag, fmt_version in (("0.2.1", "0.2.1"), ("0.2.1-rc.1", "0.2.1rc.1")):
            with self.subTest(tag=tag):
                rv.verify("rpm", fmt_version, tag)

    def test_rejects_a_stale_package(self):
        # A package from an earlier build must not pass as the current tag.
        with self.assertRaises(rv.ReleaseVersionError):
            rv.verify("rpm", "0.2.0", "0.2.1")

    def test_rejects_a_prerelease_package_for_a_plain_tag(self):
        with self.assertRaises(rv.ReleaseVersionError):
            rv.verify("rpm", "0.2.1rc.1", "0.2.1")

    def test_rejects_a_plain_package_for_a_prerelease_tag(self):
        with self.assertRaises(rv.ReleaseVersionError):
            rv.verify("rpm", "0.2.1", "0.2.1-rc.1")

    def test_error_names_both_spellings(self):
        # The tag and the package disagree by design, so the message has to show both
        # or a reader cannot tell which one is wrong.
        with self.assertRaises(rv.ReleaseVersionError) as ctx:
            rv.verify("rpm", "nonsense", "0.2.1-rc.1")
        message = str(ctx.exception)
        self.assertIn("nonsense", message)
        self.assertIn("0.2.1rc.1", message)


class ManifestTest(unittest.TestCase):
    def test_lists_every_file(self):
        text = rv.manifest(rv.parse("0.2.1-rc.1"), ["b.apk", "a.deb"])
        self.assertIn("version=0.2.1-rc.1", text)
        self.assertIn("version_code=2001", text)
        self.assertIn("prerelease=true", text)
        # sorted, so the manifest is stable between runs
        self.assertIn("files=a.deb b.apk", text)

    def test_plain_release_is_not_a_prerelease(self):
        self.assertIn("prerelease=false", rv.manifest(rv.parse("0.2.1"), ["a.deb"]))

    def test_refuses_to_describe_nothing(self):
        # The manifest is the only thing standing between a green run and a release
        # with no files in it.
        with self.assertRaises(rv.ReleaseVersionError):
            rv.manifest(rv.parse("0.2.1"), [])


class ParseFailureTest(unittest.TestCase):
    """`parse` must fail loudly.

    The workflow reads it as `out=$(python3 … parse "$RAW") || exit 1` followed by
    `eval "$out"`. The earlier form, `eval "$(python3 … parse "$RAW")"`, took the
    exit status of `eval` rather than of python — so a rejected version produced a
    traceback, left `version` empty, and the release carried on. Both halves matter:
    python must return non-zero, and the error must be a `::error::` line rather than
    a stack trace in a CI log.
    """

    def test_parse_exits_nonzero(self):
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = rv.main(["parse", "1.2"])
        self.assertEqual(code, 1)

    def test_parse_reports_a_ci_error_not_a_traceback(self):
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            rv.main(["parse", "1.2"])
        self.assertIn("::error::", buffer.getvalue())
        self.assertIn("not X.Y.Z", buffer.getvalue())

    def test_parse_emits_no_assignments_on_failure(self):
        # `eval "$out"` applies whatever it is handed. The `::error::` line is
        # harmless (it is not a valid assignment, so eval fails and the earlier
        # `|| exit 1` has already stopped the step), but no `name=value` may appear —
        # a half-populated set of variables is how a rejected version becomes a
        # release that says nothing.
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            self.assertEqual(rv.main(["parse", "0.2.1000.0"]), 1)
        assignments = [
            line for line in buffer.getvalue().splitlines() if line.startswith(("version=", "prerelease=", "package_version="))
        ]
        self.assertEqual(assignments, [])

    def test_eval_form_cannot_swallow_the_failure(self):
        """The regression itself, run as the workflow runs it."""
        import subprocess

        script = 'out=$(python3 scripts/release_version.py parse "$RAW") || exit 1; eval "$out"; echo REACHED'
        env = {"RAW": "1.2", "PATH": "/usr/bin:/bin"}
        result = subprocess.run(
            ["bash", "-c", script], capture_output=True, text=True, cwd=MODULE_PATH.parent.parent, env=env
        )
        self.assertNotEqual(result.returncode, 0, "a rejected version must not reach the eval")
        self.assertNotIn("REACHED", result.stdout)


class CliTest(unittest.TestCase):
    def test_parse_emits_key_value_lines(self):
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            self.assertEqual(rv.main(["parse", "v0.2.1-rc.1"]), 0)
        self.assertEqual(
            buffer.getvalue().split(),
            [
                "version=0.2.1-rc.1",
                "version_code=2001",
                "prerelease=true",
                "package_version=0.2.1rc.1",
            ],
        )

    def test_parse_json(self):
        import io
        import contextlib
        import json

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            rv.main(["parse", "0.2.1", "--json"])
        self.assertEqual(json.loads(buffer.getvalue())["package_version"], "0.2.1")

    def test_verify_exits_nonzero_on_mismatch(self):
        # `main()` returns the exit code; only the `__main__` block raises SystemExit,
        # so a caller testing the function must check the return value, not an exception.
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = rv.main(["verify", "--format", "rpm", "--embedded", "0.2.0", "--tag", "0.2.1"])
        self.assertEqual(code, 1)
        self.assertIn("::error::", buffer.getvalue())


if __name__ == "__main__":
    unittest.main()