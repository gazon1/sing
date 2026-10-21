"""Tests for check-rebase-compiles.py — the post-rebase DI compile gate.

The defect this gate exists to catch: a rebase can pull in changes to
`*DiModule.kt` or other import-sensitive files that compile in isolation and
break the tree of whoever pulls next. This is not a general "did the build
break" monitor — the tests job covers that. It is a fast pre-flight check for
the DI surface that regresses most often after a large rebase.

Two properties decide whether the gate can do that job:

* **it must not skip when it cannot prove it does not need to run.** When no
  upstream branch is configured the gate collects every DI-sensitive file in
  the tree instead of returning nothing, because "a gate that skips when it
  cannot prove it needs to run is a gate that cannot fail on a clean-tree
  sabotage" — the exact defect it was written to catch;
* **it must not compile when nothing import-sensitive changed**, because a
  needless Gradle invocation is the difference between a pre-flight check and a
  tax.

So the tests assert the classification of changed files, the two ways the
changed-file set is computed (with and without an upstream), and the gate's
verdict in all three branches — skip, nothing DI-sensitive, and
DI-sensitive-and-compile-failed.

Two observations about the current classification, which these tests pin rather
than change (the gate is not ours to edit):

* `DI_SENSITIVE_PATTERNS` holds three *directory* patterns
  (`core/di/`, `feature/*/di/`, `core/coroutines/`) and one *file* pattern
  (`feature/*/*DiModule.kt`). The directory patterns are matched against file
  paths, and a path never ends in `/`, so they match nothing: today only a file
  named `*DiModule.kt` under a `feature/` directory is classified as
  DI-sensitive, and `core/di/CoreDiModule.kt` is not. The tests assert that as
  it is, so the change that fixes it (if it is a defect) is visible.
* the classification is therefore narrower than the docstring claims, and the
  gate's compile check runs on a smaller file set than the docstring says.

Gradle is never invoked: `gradle_compile` is exercised with a stubbed
`run_cmd`, and the gate's own decision to call it is asserted directly.
"""

import contextlib
import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_rebase_compiles", SCRIPTS / "check-rebase-compiles.py"
)
rebase = importlib.util.module_from_spec(_spec)
sys.modules["check_rebase_compiles"] = rebase
_spec.loader.exec_module(rebase)

FEATURE_DI_FILE = (
    "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/di/TasksDiModule.kt"
)
CORE_DI_FILE = "shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt"
COROUTINES_FILE = (
    "shared/src/commonMain/kotlin/com/singularity/todo/core/coroutines/Scopes.kt"
)
PLAIN_FILE = "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/Task.kt"


class DiSensitiveTest(unittest.TestCase):
    """Which paths count as import-sensitive."""

    def test_a_feature_di_module_is_sensitive(self):
        self.assertEqual([FEATURE_DI_FILE], rebase.di_sensitive([FEATURE_DI_FILE]))

    def test_a_di_module_under_a_feature_is_sensitive(self):
        """`*DiModule.kt` under a feature is a DI module whatever it is called."""
        path = "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/FooDiModule.kt"
        self.assertEqual([path], rebase.di_sensitive([path]))

    def test_a_plain_feature_file_is_not_sensitive(self):
        self.assertEqual([], rebase.di_sensitive([PLAIN_FILE]))

    def test_a_documentation_file_is_not_sensitive(self):
        self.assertEqual([], rebase.di_sensitive(["README.md", "docs/adr/x.md"]))

    def test_only_the_sensitive_subset_is_returned(self):
        changed = [PLAIN_FILE, FEATURE_DI_FILE, "README.md"]
        self.assertEqual([FEATURE_DI_FILE], rebase.di_sensitive(changed))

    def test_a_core_di_module_is_not_detected_by_the_directory_patterns(self):
        """Pins the current classification, which is narrower than the docstring.

        `core/di/` is a directory pattern matched against a file path, and a
        file path never ends in `/`. The gate therefore does not treat
        `core/di/CoreDiModule.kt` as DI-sensitive today. This test exists so
        that the day the pattern is fixed, the change is a visible one.
        """
        self.assertEqual([], rebase.di_sensitive([CORE_DI_FILE]))

    def test_a_coroutines_module_is_not_detected_by_the_directory_patterns(self):
        """Same pinning for `core/coroutines/` — see the test above."""
        self.assertEqual([], rebase.di_sensitive([COROUTINES_FILE]))


class ChangedFilesTest(unittest.TestCase):
    """The two ways the changed-file set is computed."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        patcher = unittest.mock.patch.object(
            rebase, "__file__", str(self.root / "scripts" / "check-rebase-compiles.py")
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def _stub_git(self, responses):
        calls = []

        def run_cmd(cmd, capture=True):
            calls.append(cmd)
            for prefix, response in responses.items():
                if cmd[: len(prefix)] == list(prefix):
                    return response
            return 1, ""

        return run_cmd, calls

    def test_without_an_upstream_the_di_files_in_the_tree_are_collected(self):
        """The gate must not skip when it cannot prove it does not need to run.

        An empty list here would make the gate exit 0 without compiling
        anything — a sabotage of the DI graph would pass.
        """
        feature = self.root / "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/di"
        feature.mkdir(parents=True)
        (feature / "TasksDiModule.kt").write_text("class TasksDiModule\n", encoding="utf-8")
        plain = self.root / "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks"
        plain.mkdir(parents=True, exist_ok=True)
        (plain / "Task.kt").write_text("class Task\n", encoding="utf-8")

        run_cmd, _calls = self._stub_git({("git", "rev-parse"): (1, "")})
        with unittest.mock.patch.object(rebase, "run_cmd", run_cmd):
            changed = rebase.changed_files_since_upstream(quiet=True)
        self.assertTrue(
            any(f.endswith("feature/tasks/di/TasksDiModule.kt") for f in changed),
            changed,
        )
        self.assertFalse(
            any(f.endswith("feature/Task.kt") for f in changed),
            changed,
        )

    def test_with_an_upstream_the_diff_against_it_is_the_changed_set(self):
        run_cmd, _calls = self._stub_git({
            ("git", "rev-parse"): (0, "origin/main\n"),
            ("git", "diff", "--name-only", "HEAD@{upstream}", "HEAD"): (0, FEATURE_DI_FILE + "\n"),
            ("git", "diff", "--name-only", "HEAD"): (0, ""),
        })
        with unittest.mock.patch.object(rebase, "run_cmd", run_cmd):
            changed = rebase.changed_files_since_upstream(quiet=True)
        self.assertEqual([FEATURE_DI_FILE], changed)

    def test_uncommitted_changes_are_included(self):
        """The gate runs on a dirty tree before pushing."""
        run_cmd, _calls = self._stub_git({
            ("git", "rev-parse"): (0, "origin/main\n"),
            ("git", "diff", "--name-only", "HEAD@{upstream}", "HEAD"): (0, FEATURE_DI_FILE + "\n"),
            ("git", "diff", "--name-only", "HEAD"): (0, PLAIN_FILE + "\n"),
        })
        with unittest.mock.patch.object(rebase, "run_cmd", run_cmd):
            changed = rebase.changed_files_since_upstream(quiet=True)
        self.assertEqual([FEATURE_DI_FILE, PLAIN_FILE], changed)


class GradleCompileTest(unittest.TestCase):
    """The compile invocation, without ever invoking Gradle."""

    def test_a_successful_compile_reports_success(self):
        seen = {}

        def run_cmd(cmd, capture=True):
            seen["cmd"] = cmd
            return 0, ""

        with unittest.mock.patch.object(rebase, "run_cmd", run_cmd):
            self.assertTrue(rebase.gradle_compile([":shared:compileKotlinJvm"], quiet=False))
        self.assertEqual(
            ["./gradlew", ":shared:compileKotlinJvm", "--no-configuration-cache", "--no-daemon"],
            seen["cmd"],
        )

    def test_a_failed_compile_reports_failure(self):
        with unittest.mock.patch.object(rebase, "run_cmd", lambda cmd, capture=True: (1, "boom")):
            self.assertFalse(rebase.gradle_compile([":shared:compileKotlinJvm"], quiet=False))

    def test_the_quiet_flag_is_passed_to_gradle(self):
        seen = {}

        def run_cmd(cmd, capture=True):
            seen["cmd"] = cmd
            return 0, ""

        with unittest.mock.patch.object(rebase, "run_cmd", run_cmd):
            rebase.gradle_compile([":shared:compileKotlinJvm"], quiet=True)
        self.assertIn("--quiet", seen["cmd"])


class CliTest(unittest.TestCase):
    """The gate's verdict in its three branches."""

    def _run(self, changed, compiles, *args):
        buffer = io.StringIO()
        with (
            unittest.mock.patch.object(
                rebase, "changed_files_since_upstream", lambda quiet: list(changed)
            ),
            unittest.mock.patch.object(rebase, "gradle_compile", lambda tasks, quiet: compiles),
        ):
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                code = rebase.run(rebase.cli.main, list(args))
        return code, buffer.getvalue()

    def test_nothing_changed_skips_the_compile(self):
        code, output = self._run([], True)
        self.assertEqual(0, code, output)
        self.assertIn("skipping", output)

    def test_no_di_sensitive_change_skips_the_compile(self):
        """A needless Gradle invocation is a tax, not a check."""
        code, output = self._run([PLAIN_FILE], True)
        self.assertEqual(0, code, output)
        self.assertIn("no DI-sensitive files changed", output)

    def test_a_di_change_that_fails_to_compile_fails_the_gate(self):
        code, output = self._run([FEATURE_DI_FILE], False)
        self.assertEqual(1, code, output)
        self.assertIn("COMPILE FAILED", output)

    def test_a_di_change_that_compiles_passes(self):
        code, output = self._run([FEATURE_DI_FILE], True)
        self.assertEqual(0, code, output)
        self.assertIn("compile succeeded", output)

    def test_the_changed_di_files_are_named(self):
        """The report says which files forced the compile."""
        code, output = self._run([FEATURE_DI_FILE, PLAIN_FILE], True)
        self.assertEqual(0, code, output)
        self.assertIn(FEATURE_DI_FILE, output)
        self.assertNotIn(PLAIN_FILE, output)

    def test_the_quiet_mode_prints_nothing_when_there_is_nothing_to_do(self):
        code, output = self._run([], True, "--quiet")
        self.assertEqual(0, code, output)
        self.assertEqual("", output.strip())


if __name__ == "__main__":
    unittest.main()
