"""Tests for scripts/check-workflow-exit-status.py.

A gate that finds no `run:` blocks is worse than no gate: it certifies everything while
examining nothing. The first version of the block scanner did exactly that, because
PyYAML yields `(key, value)` as a tuple and the walker only accepted a list — it
returned zero blocks and reported `OK` on a repository containing the very defect it was
written for. So the first test here is not about pipelines at all; it is about whether
the scanner finds the steps.

The rest pin the three shapes that must not fire, each of which was a false positive in
the first working version, and the two that must.
"""
import importlib.util
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / "scripts" / "check-workflow-exit-status.py"
spec = importlib.util.spec_from_file_location("check_workflow_exit_status", MODULE_PATH)
cw = importlib.util.module_from_spec(spec)
sys.modules["check_workflow_exit_status"] = cw
spec.loader.exec_module(cw)


def _workflow(steps: str) -> str:
    return f"name: T\non: push\njobs:\n  j:\n    runs-on: ubuntu-24.04\n    steps:\n{steps}"


def _step(name: str, body: str, *, folded: bool = False) -> str:
    marker = ">" if folded else "|"
    indented = "\n".join(f"          {line}" for line in body.splitlines())
    return f"      - name: {name}\n        run: {marker}\n{indented}\n"


class ScannerFindsStepsTest(unittest.TestCase):
    """The scanner has to find steps at all. This is not a formality."""

    def test_a_literal_block_is_found(self):
        text = _workflow(_step("build", "./gradlew build"))
        blocks = cw._blocks(text)
        self.assertEqual(1, len(blocks), blocks)
        self.assertIn("gradlew", blocks[0][1])

    def test_a_folded_block_is_found(self):
        text = _workflow(_step("build", "./gradlew build", folded=True))
        blocks = cw._blocks(text)
        self.assertEqual(1, len(blocks), blocks)

    def test_a_one_line_run_is_found(self):
        text = _workflow("      - name: install\n        run: uv tool install specify-cli\n")
        self.assertEqual(1, len(cw._blocks(text)))

    def test_steps_in_several_jobs_are_all_found(self):
        text = (
            "name: T\non: push\njobs:\n"
            "  a:\n    runs-on: ubuntu-24.04\n    steps:\n"
            "      - name: one\n        run: |\n          echo 1 | cat\n"
            "  b:\n    runs-on: ubuntu-24.04\n    steps:\n"
            "      - name: two\n        run: |\n          echo 2 | cat\n"
        )
        self.assertEqual(2, len(cw._blocks(text)))


class FindsRealDefectsTest(unittest.TestCase):
    """The shapes the gate is for."""

    def _findings(self, steps):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "w.yml"
            path.write_text(_workflow(steps), encoding="utf-8")
            return cw.check_workflow(path)

    def test_an_unprotected_tee_is_reported(self):
        findings = self._findings(
            _step("gradle", "./gradlew --continue :shared:detekt 2>&1 | tee build.log")
        )
        self.assertEqual(1, len(findings), findings)
        self.assertIn("pipefail", findings[0])

    def test_pipefail_silences_it(self):
        findings = self._findings(
            _step("gradle", "set -o pipefail\n./gradlew --continue 2>&1 | tee build.log")
        )
        self.assertEqual([], findings)

    def test_pipefail_with_other_flags_silences_it(self):
        findings = self._findings(
            _step("gradle", "set -euo pipefail\n./gradlew 2>&1 | tee build.log")
        )
        self.assertEqual([], findings)

    def test_an_explicit_exit_handling_silences_it(self):
        for tail in ("|| exit 1", "|| { echo failed; exit 1; }", "${PIPESTATUS[0]}"):
            with self.subTest(tail=tail):
                body = f"set -o pipefail\n./gradlew 2>&1 | tee build.log\n{tail}"
                self.assertEqual([], self._findings(_step("gradle", body)))


class DoesNotFireTest(unittest.TestCase):
    """The three shapes that fired falsely the first time this ran for real."""

    def _findings(self, body):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "w.yml"
            path.write_text(_workflow(_step("s", body)), encoding="utf-8")
            return cw.check_workflow(path)

    def test_a_command_substitution_capture_is_not_a_pipeline(self):
        # `bash -e` fails the step when the substitution fails, and the step checks the
        # captured value itself. This is the shape ci.yml's android step uses.
        body = (
            'xml=$(find "$dir" -name "*.xml" -type f | head -1)\n'
            'if [ -z "$xml" ]; then exit 1; fi'
        )
        self.assertEqual([], self._findings(body))

    def test_a_process_substitution_is_not_a_pipeline(self):
        body = "mapfile -t ATTEMPTS < <(find attempts -type d | sort)"
        self.assertEqual([], self._findings(body))

    def test_a_pipe_inside_a_comment_is_not_a_pipeline(self):
        self.assertEqual([], self._findings("# ls … | head -n1 was the old way"))

    def test_a_pipe_inside_a_string_is_not_a_pipeline(self):
        self.assertEqual([], self._findings("echo 'a | b' > out.txt"))

    def test_a_pipe_split_across_lines_is_not_a_pipeline(self):
        # The masked `$( … )` on one line, then a continuation line starting with `|`,
        # read as one pipeline when the pattern's `\s` was allowed to cross newlines.
        body = (
            "embedded=$(dpkg-deb -f app.deb Version\n"
            "  | sed -n '1p')\n"
            "echo \"$embedded\""
        )
        self.assertEqual([], self._findings(body))

    def test_a_step_without_a_pipe_is_not_reported(self):
        self.assertEqual([], self._findings("echo hello"))


class MainTest(unittest.TestCase):
    def test_a_clean_tree_passes(self):
        self.assertEqual(0, cw.main(["--quiet"]))

    def test_a_defect_is_found_on_this_repository(self):
        # The gate is about this repository, so the committed workflows are its corpus.
        findings = []
        for path in sorted(cw.WORKFLOWS.glob("*.yml")):
            findings.extend(cw.check_workflow(path))
        self.assertEqual([], findings, findings)