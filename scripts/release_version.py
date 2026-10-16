#!/usr/bin/env python3
"""release_version — the version logic of `.github/workflows/release.yml`.

## Why this file exists

Six defects had to be fixed before `release.yml` could complete a release. Every one
of them was found by *running* the pipeline, because the logic lived in shell inside
a workflow file where nothing can unit-test it: the repository's own doctrine is that
orchestration belongs in shell and decisions belong in Python, and this was a decision.

The sharpest example is the packaging version. `nativeDistributions` carries one
`packageVersion` for both Deb and Rpm and validates it against every declared format
while *configuring* the project — so rpm rejecting a dash also fails the job that only
builds the deb. Getting that wrong twice cost two failed tag runs, each of which is
about ten minutes of CI.

So: the arithmetic that decides what a release is called and what its packages may be
named lives here, is covered by `scripts/tests/test_release_version.py`, and the
workflow asks this module rather than doing it in bash. The shell left in
`release.yml` now runs something; it no longer decides anything.

## What it does not do

It does not build, sign, verify or upload anything. Those stay in the workflow and in
Gradle. This module only answers questions about version *strings*.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass

#: `X.Y.Z` with an optional `-suffix`. The suffix may not contain another dash, which
#: is what makes the single substitution in `package_version` below sufficient.
_VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$")

#: versionCode scheme, from the workflow it replaces: major*1000000 + minor*1000 + patch.
_CODE_UNIT_MINOR = 1_000
_CODE_UNIT_MAJOR = 1_000_000

#: The minor and patch components occupy the low three decimal digits of `versionCode`,
#: so anything larger silently overlaps the component above it.
_MAX_COMPONENT = _CODE_UNIT_MINOR - 1


class ReleaseVersionError(ValueError):
    """A version string the release pipeline cannot use."""


@dataclass(frozen=True)
class ReleaseVersion:
    """A parsed, validated release version."""

    version: str
    """`X.Y.Z[-suffix]` with the optional leading `v` stripped."""

    version_code: int
    """Android `versionCode`; `major*1000000 + minor*1000 + patch`."""

    prerelease: bool
    """True when a `-suffix` is present."""

    @property
    def base(self) -> str:
        """`X.Y.Z`, without the prerelease suffix."""
        return self.version.split("-", 1)[0]

    @property
    def suffix(self) -> str:
        """The prerelease suffix, or `''` for a plain release."""
        _, _, suffix = self.version.partition("-")
        return suffix

    @property
    def package_version(self) -> str:
        """The version string the desktop packages must carry.

        Neither packaging format accepts everything the tag may contain, and
        `packageVersion` is one property validated against *both* of them while the
        project is configured:

        - rpm rejects a dash outright;
        - deb's `UPSTREAM_VERSION` accepts only alphanumerics and `.`, `+`, `-`, `~`,
          so an underscore is rejected too.

        No replacement character is acceptable to both, so the dash is dropped:
        `v0.2.1-rc.1` ships `0.2.1rc.1`. Measured, not assumed — see
        `docs/decisions/2026-10-16-desktop-package-version-must-satisfy-rpm-and-deb.md`.

        The tag and the package therefore legitimately spell the same release
        differently. The Android APK keeps the dashed form: Android imposes no such
        restriction.
        """
        return self.version.replace("-", "")


def parse(raw: str) -> ReleaseVersion:
    """Parse and validate a tag name or a manual version input.

    Accepts an optional leading `v`, so `v0.2.1-rc.1` and `0.2.1-rc.1` are the same
    version. Raises [ReleaseVersionError] with a message meant for a CI log.
    """
    version = raw[1:] if raw.startswith("v") else raw

    if not _VERSION_RE.match(version):
        raise ReleaseVersionError(f"{raw!r} is not X.Y.Z[-suffix]")

    base = version.split("-", 1)[0]
    # Leading zeros are stripped the way the shell did: "01" -> 1, "" -> 0. `int`
    # subsumes that, and refuses nothing the regex already allowed.
    major, minor, patch = (int(part) for part in base.split("."))

    if minor >= _MAX_COMPONENT + 1:
        raise ReleaseVersionError(f"minor={minor} must be < {_MAX_COMPONENT + 1}")
    if patch >= _MAX_COMPONENT + 1:
        raise ReleaseVersionError(f"patch={patch} must be < {_MAX_COMPONENT + 1}")

    return ReleaseVersion(
        version=version,
        version_code=major * _CODE_UNIT_MAJOR + minor * _CODE_UNIT_MINOR + patch,
        prerelease="-" in version,
    )


def rpm_embedded(version_field: str, release_field: str) -> str:
    """Reconstruct the version of a built RPM from its two metadata fields.

    `RELEASE` is jpackage's default packaging revision, not part of the version: an
    rpm built from `0.2.1` reports `0.2.1-1`. Appending it unconditionally made every
    plain release fail its own version check. A RELEASE that is not the default still
    counts, so a package whose revision was tampered with cannot pass as the tag.
    """
    if release_field and release_field != "1":
        return f"{version_field}-{release_field}"
    return version_field


def deb_embedded(deb_version: str) -> str:
    """Reconstruct the version of a built .deb.

    Compose Desktop appends no Debian revision, so the field is used as-is apart from
    a trailing `-<digits>` that some tooling adds; stripping it keeps the comparison
    against what the package declares rather than against its packaging revision.
    """
    return re.sub(r"-[0-9].*$", "", deb_version)


def verify(fmt: str, embedded: str, tag: str) -> None:
    """Assert that a built package declares the version its tag names.

    Raises [ReleaseVersionError] on a mismatch. The expected string is derived from
    the tag rather than passed in, so packaging and verification cannot drift apart
    by construction — the previous shell computed the two in different steps and they
    already disagreed once.
    """
    version = parse(tag)
    expected = version.package_version
    if embedded != expected:
        raise ReleaseVersionError(
            f"{fmt} declares version {embedded!r} but tag {tag!r} maps to {expected!r} for this format"
        )


def manifest(version: ReleaseVersion, files: list[str]) -> str:
    """Render `MANIFEST.txt` for a release.

    `files` must be non-empty: this file exists to name what shipped, and a manifest
    listing nothing is worse than no manifest, because it reads like one.
    """
    if not files:
        raise ReleaseVersionError("no artifacts to describe — the manifest would name nothing")
    return (
        f"version={version.version}\n"
        f"version_code={version.version_code}\n"
        f"prerelease={'true' if version.prerelease else 'false'}\n"
        f"files={' '.join(sorted(files))}\n"
    )


def _cmd_parse(args: argparse.Namespace) -> int:
    try:
        version = parse(args.raw)
    except ReleaseVersionError as exc:
        # A `::error::` line, not a traceback: this runs in a CI log, and the reader
        # needs the sentence, not a stack. Callers that use command substitution in an
        # `eval` would otherwise swallow the non-zero exit and proceed with empty
        # variables — which is how a rejected version turns into a release.
        print(f"::error::{exc}")
        return 1
    if args.json:
        json.dump(
            {
                "version": version.version,
                "version_code": version.version_code,
                "prerelease": version.prerelease,
                "package_version": version.package_version,
            },
            sys.stdout,
        )
        sys.stdout.write("\n")
    else:
        # `key=value` lines, for `eval "$(...)"` and for `$GITHUB_OUTPUT`.
        sys.stdout.write(f"version={version.version}\n")
        sys.stdout.write(f"version_code={version.version_code}\n")
        sys.stdout.write(f"prerelease={'true' if version.prerelease else 'false'}\n")
        sys.stdout.write(f"package_version={version.package_version}\n")
    return 0


def _cmd_verify(args: argparse.Namespace) -> int:
    try:
        verify(args.format, args.embedded, args.tag)
    except ReleaseVersionError as exc:
        print(f"::error::{exc}")
        return 1
    expected = parse(args.tag).package_version
    print(f"embedded {args.format} version: {args.embedded}")
    print(f"expected from tag '{args.tag}': {expected}")
    return 0


def _cmd_manifest(args: argparse.Namespace) -> int:
    try:
        version = parse(args.tag)
        text = manifest(version, args.files)
    except ReleaseVersionError as exc:
        print(f"::error::{exc}")
        return 1
    sys.stdout.write(text)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="release_version", description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="command", required=True)

    p_parse = sub.add_parser("parse", help="validate a version and print what the release needs")
    p_parse.add_argument("raw", help="tag name or version, e.g. v0.2.1-rc.1")
    p_parse.add_argument("--json", action="store_true", help="emit JSON instead of key=value lines")
    p_parse.set_defaults(func=_cmd_parse)

    p_verify = sub.add_parser("verify", help="check a built package against its tag")
    p_verify.add_argument("--format", required=True, choices=("deb", "rpm", "apk"))
    p_verify.add_argument("--embedded", required=True, help="the version the package declares")
    p_verify.add_argument("--tag", required=True, help="the tag that was built")
    p_verify.set_defaults(func=_cmd_verify)

    p_manifest = sub.add_parser("manifest", help="render MANIFEST.txt to stdout")
    p_manifest.add_argument("--tag", required=True)
    p_manifest.add_argument("--file", dest="files", action="append", required=True, help="repeat per artifact")
    p_manifest.set_defaults(func=_cmd_manifest)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())