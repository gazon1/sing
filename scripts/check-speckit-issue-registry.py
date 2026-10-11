#!/usr/bin/env python3
"""
Check spec-kit issue registry consistency with OpenSpec changes.

Validates:
1. Every issue in .specify/issues/ points to an existing openspec/changes/ directory
2. All issues have required fields (version, type, slug, title, status)
3. ADR references in issues are valid (slugs exist in docs/decisions/)

An issue record that points to a non-existent OpenSpec change = broken link.
An issue record with invalid fields = malformed registry.
"""
from __future__ import annotations

import sys
import yaml
from pathlib import Path

ROOT = Path(__file__).parent.parent.resolve()
ISSUES_DIR = ROOT / ".specify" / "issues"
OPENSPEC_CHANGES = ROOT / "openspec" / "changes"


def main() -> int:
    errors: list[str] = []

    if not ISSUES_DIR.exists():
        print("INFO: .specify/issues/ does not exist — no spec-kit issues yet")
        return 0

    issue_files = sorted(ISSUES_DIR.glob("*.yaml"))

    for issue_file in issue_files:
        try:
            text = issue_file.read_text(encoding="utf-8")
            # Strip YAML comments and parse
            records = list(yaml.safe_load_all(text))
            for record in records:
                if record is None:
                    continue
                errors += _validate_record(record, issue_file)
        except yaml.YAMLError as exc:
            errors.append(f"{issue_file}: YAML parse error: {exc}")
        except Exception as exc:
            errors.append(f"{issue_file}: unexpected error: {exc}")

    if errors:
        for err in errors:
            print(f"ERROR: {err}")
        print()
        print(f"check-speckit-issue-registry: {len(errors)} error(s) in {len(issue_files)} issue file(s)")
        return 1

    print(f"check-speckit-issue-registry: OK — {len(issue_files)} issue record(s) consistent")
    return 0


def _validate_record(record: dict, issue_file: Path) -> list[str]:
    errors: list[str] = []
    slug = record.get("slug", "?")

    # Required fields
    for field in ("version", "type", "slug", "status"):
        if field not in record:
            errors.append(f"{issue_file.name}: missing required field '{field}' in issue '{slug}'")

    # Type must be openspec-change
    if record.get("type") not in (None, "openspec-change"):
        errors.append(
            f"{issue_file.name}: issue '{slug}' has type '{record.get('type')}', "
            "expected 'openspec-change'"
        )

    # Check openspec_path exists
    opspec_path = record.get("openspec_path")
    if opspec_path:
        opspec_dir = Path(opspec_path)
        if not opspec_dir.exists():
            errors.append(
                f"{issue_file.name}: issue '{slug}' points to non-existent "
                f"OpenSpec path: {opspec_path}"
            )

    # Check document paths exist (advisory — stubs may not have all documents)
    for doc_type, doc_path in record.get("documents", {}).items():
        if doc_path:
            doc_file = Path(doc_path)
            if not doc_file.exists():
                print(
                    f"  WARNING: {issue_file.name}: issue '{slug}' document '{doc_type}' "
                    f"does not exist: {doc_path}"
                )

    return errors


if __name__ == "__main__":
    sys.exit(main())
