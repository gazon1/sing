#!/usr/bin/env python3
"""check-release-tags.py — every release must be able to describe itself.

`meta` proves a tag is on `main` with a green CI run. It says nothing about whether a
release came out of it, and nothing about whether the release that exists is
internally consistent. Those are two gaps this gate closes:

**R1 — every tag in the snapshot has a release, unless it is recorded as a known
failure.** Two tags predate any working release (`v0.1.0`, `v0.2.0`), and their runs
died for reasons that cannot be undone retroactively — one was rejected by `meta`,
the other by a version check that could not pass. A gate that demands a release for
them can never go green, which is the same "red by construction" advisory debt this
repository is trying to retire (#667). So the snapshot carries
`tags_without_release: {tag: reason}`, and R1 fires only for a tag that is neither
released nor explained. The point is to catch the *next* tag that produces nothing,
not to relitigate 2026-10-09.

**R2 — a release's own bookkeeping matches its own assets.** `publish` writes
`MANIFEST.txt` and `SHA256SUMS.txt`, then uploads whatever is in `dist/`. Nothing
checked that the manifest names the files that were actually attached, or that the
checksums cover them. The manifest step once wrote shell source into the file and left
`files=` empty (it had never run); nothing would have said so.

Why a snapshot rather than live GitHub queries: the gate has to run locally in
`static-gates.sh`, on a developer machine with no token, and in CI. Same arrangement
as `check-backlog-issue-refs.py`, for the same reason.

What this gate deliberately does NOT do: download and hash the binaries. That is
380 MB per release and the hashes were computed at build time from the same files;
verifying them again proves the upload, not the release. What it verifies is the
claim each release makes about itself, which is the part that is written by hand.

Usage:
    python3 scripts/check-release-tags.py --snapshot config/docs/releases-snapshot.json
    python3 scripts/check-release-tags.py --snapshot ... --refresh   # via gh, needs auth
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

import click

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import release_version as rv  # noqa: E402

#: Files the release process writes itself; they are described by the release rather
#: than being artifacts, and `SHA256SUMS.txt` cannot hash itself.
BOOKKEEPING = {"SHA256SUMS.txt", "MANIFEST.txt"}

_CHECKSUM_LINE = re.compile(r"^([0-9a-f]{64})\s+\*?\./?(.+)$")


def _findings_for_release(release: dict) -> list[str]:
    """Return the findings for one release; empty means it is self-consistent."""
    findings: list[str] = []
    tag = release.get("tag", "<no tag>")

    assets = [a["name"] for a in release.get("assets", []) if "name" in a]
    if not assets:
        findings.append(f"{tag}: no assets attached")
        return findings

    # The tag must be a version the pipeline could have produced at all. A release for
    # `nightly` or `latest` was not built by this pipeline, and would fail every
    # version comparison below for the wrong reason.
    try:
        version = rv.parse(tag)
    except rv.ReleaseVersionError as exc:
        findings.append(f"{tag}: not a release version this pipeline builds ({exc})")
        return findings

    # A prerelease tag must be marked prerelease, and a plain one must not be. The flag
    # is what makes `gh upgrade` and the releases page behave, and it is set from a
    # different line of the same workflow than the version.
    if bool(release.get("prerelease")) != version.prerelease:
        findings.append(
            f"{tag}: prerelease flag is {release.get('prerelease')!r} but the tag says "
            f"prerelease={version.prerelease}"
        )

    artifacts = [a for a in assets if a not in BOOKKEEPING]
    if not artifacts:
        findings.append(f"{tag}: has bookkeeping files but no artifacts")

    checksums = release.get("sha256sums")
    if not checksums:
        findings.append(f"{tag}: no SHA256SUMS.txt — the release cannot be verified by a reader")
    else:
        listed = {}
        for line in checksums.splitlines():
            match = _CHECKSUM_LINE.match(line.strip())
            if match:
                listed[match.group(2).strip()] = match.group(1)
            elif line.strip():
                findings.append(f"{tag}: unparseable SHA256SUMS line: {line.strip()!r}")
        missing = sorted(a for a in artifacts if a not in listed)
        if missing:
            findings.append(f"{tag}: not covered by SHA256SUMS.txt: {', '.join(missing)}")
        # A checksum file that names a file nobody attached is worse than a short one:
        # it asserts a verification that cannot be performed.
        ghost = sorted(name for name in listed if name not in assets)
        if ghost:
            findings.append(f"{tag}: SHA256SUMS.txt names files that are not attached: {', '.join(ghost)}")
        if "SHA256SUMS.txt" in listed:
            findings.append(f"{tag}: SHA256SUMS.txt hashes itself")

    manifest = release.get("manifest")
    if not manifest:
        findings.append(f"{tag}: no MANIFEST.txt")
    else:
        fields = dict(
            line.split("=", 1) for line in manifest.splitlines() if "=" in line and not line.startswith("#")
        )
        if fields.get("version") != version.version:
            findings.append(
                f"{tag}: MANIFEST.txt says version={fields.get('version')!r}, tag says {version.version!r}"
            )
        named = fields.get("files", "").split()
        if sorted(named) != sorted(artifacts):
            findings.append(
                f"{tag}: MANIFEST.txt names {len(named)} file(s), {len(artifacts)} artifact(s) are attached"
            )
    return findings


def check(snapshot: dict) -> list[str]:
    """Every finding in the snapshot; empty means the releases are consistent."""
    findings: list[str] = []

    releases = snapshot.get("releases", [])
    by_tag = {}
    for release in releases:
        tag = release.get("tag")
        if tag in by_tag:
            findings.append(f"{tag}: appears twice in the snapshot")
        by_tag[tag] = release

    for tag in snapshot.get("tags", []):
        if tag not in by_tag and tag not in snapshot.get("tags_without_release", {}):
            findings.append(f"{tag}: tag has no release, and is not recorded in tags_without_release")

    # An exception that names a release contradicts the snapshot.
    for tag in snapshot.get("tags_without_release", {}):
        if tag in by_tag:
            findings.append(f"{tag}: listed in tags_without_release but a release exists for it")

    for release in releases:
        findings.extend(_findings_for_release(release))

    return findings


def refresh(path: Path) -> None:
    """Refetch the snapshot from GitHub. Needs `gh` and a token.

    Assets are read one release at a time: `gh release list --json` does not accept
    `assets` at all (it offers createdAt / isDraft / isPrerelease / tagName / …), so
    the only route is `gh release view <tag> --json assets`. Drafts are included —
    they are releases, and a draft that has been sitting for a week is exactly what
    this gate should be able to see.
    """
    listed = json.loads(
        subprocess.run(
            ["gh", "release", "list", "--limit", "100", "--json", "tagName,isDraft,isPrerelease"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    )

    releases = []
    for entry in listed:
        tag = entry["tagName"]
        view = json.loads(
            subprocess.run(
                ["gh", "release", "view", tag, "--json", "assets,isDraft,isPrerelease"],
                capture_output=True,
                text=True,
                check=True,
            ).stdout
        )
        release = {
            "tag": tag,
            "draft": view.get("isDraft", entry.get("isDraft")),
            "prerelease": view.get("isPrerelease", entry.get("isPrerelease")),
            "assets": [{"name": a["name"]} for a in view.get("assets", [])],
        }
        for field, asset in (("sha256sums", "SHA256SUMS.txt"), ("manifest", "MANIFEST.txt")):
            result = subprocess.run(
                ["gh", "release", "download", tag, "-p", asset, "-O", "-"],
                capture_output=True,
                text=True,
            )
            release[field] = result.stdout if result.returncode == 0 else ""
        releases.append(release)

    tags = subprocess.run(
        ["git", "tag", "-l", "v*"], capture_output=True, text=True, cwd=ROOT, check=True
    ).stdout.split()

    # The reasons in `tags_without_release` are hand-written history: GitHub has no
    # record of why v0.1.0 never produced a release, only that it did not. Dropping
    # them on refresh would turn every documented exception into a red finding and
    # send the next person looking for a reason that cannot be found. Entries for tags
    # that DO now have a release are dropped, because R1 checks that contradiction.
    existing = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    released = {r["tag"] for r in releases}
    exceptions = {
        tag: reason
        for tag, reason in existing.get("tags_without_release", {}).items()
        if tag not in released
    }

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {"tags": sorted(tags), "releases": releases, "tags_without_release": exceptions},
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"refreshed {path}: {len(releases)} release(s), {len(tags)} tag(s)")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

@click.command()
@click.option("--snapshot", type=click.Path(exists=True, path_type=Path),
              default=ROOT / "config/docs/releases-snapshot.json",
              help="path to the releases snapshot (default: config/docs/releases-snapshot.json)")
@click.option("--refresh", "do_refresh", is_flag=True,
              help="refetch snapshot from GitHub (needs auth)")
@click.pass_context
def cli(ctx: click.Context, snapshot: Path, do_refresh: bool) -> None:
    if do_refresh:
        refresh(snapshot)
        raise ctx.exit(CLEAN)

    if not snapshot.is_file():
        click.echo(f"ERROR: snapshot not found: {snapshot}", err=True)
        click.echo("Run `just releases-refresh` to fetch the current release state.", err=True)
        raise ctx.exit(IO_ERROR)

    findings = check(json.loads(snapshot.read_text(encoding="utf-8")))
    if not findings:
        count = len(json.loads(snapshot.read_text(encoding="utf-8")).get("releases", []))
        click.echo(f"check-release-tags.py: OK — {count} release(s), tags and assets consistent")
        raise ctx.exit(CLEAN)

    for finding in findings:
        click.echo(f"::error::{finding}")
    click.echo(f"{len(findings)} release problem(s)", err=True)
    raise ctx.exit(VALIDATION_FAILURE)


def main(argv: list[str] | None = None) -> int:
    try:
        return cli.main(standalone_mode=False, args=argv)
    except SystemExit:
        raise


if __name__ == "__main__":
    raise SystemExit(main())
