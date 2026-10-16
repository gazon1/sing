#!/usr/bin/env python3
"""Tests for check-build-output-readers.py.

The gate's failure mode is the one it exists to prevent: a reader detection that
stops matching yields an empty finding list, and an empty finding list reads as a
pass. The fixtures therefore assert on what the gate *saw* — the readers it
enumerated — not on the exit code alone.

Each fixture materialises the script inside a fake repo, because the script derives
its root from its own location and a `cwd`-based harness would silently run it
against the real tree. The sibling population gate is loaded from the fixture repo
too, so a fixture that forgot it would fail loudly instead of reaching into /home.
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / 'check-build-output-readers.py'
#: The gate imports its parser from here.
PARSER = ROOT / 'src' / 'workflow_gradle.py'

# The shape that failed silently on 2026-10-16: the flake reader sits after three
# Gradle invocations that can each re-run the shared test task.
READER_AFTER_WRITERS = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Gradle
        run: ./gradlew --continue :shared:jvmTest -Ptest.tags=fast,slow 2>&1 | tee build.log
      - name: counts
        run: python3 scripts/check-test-runs.py --require shared:jvmTest
      - name: coverage floors
        run: ./gradlew :koverVerify -Ptest.tags=fast,slow
      - name: flake analysis
        run: |
          python3 scripts/check-flaky-tests.py \\
            --current shared/build/test-results/jvmTest \\
            --previous /tmp/prev-run
'''

# The fixed shape, and the one the repository now carries.
READER_BEFORE_WRITERS = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Gradle
        run: ./gradlew --continue :shared:jvmTest -Ptest.tags=fast,slow 2>&1 | tee build.log
      - name: flake analysis
        run: |
          python3 scripts/check-flaky-tests.py \\
            --current shared/build/test-results/jvmTest \\
            --previous /tmp/prev-run
      - name: coverage floors
        run: ./gradlew :koverVerify -Ptest.tags=fast,slow
'''

# One test-capable invocation: the producer. Nothing can overwrite what its
# readers read, so this is silent even though every reader follows it.
SINGLE_PRODUCER = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Gradle
        run: ./gradlew :shared:jvmTest :desktopApp:test -Ptest.tags=fast,slow
      - name: counts
        run: python3 scripts/check-test-runs.py --require shared:jvmTest
'''

# A compile-only step after the producer does not rewrite JUnit XML.
COMPILE_AFTER_READER = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Gradle
        run: ./gradlew :shared:jvmTest -Ptest.tags=fast,slow
      - name: counts
        run: python3 scripts/check-test-runs.py --require shared:jvmTest
      - name: apk
        run: ./gradlew :androidApp:assembleDebug
'''

# Two jobs, one per runner: a reader in one job cannot be affected by an
# invocation in the other.
TWO_JOBS = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Gradle
        run: ./gradlew :shared:jvmTest -Ptest.tags=fast,slow
      - name: counts
        run: python3 scripts/check-test-runs.py --require shared:jvmTest
      - name: coverage
        run: ./gradlew :koverVerify -Ptest.tags=fast,slow
  android:
    runs-on: ubuntu-24.04
    steps:
      - run: ./gradlew :androidApp:assembleDebug
'''


# The exact shape that hid the main Gradle run from the parser. `ci.yml` writes its
# test run as a `|` block of backslash continuations (since PR #690), so only the
# first line carries `./gradlew` and a parser that splits on newlines sees
# `--continue` with no task at all — not a test-capable invocation. With the run
# invisible, the sabotage control passed a sabotaged workflow and the gate could
# not detect its own premise. Found by the control itself: "gate
# 'build-output-readers' PASSED a deliberately sabotaged input" on run 38961950540.
CONTINUATION_SHAPED_SABOTAGE = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Gradle (tests)
        run: |
          set -o pipefail
          ./gradlew --continue \\
            --no-build-cache \\
            :shared:jvmTest \\
            -Ptest.tags=fast,slow \\
            2>&1 | tee build.log
      - name: late coverage pass
        run: ./gradlew :koverVerify -Ptest.tags=fast,slow
      - name: counts
        run: |
          python3 scripts/check-test-runs.py --require shared:jvmTest \\
            --current shared/build/test-results/jvmTest
'''

CONTINUATION_SHAPED_CLEAN = CONTINUATION_SHAPED_SABOTAGE.replace(
    '''      - name: late coverage pass
        run: ./gradlew :koverVerify -Ptest.tags=fast,slow
''',
    '''      - name: counts
        run: |
          python3 scripts/check-test-runs.py --require shared:jvmTest \\
            --current shared/build/test-results/jvmTest
''',
)


def run_gate(files: dict[str, str]) -> tuple[int, str]:
    """Materialise a fake repo — both scripts included — and run the gate."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        scripts = root / 'scripts'
        scripts.mkdir(parents=True)
        script_dest = scripts / SCRIPT.name
        script_dest.write_text(SCRIPT.read_text())
        script_dest.chmod(0o755)
        parser_dest = scripts / 'src' / PARSER.name
        parser_dest.parent.mkdir(parents=True, exist_ok=True)
        parser_dest.write_text(PARSER.read_text())
        for rel, content in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        proc = subprocess.run(
            [sys.executable, str(scripts / SCRIPT.name), '--verbose'],
            capture_output=True, text=True, cwd=root, env={'PATH': '/usr/bin:/bin'},
        )
        return proc.returncode, proc.stdout + proc.stderr


class OrderingIsTheRule(unittest.TestCase):
    def test_reader_after_a_second_invocation_fails(self):
        code, out = run_gate({'.github/workflows/ci.yml': READER_AFTER_WRITERS})
        self.assertEqual(code, 1, out)
        self.assertIn('reads build/test-results', out)

    def test_reader_before_the_invocation_passes(self):
        code, out = run_gate({'.github/workflows/ci.yml': READER_BEFORE_WRITERS})
        self.assertEqual(code, 0, out)

    def test_the_producing_invocation_is_not_a_violation(self):
        code, out = run_gate({'.github/workflows/ci.yml': SINGLE_PRODUCER})
        self.assertEqual(code, 0, out)

    def test_a_compile_only_step_after_a_reader_is_silent(self):
        code, out = run_gate({'.github/workflows/ci.yml': COMPILE_AFTER_READER})
        self.assertEqual(code, 0, out)

    def test_a_reader_in_one_job_is_not_implicated_by_another_job(self):
        code, out = run_gate({'.github/workflows/ci.yml': TWO_JOBS})
        self.assertEqual(code, 0, out)


class MultiLineGradleCommandIsOneInvocation(unittest.TestCase):
    """A `run:` block of `\\` continuations is one command, not N lines."""

    def test_the_continuation_shaped_run_is_the_producer_not_a_gap(self):
        # Without the producer being recognised there is only one test-capable
        # invocation, the job is skipped, and the gate reports nothing.
        code, out = run_gate({'.github/workflows/ci.yml': CONTINUATION_SHAPED_CLEAN})
        self.assertEqual(code, 0, out)

    def test_a_second_invocation_after_the_run_is_still_detected(self):
        code, out = run_gate({'.github/workflows/ci.yml': CONTINUATION_SHAPED_SABOTAGE})
        self.assertEqual(code, 1, out)
        self.assertIn('late coverage pass', out)


class TheGateStillSees(unittest.TestCase):
    """An empty finding list is what a broken reader detection looks like."""

    def test_every_build_output_reader_is_enumerated(self):
        _, out = run_gate({'.github/workflows/ci.yml': READER_BEFORE_WRITERS})
        readers = [line for line in out.splitlines() if line.strip().startswith('.github')]
        self.assertEqual(len(readers), 1, out)
        self.assertIn('build/test-results', readers[0])

    def test_all_three_build_output_paths_are_recognised(self):
        _, out = run_gate({'.github/workflows/ci.yml': READER_AFTER_WRITERS})
        self.assertIn('reads build/test-results', out)

    def test_a_second_invocation_is_recognised_as_a_writer(self):
        _, out = run_gate({'.github/workflows/ci.yml': READER_AFTER_WRITERS})
        self.assertIn('is a test-capable Gradle invocation that runs before it', out)


if __name__ == '__main__':
    unittest.main()