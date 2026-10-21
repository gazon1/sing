"""Tests for check-workflow-env-context.py — every `${{ env.X }}` must resolve.

The `env` context of a workflow holds the variables the *workflow* defines:
workflow-level, job-level and step-level `env:` blocks, plus the names an
earlier step wrote to `$GITHUB_ENV`. It does not hold the runner's own
environment. `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `RUNNER_TEMP` and `HOME` are
process variables, and `${{ env.ANDROID_HOME }}` expands to the empty string —
silently, with no warning and no error.

That is not a theory here: the Maestro shards failed a whole release cycle with
"'emulator' binary not found in PATH" because the cache paths were written
`${{ env.ANDROID_HOME }}/…`. The list expanded to `/system-images`, `/emulator`
and `/platform-tools` at the filesystem root, and the empty entry the cache
carried was a *hit* on every later run — and a cache hit means "do not save", so
the empty entry was permanent.

The properties asserted here are the ones that decide whether the gate can
catch that:

* a reference to a name the file does not define is reported, with the file and
  the name;
* a name defined at workflow, job or step level resolves, and so does a name a
  step publishes to `$GITHUB_ENV` — a gate that reported those would be a gate
  nobody could satisfy;
* the `|| 'fallback'` form the repository uses is still matched, because the
  fallback is exactly what makes the reference look safe;
* a mention inside a comment is not a reference — comments are not values, and
  the mistake that matters is in the code;
* a workflow that does not parse is a finding, not a silent pass;
* an absent workflow directory is an infrastructure failure (exit 2), not a
  clean bill of health: a gate that scans nothing reports "every reference
  resolves", which is exactly what it said when the directory was renamed.

The workflows are written to a `tempfile.TemporaryDirectory` and the gate's
`WORKFLOWS` global is pointed at it, so nothing in the repository is touched.
The clean-tree case is asserted against this repository's own workflows.
"""

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_workflow_env_context", SCRIPTS / "check-workflow-env-context.py"
)
envctx = importlib.util.module_from_spec(_spec)
sys.modules["check_workflow_env_context"] = envctx
_spec.loader.exec_module(envctx)

REPO_ROOT = SCRIPTS.parent


class WorkflowTreeTest(unittest.TestCase):
    """A temporary `.github/workflows/` directory."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.workflows = self.tmp / ".github" / "workflows"
        self.workflows.mkdir(parents=True)

    def write_workflow(self, name, text):
        path = self.workflows / name
        path.write_text(text, encoding="utf-8")
        return path

    def scan(self):
        with unittest.mock.patch.object(envctx, "WORKFLOWS", self.workflows):
            return envctx.scan()

    def run_gate(self, *args):
        buffer = io.StringIO()
        with unittest.mock.patch.object(envctx, "WORKFLOWS", self.workflows):
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                code = envctx.run(envctx.cli.main, list(args))
        return code, buffer.getvalue()


WORKFLOW_TEMPLATE = """\
name: build
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - run: echo build
"""


class UnresolvableTest(WorkflowTreeTest):
    """A reference the file cannot resolve."""

    def test_a_reference_to_an_undefined_name_is_reported(self):
        self.write_workflow(
            "ci.yml",
            WORKFLOW_TEMPLATE
            + "      - run: echo ${{ env.ANDROID_HOME }}\n",
        )
        findings = self.scan()
        self.assertEqual(1, len(findings), findings)
        self.assertEqual("ANDROID_HOME", findings[0]["name"])
        self.assertEqual("ci.yml", findings[0]["file"])

    def test_the_finding_explains_what_the_reference_expands_to(self):
        self.write_workflow(
            "ci.yml",
            WORKFLOW_TEMPLATE + "      - run: echo ${{ env.ANDROID_HOME }}\n",
        )
        findings = self.scan()
        self.assertIn("empty string", findings[0]["detail"])

    def test_the_gate_fails_on_an_unresolvable_reference(self):
        self.write_workflow(
            "ci.yml",
            WORKFLOW_TEMPLATE + "      - run: echo ${{ env.ANDROID_HOME }}\n",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("ANDROID_HOME", output)

    def test_the_fallback_form_is_still_a_reference(self):
        """`|| 'fallback'` is what makes the reference look safe."""
        self.write_workflow(
            "ci.yml",
            WORKFLOW_TEMPLATE + "      - run: echo ${{ env.ANDROID_HOME || '/opt' }}\n",
        )
        findings = self.scan()
        self.assertEqual(1, len(findings), findings)
        self.assertEqual("ANDROID_HOME", findings[0]["name"])


class ResolvableTest(WorkflowTreeTest):
    """The names a workflow does define."""

    def test_a_workflow_level_env_resolves(self):
        self.write_workflow(
            "ci.yml",
            "name: build\n"
            "on: [push]\n"
            "env:\n  ANDROID_HOME: /opt/android\n"
            "jobs:\n"
            "  build:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            "      - run: echo ${{ env.ANDROID_HOME }}\n",
        )
        self.assertEqual([], self.scan())

    def test_a_job_level_env_resolves(self):
        self.write_workflow(
            "ci.yml",
            "name: build\n"
            "on: [push]\n"
            "jobs:\n"
            "  build:\n"
            "    runs-on: ubuntu-latest\n"
            "    env:\n      ANDROID_HOME: /opt/android\n"
            "    steps:\n"
            "      - run: echo ${{ env.ANDROID_HOME }}\n",
        )
        self.assertEqual([], self.scan())

    def test_a_step_level_env_resolves(self):
        self.write_workflow(
            "ci.yml",
            "name: build\n"
            "on: [push]\n"
            "jobs:\n"
            "  build:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            "      - env:\n          ANDROID_HOME: /opt/android\n"
            "        run: echo ${{ env.ANDROID_HOME }}\n",
        )
        self.assertEqual([], self.scan())

    def test_a_name_published_to_github_env_resolves(self):
        self.write_workflow(
            "ci.yml",
            "name: build\n"
            "on: [push]\n"
            "jobs:\n"
            "  build:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            '      - run: echo "ANDROID_HOME=/opt/android" >> "$GITHUB_ENV"\n'
            "      - run: echo ${{ env.ANDROID_HOME }}\n",
        )
        self.assertEqual([], self.scan())

    def test_a_comment_is_not_a_reference(self):
        """A workflow that documents the mistake in a comment does not trip the gate."""
        self.write_workflow(
            "ci.yml",
            WORKFLOW_TEMPLATE + "      # never use ${{ env.ANDROID_HOME }} here\n",
        )
        self.assertEqual([], self.scan())


class ParseErrorTest(WorkflowTreeTest):
    """A workflow the gate cannot read is a finding, not a silent pass."""

    def test_a_workflow_that_does_not_parse_is_reported(self):
        self.write_workflow("ci.yml", "name: build\n  bad indent: [\n")
        findings = self.scan()
        self.assertEqual(1, len(findings), findings)
        self.assertEqual("parse error", findings[0]["where"])

    def test_a_parse_error_fails_the_gate(self):
        self.write_workflow("ci.yml", "name: build\n  bad indent: [\n")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)


class NoWorkflowsTest(unittest.TestCase):
    """An absent workflow directory means the gate did not run."""

    def test_an_absent_workflow_directory_is_an_io_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = pathlib.Path(tmp) / "no-workflows-here"
            buffer = io.StringIO()
            with unittest.mock.patch.object(envctx, "WORKFLOWS", missing):
                with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                    code = envctx.run(envctx.cli.main, [])
        self.assertEqual(envctx.IO_ERROR, code)
        self.assertIn("no workflows found", buffer.getvalue())

    def test_an_empty_workflow_directory_is_an_io_error(self):
        """Zero workflows is the same defect as no directory: nothing was scanned."""
        with tempfile.TemporaryDirectory() as tmp:
            empty = pathlib.Path(tmp)
            buffer = io.StringIO()
            with unittest.mock.patch.object(envctx, "WORKFLOWS", empty):
                with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                    code = envctx.run(envctx.cli.main, [])
        self.assertEqual(envctx.IO_ERROR, code)


class CliShapeTest(WorkflowTreeTest):
    """The gate's own reporting."""

    def test_the_json_output_carries_the_findings(self):
        self.write_workflow(
            "ci.yml",
            WORKFLOW_TEMPLATE + "      - run: echo ${{ env.ANDROID_HOME }}\n",
        )
        buffer = io.StringIO()
        with unittest.mock.patch.object(envctx, "WORKFLOWS", self.workflows):
            with contextlib.redirect_stdout(buffer):
                code = envctx.run(envctx.cli.main, ["--json"])
        payload = json.loads(buffer.getvalue())
        self.assertEqual(1, code)
        self.assertEqual(1, len(payload["findings"]))
        self.assertEqual("ANDROID_HOME", payload["findings"][0]["name"])

    def test_the_check_flag_reports_without_failing(self):
        self.write_workflow(
            "ci.yml",
            WORKFLOW_TEMPLATE + "      - run: echo ${{ env.ANDROID_HOME }}\n",
        )
        code, output = self.run_gate("--check")
        self.assertEqual(0, code, output)
        self.assertIn("ANDROID_HOME", output)

    def test_a_clean_tree_says_every_reference_resolves(self):
        self.write_workflow("ci.yml", WORKFLOW_TEMPLATE)
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertIn("every ${{ env.X }} resolves", output)


class RealWorkflowsTest(unittest.TestCase):
    """This repository's own workflows."""

    def test_the_real_workflows_have_no_unresolvable_references(self):
        findings = envctx.scan()
        self.assertEqual([], findings, findings)

    def test_the_real_gate_is_green(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            code = envctx.run(envctx.cli.main, [])
        self.assertEqual(0, code, buffer.getvalue())


if __name__ == "__main__":
    unittest.main()
