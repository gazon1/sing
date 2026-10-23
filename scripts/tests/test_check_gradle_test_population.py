#!/usr/bin/env python3
"""Tests for check-gradle-test-population.py.

The gate's own failure mode is the one it exists to prevent: a parser that stops
recognising an invocation yields an empty finding list, and an empty finding list
reads as a pass. So the fixtures assert on what the gate *saw*, not on the exit code
alone — every fixture materialises the script inside a fake repo, because the script
derives its root from its own location and a `cwd`-based harness would silently run it
against the real tree.
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / 'check-gradle-test-population.py'
#: The gate now imports its parser from here; a fixture that forgot it would fail
#: loudly rather than silently testing a different code path.
PARSER = pathlib.Path(__file__).resolve().parent.parent / 'src' / 'workflow_gradle.py'
#: The populations file — the definition the gate reads the populations and their
#: properties from. A fake repo without it is a fake repo the gate cannot judge.
POPULATIONS = SCRIPT.parent.parent / 'config' / 'test-populations.properties'

# The shape that produced the finding: one test run, then a Kover invocation without
# the selector. Gradle's default is excludeTags("slow"), so the re-run measures a
# different population than the gate that already counted the tests.
DISAGREEING = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: tests
        run: >
          ./gradlew --continue :shared:jvmTest :desktopApp:test
          -Ptest.tags=fast,slow 2>&1 | tee build.log
      - name: counts
        run: python3 scripts/check-test-runs.py --require desktopApp:test
      - name: coverage table
        run: ./gw :koverMarkdownTable >> "$GITHUB_STEP_SUMMARY" || true
'''

AGREEING = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: tests
        run: >
          ./gradlew --continue :shared:jvmTest :desktopApp:test
          -Ptest.tags=fast,slow 2>&1 | tee build.log
      - name: coverage floors
        run: ./gradlew :koverVerify -Ptest.tags=fast,slow
      - name: coverage table
        run: ./gw :koverMarkdownTable -Ptest.tags=fast,slow >> "$GITHUB_STEP_SUMMARY" || true
'''

# One invocation, no selector: not a violation. The rule is parity, not "always declare".
SINGLE_DEFAULT = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: tests
        run: ./gradlew :shared:jvmTest :desktopApp:test
'''

# A compile-only step after a test run is not a second population.
COMPILE_AFTER_TEST = '''
name: ci
on: [push]
jobs:
  build:
    runs-on: ubuntu-24.04
    steps:
      - name: tests
        run: ./gradlew :shared:jvmTest -Ptest.tags=fast,slow
      - name: apk
        run: ./gradlew :androidApp:assembleDebug
'''

# Two jobs in one file are two surfaces: a mismatch inside one does not implicate the
# other, and a job that never runs tests is not a surface at all.
TWO_JOBS_ONE_CLEAN = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: tests
        run: ./gradlew :shared:jvmTest -Ptest.tags=fast,slow
      - name: verify
        run: ./gradlew :koverVerify -Ptest.tags=fast,slow
  lint:
    runs-on: ubuntu-24.04
    steps:
      - run: ./gradlew :shared:detekt
'''

# The selector spelled with a space, which Gradle accepts just the same.
SPACE_SELECTOR = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: tests
        run: ./gradlew :shared:jvmTest -Ptest.tags fast,slow
      - name: verify
        run: ./gradlew :koverVerify -Ptest.tags=fast,slow
'''

# A commented-out invocation is not an invocation. Commented-out code that runs is the
# gate's own hazard, not this gate's business.
COMMENTED_OUT = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: tests
        run: ./gradlew :shared:jvmTest -Ptest.tags=fast,slow
      - name: old
        run: |
          # ./gradlew :koverVerify
          echo done
'''

# `assembleDebug` and `compileDebugKotlin` must not be read as test tasks: they neither
# run tests nor produce the JUnit XML the later steps read.
COMPILE_TOKENS = '''
name: ci
on: [push]
jobs:
  release:
    runs-on: ubuntu-24.04
    steps:
      - run: ./gradlew :desktopApp:assembleRelease :androidApp:compileReleaseKotlin
'''

# ── The populations and their properties ─────────────────────────────────────
#
# The shape the CI workflow has: the population is chosen by NAME, and the step
# that needs to know whether it includes the slow half asks the FILE. The
# question is asked of the file, so the fixture has to ask it of the file too —
# otherwise the gate has nothing to check and passes by silence.
POPULATION_WORKFLOW = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: select
        run: |
          case "$EVENT_NAME" in
            schedule|workflow_dispatch) echo "TEST_POPULATION=full" ;;
            *)                       echo "TEST_POPULATION=fast" ;;
          esac >> "$GITHUB_ENV"
      - name: slow tests ran
        run: |
          INCLUDES_SLOW=$(grep -F -m1 "${TEST_POPULATION}.includes-slow:" \\
            config/test-populations.properties | cut -d ':' -f 2- | tr -d '[:space:]' || echo "")
          if [ -z "$INCLUDES_SLOW" ]; then
            echo "::error::the population '$TEST_POPULATION' does not say whether it includes the slow half"
            exit 1
          fi
          if [ "$INCLUDES_SLOW" != "true" ]; then
            echo "no slow half in this population"
            exit 0
          fi
'''

# The populations file with every property the workflow asks about.
PROPERTIES_COMPLETE = '''
fast=fast
full=fast,slow

fast.includes-slow: false
full.includes-slow: true
'''

# `full` does not say whether it includes the slow half — the question the
# workflow asks of every population it can select.
PROPERTIES_MISSING = '''
fast=fast
full=fast,slow

fast.includes-slow: false
'''

# The property written with `=` — the separator the Python reader of the
# populations splits on, so the reader would see a population named after the
# property.
PROPERTIES_WRONG_SEPARATOR = '''
fast=fast
full=fast,slow

fast.includes-slow=false
full.includes-slow=true
'''

# `full` selects the slow classes and says it does not: the build asks the same
# question of the tags, so the two answers have to agree.
PROPERTIES_DISAGREE = '''
fast=fast
full=fast,slow

fast.includes-slow: false
full.includes-slow: false
'''

# The workflow asks the question by NAME — the defect the file removes.
NAME_COMPARISON_WORKFLOW = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: select
        run: |
          echo "TEST_POPULATION=full" >> "$GITHUB_ENV"
      - name: slow tests ran
        run: |
          if [ "$TEST_POPULATION" != "full" ]; then
            echo "no slow half in this population"
            exit 0
          fi
'''

# A population the workflow can select and the file does not declare.
UNDECLARED_POPULATION_WORKFLOW = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: select
        run: |
          echo "TEST_POPULATION=nightly" >> "$GITHUB_ENV"
      - name: slow tests ran
        run: |
          INCLUDES_SLOW=$(grep -F -m1 "${TEST_POPULATION}.includes-slow:" \\
            config/test-populations.properties | cut -d ':' -f 2- | tr -d '[:space:]' || echo "")
          echo "$INCLUDES_SLOW"
'''


def run_gate(files: dict[str, str]) -> tuple[int, str]:
    """Materialise a fake repo — script included — and run the gate against it."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        script_dest = root / 'scripts' / 'check-gradle-test-population.py'
        script_dest.parent.mkdir(parents=True, exist_ok=True)
        script_dest.write_text(SCRIPT.read_text())
        script_dest.chmod(0o755)
        parser_dest = root / 'scripts' / 'src' / 'workflow_gradle.py'
        parser_dest.parent.mkdir(parents=True, exist_ok=True)
        parser_dest.write_text(PARSER.read_text())
        # The populations file is the definition the gate reads: a fake repo
        # without it cannot be judged at all. A fixture may replace it by
        # putting its own content under this path.
        populations_dest = root / 'config' / 'test-populations.properties'
        populations_dest.parent.mkdir(parents=True, exist_ok=True)
        populations_dest.write_text(POPULATIONS.read_text())
        for rel, content in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        proc = subprocess.run(
            [sys.executable, str(script_dest), '--verbose'],
            capture_output=True, text=True, cwd=root, env={'PATH': '/usr/bin:/bin'},
        )
        return proc.returncode, proc.stdout + proc.stderr


class ParityIsTheRule(unittest.TestCase):
    def test_disagreeing_populations_fail(self):
        code, out = run_gate({'.github/workflows/ci.yml': DISAGREEING})
        self.assertEqual(code, 1, out)
        self.assertIn('disagree about the population', out)

    def test_agreeing_populations_pass(self):
        code, out = run_gate({'.github/workflows/ci.yml': AGREEING})
        self.assertEqual(code, 0, out)

    def test_one_invocation_without_a_selector_is_not_a_violation(self):
        code, out = run_gate({'.github/workflows/ci.yml': SINGLE_DEFAULT})
        self.assertEqual(code, 0, out)

    def test_compile_only_step_after_a_test_run_is_not_a_second_population(self):
        code, out = run_gate({'.github/workflows/ci.yml': COMPILE_AFTER_TEST})
        self.assertEqual(code, 0, out)

    def test_a_clean_job_does_not_implicate_a_different_job(self):
        code, out = run_gate({'.github/workflows/ci.yml': TWO_JOBS_ONE_CLEAN})
        self.assertEqual(code, 0, out)

    def test_space_separated_selector_binds(self):
        code, out = run_gate({'.github/workflows/ci.yml': SPACE_SELECTOR})
        self.assertEqual(code, 0, out)

    def test_commented_out_invocation_is_not_an_invocation(self):
        code, out = run_gate({'.github/workflows/ci.yml': COMMENTED_OUT})
        self.assertEqual(code, 0, out)
        self.assertNotIn('disagree', out)

    def test_compile_tasks_are_not_test_tasks(self):
        code, out = run_gate({'.github/workflows/ci.yml': COMPILE_TOKENS})
        self.assertEqual(code, 0, out)
        listing = [line for line in out.splitlines() if line.startswith('  ')]
        self.assertEqual(len(listing), 1, out)
        self.assertIn('compile-only', listing[0], out)


class TheGateStillSees(unittest.TestCase):
    """An empty finding list is what a broken parser looks like. Pin the detection."""

    def test_every_test_invocation_is_recognised(self):
        # The listing lines are what "the gate saw it" means; the violation text also
        # contains the word, so count the leading-space lines only.
        _, out = run_gate({'.github/workflows/ci.yml': AGREEING})
        listing = [line for line in out.splitlines() if line.startswith('  ')]
        self.assertEqual(len(listing), 3, out)
        self.assertTrue(all('test-capable' in line for line in listing), out)

    def test_a_kover_task_is_test_capable(self):
        # The finding itself was a Kover invocation: Kover reads the binary reports the
        # test tasks produce, so `:koverMarkdownTable` runs them when they are stale.
        _, out = run_gate({'.github/workflows/ci.yml': DISAGREEING})
        self.assertIn('test-capable', out)
        self.assertIn('koverMarkdownTable', out)

    def test_check_sh_is_a_surface_and_reports_its_default(self):
        _, out = run_gate({'check.sh': './gw :shared:jvmTest\n./gw :desktopApp:test\n'})
        self.assertIn('check.sh', out)
        self.assertIn('note:', out)


class ThePopulationsFileAnswersTheWorkflow(unittest.TestCase):
    """The file describes the populations, so the shell does not have to.

    Every case here is a positive control in one direction or the other: the
    fixture that lacks what the workflow asks for must FAIL, and the fixture
    that has it must PASS. A gate that passes both is a gate that reads
    nothing, which is the failure mode this repository measures itself
    against.
    """

    def test_a_population_that_declares_the_property_is_clean(self):
        code, out = run_gate({
            '.github/workflows/ci.yml': POPULATION_WORKFLOW,
            'config/test-populations.properties': PROPERTIES_COMPLETE,
        })
        self.assertEqual(code, 0, out)

    def test_a_population_that_lacks_the_property_is_a_finding(self):
        code, out = run_gate({
            '.github/workflows/ci.yml': POPULATION_WORKFLOW,
            'config/test-populations.properties': PROPERTIES_MISSING,
        })
        self.assertEqual(code, 1, out)
        self.assertIn('does not answer', out)
        self.assertIn('includes-slow', out)
        self.assertIn('full', out)

    def test_the_gate_says_which_property_it_asked_about(self):
        # The listing is what "the gate saw it" means: the property the workflow
        # asks of the populations it can select.
        _, out = run_gate({
            '.github/workflows/ci.yml': POPULATION_WORKFLOW,
            'config/test-populations.properties': PROPERTIES_COMPLETE,
        })
        self.assertIn('includes-slow', out)
        self.assertIn('fast', out)
        self.assertIn('full', out)

    def test_a_property_that_contradicts_the_tags_is_a_finding(self):
        code, out = run_gate({
            '.github/workflows/ci.yml': POPULATION_WORKFLOW,
            'config/test-populations.properties': PROPERTIES_DISAGREE,
        })
        self.assertEqual(code, 1, out)
        self.assertIn('say otherwise', out)

    def test_a_property_written_with_the_wrong_separator_is_a_finding(self):
        code, out = run_gate({
            '.github/workflows/ci.yml': POPULATION_WORKFLOW,
            'config/test-populations.properties': PROPERTIES_WRONG_SEPARATOR,
        })
        self.assertEqual(code, 1, out)
        self.assertIn("written with '='", out)

    def test_a_workflow_that_asks_by_name_is_a_finding(self):
        code, out = run_gate({
            '.github/workflows/ci.yml': NAME_COMPARISON_WORKFLOW,
            'config/test-populations.properties': PROPERTIES_COMPLETE,
        })
        self.assertEqual(code, 1, out)
        self.assertIn('by NAME', out)

    def test_a_population_the_file_does_not_declare_is_a_finding(self):
        code, out = run_gate({
            '.github/workflows/ci.yml': UNDECLARED_POPULATION_WORKFLOW,
            'config/test-populations.properties': PROPERTIES_COMPLETE,
        })
        self.assertEqual(code, 1, out)
        self.assertIn('nightly', out)
        self.assertIn('does not declare it', out)

    def test_a_workflow_that_selects_nothing_asks_nothing(self):
        # No population, no question, no finding: the check is about what the
        # workflow asks, not about every workflow.
        code, out = run_gate({
            '.github/workflows/ci.yml': AGREEING,
            'config/test-populations.properties': PROPERTIES_MISSING,
        })
        self.assertEqual(code, 0, out)


if __name__ == '__main__':
    unittest.main()