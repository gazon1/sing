#!/usr/bin/env python3
"""
scripts/junit-annotations.py

Reads JUnit XML files from desktop test results, parses them with junitparser,
and emits GitHub Actions ::error and ::notice workflow commands — one annotation
per failing or retry-passed test case.

Exit code: 0 always (annotation failures are informational and must not crash CI).

Usage:
    python scripts/junit-annotations.py desktopApp/build/test-results/test/
    python scripts/junit-annotations.py --glob "**/test-results/test/*.xml"

Dependencies:
    pip install junitparser

Annotation format:
    ::error file={path}::title={Class.method}::{failure_message}
    ::notice title=Passed on retry::{N} test(s) passed on retry (class: ClassName)
"""

import argparse
import glob as glob_module
import sys
from pathlib import Path

try:
    import junitparser
except ImportError:
    print("::warning ::junitparser not installed — skipping annotation generation")
    print("::warning ::Install with: pip install junitparser")
    sys.exit(0)


def annotation_title(classname: str, methodname: str, max_len: int = 64) -> str:
    """Build a GitHub-annotation title from class + method, truncated to max_len."""
    raw = f"{classname}.{methodname}"
    if len(raw) <= max_len:
        return raw
    # Truncate from the front (keep method name which is more specific)
    return raw[len(raw) - max_len:]


def emit_error(classname: str, methodname: str, message: str, file_path: str = "."):
    """Emit a ::error annotation for a persistent (non-flaky) failure."""
    title = annotation_title(classname, methodname)
    # Truncate message to avoid extremely long annotations
    truncated_msg = message[:500] + ("…" if len(message) > 500 else "")
    print(f"::error file={file_path},line=1::title={title}::{truncated_msg}", flush=True)


def emit_notice(message: str):
    """Emit a ::notice annotation for a test that passed on retry (flaky pass)."""
    print(f"::notice title=Passed on retry::{message}", flush=True)


def parse_flaky_failure(testcase) -> tuple[bool, str, str]:
    """
    Check if a testcase has a <flakyFailure> child element.
    Returns (is_flaky, failure_message, failure_type).
    junitparser maps flakyFailure to a Failure-like element with tag 'flakyFailure'.
    """
    # Access the underlying XML element directly
    elem = testcase._elem  # lxml element
    flaky_nodes = elem.findall("flakyFailure")
    if not flaky_nodes:
        return False, "", ""
    flaky_node = flaky_nodes[0]
    message = flaky_node.get("message", "test passed on retry")
    failure_type = flaky_node.get("type", "flaky")
    return True, message, failure_type


def process_xml_file(xml_path: Path) -> tuple[int, int, int]:
    """
    Process one JUnit XML file. Returns (passed, failures, flaky_passes).
    Prints annotations for each failure and flaky pass.
    """
    try:
        junit_xml = junitparser.JUnitXml.fromfile(str(xml_path))
    except Exception as e:
        print(f"::warning ::Failed to parse {xml_path}: {e}", flush=True)
        return 0, 0, 0

    failures = 0
    flaky_passes = 0
    passed = 0

    # JUnitXml iterates over TestSuite objects; TestSuite iterates over TestCase objects.
    for suite in junit_xml:
        for tc in suite:
            classname = tc.classname or "unknown"
            methodname = tc.name or "unknown"

            # Check for flakyFailure (retry-passed) via direct XML element access.
            # tc.result is [] for flakyFailure — junitparser does not map it to Failure.
            is_flaky, flaky_msg, flaky_type = parse_flaky_failure(tc)
            if is_flaky:
                flaky_passes += 1
                emit_notice(
                    f"Test '{classname}.{methodname}' passed on retry "
                    f"(original failure: {flaky_msg or 'recorded in <flakyFailure>'})"
                )
                continue

            # Check for regular failure
            has_failure = False
            failure_msg = ""
            for result in tc.result:
                if isinstance(result, junitparser.Failure):
                    has_failure = True
                    failure_msg = result.message or str(result)
                    break

            if has_failure:
                failures += 1
                emit_error(classname, methodname, failure_msg)
            else:
                passed += 1

    return passed, failures, flaky_passes


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
        xml_files = list(base.glob(args.glob_pattern))
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

    for xml_path in sorted(xml_files):
        p, f, fl = process_xml_file(xml_path)
        total_passed += p
        total_failures += f
        total_flaky += fl

    print(
        f"Annotations: {total_failures} failure(s), {total_flaky} retry-pass(es), {total_passed} clean pass(es)",
        flush=True,
    )

    sys.exit(0)


if __name__ == "__main__":
    main()
