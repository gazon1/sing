#!/usr/bin/env python3
"""
Check that the AppTracer tracer {} block encodes the correct upload policy:
  - release variant: isDisabled = !tokensPresent || !isCi
  - debug variant: isDisabled = !tokensPresent
  - assertTracerBuildUuid task exists

Also verifies that the policy ADR exists at:
  docs/decisions/2026-10-10-apptracer-upload-policy.md
"""
from __future__ import annotations

import sys
import re
from pathlib import Path

ROOT = Path(__file__).parent.parent
PRO_BUILD = ROOT / "pro" / "build.gradle.kts"
ADR_PATH = ROOT / "docs" / "decisions" / "2026-10-10-apptracer-upload-policy.md"


def main() -> int:
    errors: list[str] = []

    # --- ADR exists ---
    if not ADR_PATH.exists():
        errors.append(f"ADR not found: {ADR_PATH}")
    else:
        adr_content = ADR_PATH.read_text()
        if "isCi" not in adr_content:
            errors.append("ADR does not document the isCi policy")

    # --- pro/build.gradle.kts exists ---
    if not PRO_BUILD.exists():
        errors.append(f"pro/build.gradle.kts not found at {PRO_BUILD}")
        for e in errors:
            print(f"FAIL: {e}")
        return 1

    content = PRO_BUILD.read_text()

    # --- assertTracerBuildUuid task ---
    if 'tasks.register<Exec>("assertTracerBuildUuid")' not in content:
        errors.append(
            "assertTracerBuildUuid task not found in pro/build.gradle.kts. "
            "See #383: buildUuid null check must be a Gradle task."
        )

    # --- isCi variable defined ---
    if not re.search(r"val isCi\s*=\s*providers\.environmentVariable\(\"CI\"\)", content):
        errors.append(
            "isCi variable not defined. "
            "Expected: val isCi = providers.environmentVariable(\"CI\")..."
        )

    # --- tokensPresent variable defined ---
    if not re.search(r"val tokensPresent\s*=\s*tracerAppToken\.getOrElse", content):
        errors.append(
            "tokensPresent variable not defined. "
            "Expected: val tokensPresent = tracerAppToken.getOrElse..."
        )

    # --- release block has !isCi condition ---
    release_block = re.search(
        r'create\("release"\)\s*\{([^}]+)\}',
        content,
        re.DOTALL,
    )
    if not release_block:
        errors.append(
            'create("release") block not found in tracer {} block'
        )
    else:
        release_body = release_block.group(1)
        if "!isCi" not in release_body:
            errors.append(
                'create("release") block does not contain !isCi. '
                "Release uploads must be gated on CI environment variable."
            )
        if "isDisabled" not in release_body:
            errors.append(
                'create("release") block does not set isDisabled'
            )

    # --- debug block exists and disables when no tokens ---
    debug_block = re.search(
        r'create\("debug"\)\s*\{([^}]+)\}',
        content,
        re.DOTALL,
    )
    if not debug_block:
        errors.append('create("debug") block not found in tracer {} block')
    else:
        debug_body = debug_block.group(1)
        if "isDisabled" not in debug_body:
            errors.append('create("debug") block does not set isDisabled')

    for e in errors:
        print(f"FAIL: {e}")

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
