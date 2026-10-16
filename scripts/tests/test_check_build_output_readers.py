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
SIBLING = ROOT / 'check-gradle-test-population.py'

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


def run_gate(files: dict[str, str]) -> tuple[int, str]:
    """Materialise a fake repo — both scripts included — and run the gate."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        scripts = root / 'scripts'
        scripts.mkdir(parents=True)
        for name, source in ((SCRIPT.name, SCRIPT), (SIBLING.name, SIBLING)):
            dest = scripts / name
            dest.write_text(source.read_text())
            dest.chmod(0o755)
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


class ContinuedLinesAreOneCommand(unittest.TestCase):
    """A backslash-continued block is one command, and reading it as several hid the
    exact violation this gate's docstring names as its reason to exist.

    `ci.yml` writes its flake reader that way — the path it reads sits on a
    continuation line — and its producing Gradle invocation spans fourteen lines with
    its tasks on them. Under one-physical-line-at-a-time parsing neither half was
    visible: `./gradlew --continue` looked task-less and therefore harmless, and the
    reader looked path-less and therefore absent. Both mistakes cancelled out into a
    pass.
    """

    # Every command below is multi-line, and the three properties the rule turns on —
    # tasks on the invocation, a build-output path on the reader, their relative
    # order — live exclusively on continuation lines.
    _PRELUDE = """\
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Gradle
        run: |
          ./gradlew --continue \\
            --no-build-cache \\
            :shared:jvmTest :desktopApp:test \\
            -Ptest.tags=fast,slow
"""

    _FLAKE_READER = """\
      - name: flake analysis
        run: |
          python3 scripts/check-flaky-tests.py \\
            --current shared/build/test-results/jvmTest \\
            --previous /tmp/prev-run
"""

    _KOVER_WRITER = """\
      - name: coverage floors
        run: |
          ./gradlew :koverVerify \\
            -Ptest.tags=fast,slow
"""

    #: The shape `ci.yml` is required to keep: read first, then re-run.
    READER_THEN_WRITER = _PRELUDE + _FLAKE_READER + _KOVER_WRITER

    #: The shape this gate exists to reject: a second test-capable invocation lands
    #: between the results and the step that consumes them.
    WRITER_THEN_READER = _PRELUDE + _KOVER_WRITER + _FLAKE_READER

    def test_a_reader_after_a_continued_writer_fails(self):
        code, out = run_gate({'.github/workflows/ci.yml': self.WRITER_THEN_READER})
        self.assertEqual(code, 1, out)
        self.assertIn('reads build/test-results', out)

    def test_a_reader_before_a_continued_writer_passes(self):
        code, out = run_gate({'.github/workflows/ci.yml': self.READER_THEN_WRITER})
        self.assertEqual(code, 0, out)

    def test_the_continued_producer_is_still_seen_as_the_producer(self):
        # If the continued producer were invisible, the *only* test-capable
        # invocation would be :koverVerify, and the fixture above would pass for the
        # wrong reason. Assert the producer is enumerated with its tasks.
        _, out = run_gate({'.github/workflows/ci.yml': self.WRITER_THEN_READER})
        violations = [line for line in out.splitlines() if 'test-capable Gradle invocation' in line]
        self.assertEqual(len(violations), 1, out)
        self.assertIn(':koverVerify', violations[0])


if __name__ == '__main__':
    unittest.main()
