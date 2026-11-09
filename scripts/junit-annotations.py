#!/usr/bin/env python3
"""
scripts/junit-annotations.py

Reads JUnit XML files and emits GitHub Actions ::error and ::notice workflow
commands — one annotation per failing or retry-passed test case.

The XML is parsed by ``scripts/src/traceability/junit_xml.py`` — the
repository's one JUnit reader. This script used to parse with ``junitparser``
on its own, and the two readers disagreed in the place that mattered:

* ``junitparser.Error`` is not a subclass of ``junitparser.Failure``, so a
  testcase whose only outcome was ``<error>`` (an OOM, a timeout, a crashed
  fixture) was counted as a **clean pass** and never annotated;
* the reader ``continue``d on the first ``<flakyFailure>`` child, so a
  retried test that *also* failed terminally was reported as "passed on retry"
  and its real failure was dropped.

Both defects are now impossible here: the outcome comes from the shared
reader, which classifies ``failure > error > skipped > passed`` and treats
``<flakyFailure>`` as retry history rather than as the outcome.

Exit code: 0 always. Annotations are advisory — the build result comes from
Gradle, and a reporter that crashes must not crash CI with it.

Usage:
    python scripts/junit-annotations.py desktopApp/build/test-results/test/
    python scripts/junit-annotations.py --glob "**/build/test-results/**/*.xml" .

Annotation format:
    ::error file={path},line=1::title={Class.method}::{failure_message}
    ::notice title=Passed on retry::{N} test(s) passed on retry (class: ClassName)
"""

import argparse
import sys
from pathlib import Path

# `scripts/src` on sys.path, and it has to be there *before* the first import
# that uses it: this script must read the reader from *this* checkout, not from
# whatever happens to be installed. When this file is loaded by a test harness
# via importlib, sys.path[0] is `scripts/tests` and the package is invisible;
# this file's own location is authoritative.
_scripts_src = str(Path(__file__).resolve().parent / "src")
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)

from traceability.junit_xml import parse_junit_file_strict  # noqa: E402

if _added:
    sys.path.remove(_scripts_src)

#: GitHub truncates very long annotations anyway, and an annotation is a
#: pointer to the report, not the report. The full text stays in the XML.
ANNOTATION_MESSAGE_LIMIT = 500


def annotation_title(classname: str, methodname: str, max_len: int = 64) -> str:
    """Build a GitHub-annotation title from class + method, truncated to max_len."""
    raw = f"{classname}.{methodname}"
    if len(raw) <= max_len:
        return raw
    # Truncate from the front (keep method name which is more specific)
    return raw[len(raw) - max_len:]


def emit_error(classname: str, methodname: str, message: str, file_path: str = "."):
    """Emit a ::error annotation for a failing test (failure *or* error)."""
    title = annotation_title(classname, methodname)
    truncated_msg = message[:ANNOTATION_MESSAGE_LIMIT] + (
        "…" if len(message) > ANNOTATION_MESSAGE_LIMIT else ""
    )
    print(f"::error file={file_path},line=1::title={title}::{truncated_msg}", flush=True)


def emit_notice(message: str):
    """Emit a ::notice annotation for a test that passed on retry (flaky pass)."""
    print(f"::notice title=Passed on retry::{message}", flush=True)


def process_xml_file(xml_path: Path) -> tuple[int, int, int, int]:
    """
    Process one JUnit XML file. Returns (clean_passed, failures, retry_passes, skipped).

    Prints an annotation for each failure and each retry-pass. A testcase that
    failed terminally is a failure even when it also carries ``<flakyFailure>``
    children — those record earlier attempts, not the outcome.

    A skipped test is counted as skipped, not as a clean pass. It did not run,
    and a summary line that counts it as a pass is a summary line that claims
    more than the run did.
    """
    try:
        results = parse_junit_file_strict(xml_path)
    except Exception as exc:  # noqa: BLE001 — a malformed report must not crash CI
        print(f"::warning ::Failed to parse {xml_path}: {exc}", flush=True)
        return 0, 0, 0, 0

    failures = 0
    retry_passes = 0
    passed = 0
    skipped = 0

    for case in results:
        classname = case.classname or "unknown"
        methodname = case.name or "unknown"

        if case.failed_like:
            failures += 1
            detail = case.message or case.stacktrace or f"test {case.status}"
            emit_error(classname, methodname, detail, case.file or ".")
            continue

        if case.retried:
            # The test ended green, but only after failing earlier attempts.
            # A notice, not an error — and never counted as a clean pass.
            retry_passes += 1
            emit_notice(
                f"Test '{classname}.{methodname}' passed on retry "
                f"({case.flaky_attempts} earlier attempt(s) failed)"
            )
            continue

        if case.status == "skipped":
            skipped += 1
            continue

        passed += 1

    return passed, failures, retry_passes, skipped


def main():
    parser = argparse.ArgumentParser(description="Emit GitHub annotations from JUnit XML")
    parser.add_argument(
        "path",
        nargs="?",
        default="desktopApp/build/test-results/test/",
        help="Path to JUnit XML file or directory containing XML files",
    )
    parser.add_argument(
        "--glob",
        dest="glob_pattern",
        default="**/*.xml",
        help="Glob pattern relative to path (default: **/*.xml)",
    )
    args = parser.parse_args()

    base = Path(args.path)
    if base.is_file():
        xml_files = [base]
    elif base.is_dir():
        xml_files = sorted(base.glob(args.glob_pattern))
    else:
        print(
            f"::warning ::Path does not exist: {base} — skipping annotation generation",
            flush=True,
        )
        sys.exit(0)

    if not xml_files:
        print(
            f"::warning ::No JUnit XML files found at {base} — skipping annotation generation",
            flush=True,
        )
        sys.exit(0)

    total_passed = 0
    total_failures = 0
    total_flaky = 0
    total_skipped = 0

    for xml_path in xml_files:
        p, f, fl, sk = process_xml_file(xml_path)
        total_passed += p
        total_failures += f
        total_flaky += fl
        total_skipped += sk

    print(
        f"Annotations: {total_failures} failure(s), {total_flaky} retry-pass(es), "
        f"{total_passed} clean pass(es), {total_skipped} skipped",
        flush=True,
    )

    sys.exit(0)


if __name__ == "__main__":
    main()
