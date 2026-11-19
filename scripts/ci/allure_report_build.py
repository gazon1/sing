#!/usr/bin/env python3
"""allure_report_build.py — build the Allure report and the publish tree ourselves.

The publication used to be built by `simple-elf/allure-report-action@v1.15`.
That action is a Docker action: the runner rebuilds its image on every run,
from a floating base image, and the image downloads the Allure CLI from
Maven Central with `wget` and without a retry. An HTTP 429 there failed the
job on 2026-11-19 — and that job is a leaf of the one gate that blocks merges
and releases, so a third party's hiccup cost this repository a release.

This module is what the action did, minus the image:

- it installs the Allure CLI itself, from a pinned artifact with a pinned
  digest, with retries and with mirrors to fall back to;
- it generates the report with that CLI;
- it assembles the publish tree out of the generated report.

What it does not do is own the publication. The manifest, the retention and
the state of the site belong to `allure_pages.py`; this module produces the
tree that helper judges, and nothing else.

The address the report carries is the address the site answers with. The
action hard-coded `https://<owner>.github.io/<repo>` — the address of a
project site — and this repository publishes on a custom domain, so the link
the report carried led nowhere. The address is read from the Pages settings
here, the same way `allure_report_link.py` reads it for the comment it posts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path, PurePosixPath
from typing import Any, Callable

# The helpers of this directory are imported as modules, not as a package:
# the workflow runs this file as a script, and the tests load it by path.
_CI_DIR = pathlib.Path(__file__).resolve().parent
if str(_CI_DIR) not in sys.path:
    sys.path.insert(0, str(_CI_DIR))

from github_api import LinkError, gh_api  # noqa: E402

#: The Allure CLI this repository publishes with. The version is pinned, and
#: so is the digest of the artifact: a mirror that serves other bytes is a
#: mirror that is not trusted, and the download is refused.
ALLURE_VERSION = "2.44.0"

#: The digest of `allure-commandline-2.44.0.tgz`. All the mirrors below serve
#: these bytes, and the digest is what proves it: the size of the file and
#: the name of the mirror prove nothing.
ALLURE_SHA256 = "7021a90828c00cd6ec992027cce48e8a94bd87fad43d0c2dcac4795cadc178d7"

#: The mirrors the CLI is downloaded from, in order. The first one is Maven
#: Central itself; the others are mirrors of it. A mirror that is down or
#: that rate-limits is a reason to try the next one — that fallback is what
#: the action did not have, and it is what the 429 of 2026-11-19 needed.
MAVEN_MIRRORS = (
    "https://repo.maven.apache.org/maven2/io/qameta/allure/allure-commandline",
    "https://repo1.maven.org/maven2/io/qameta/allure/allure-commandline",
    "https://maven-central.storage-download.googleapis.com/maven2/io/qameta/allure/allure-commandline",
)

#: How many times one mirror is tried before the next one is used.
DEFAULT_ATTEMPTS = 3

#: How long a download may take before it is given up on.
DOWNLOAD_TIMEOUT = 120

#: How long the install waits between two attempts of the same mirror.
RETRY_DELAY = 5.0

#: The agent the download identifies itself as.
USER_AGENT = "singularity-todo-ci/allure-report-build"


class BuildError(Exception):
    """A failure of the report build that must not be published as a report."""


# ── Installing the CLI ────────────────────────────────────────────────────────


def artifact_url(mirror: str, version: str = ALLURE_VERSION) -> str:
    """The address of the CLI archive on one mirror."""
    return f"{mirror.rstrip('/')}/{version}/allure-commandline-{version}.tgz"


def sha256_of(path: Path) -> str:
    """The digest of a file."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(
    url: str,
    destination: Path,
    *,
    opener: Callable[..., Any] = urllib.request.urlopen,
    timeout: int = DOWNLOAD_TIMEOUT,
) -> None:
    """Download one file into `destination`.

    A download that answers with an error status raises: `urlopen` raises for
    every status but 200, and a 429 is exactly the failure the mirrors are
    there to survive.
    """
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with opener(request, timeout=timeout) as response, destination.open("wb") as handle:
        shutil.copyfileobj(response, handle)


def safe_extract(archive: Path, destination: Path) -> None:
    """Unpack a tarball, refusing anything that leaves the tree it goes into.

    Only regular files and directories are unpacked. A link is a way out of
    the tree, and what is unpacked here is a program that runs.
    """
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        if not members:
            raise BuildError(f"empty archive: {archive}")
        for member in members:
            name = member.name
            if name.startswith("/") or ".." in PurePosixPath(name).parts:
                raise BuildError(f"archive holds an unsafe path: {name!r}")
            if not (member.isfile() or member.isdir()):
                raise BuildError(f"archive holds a link or a device: {name!r}")
        for member in members:
            target = destination / member.name
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            source = tar.extractfile(member)
            if source is None:
                raise BuildError(f"archive member cannot be read: {member.name!r}")
            target.parent.mkdir(parents=True, exist_ok=True)
            with source, target.open("wb") as handle:
                shutil.copyfileobj(source, handle)
            os.chmod(target, member.mode & 0o777 or 0o644)


def install_allure(
    dest_dir: Path,
    *,
    version: str = ALLURE_VERSION,
    sha256: str = ALLURE_SHA256,
    mirrors: tuple[str, ...] = MAVEN_MIRRORS,
    attempts: int = DEFAULT_ATTEMPTS,
    opener: Callable[..., Any] = urllib.request.urlopen,
    sleeper: Callable[[float], None] = time.sleep,
) -> Path:
    """Install the Allure CLI into `dest_dir` and return the path of the executable.

    The archive is downloaded from the mirrors in order, retried on each of
    them, and accepted only when its digest is the one this repository pins.
    A mirror that is down, that rate-limits or that serves other bytes is
    skipped and the next one is tried; the install fails only when every
    mirror failed.

    An install that is already there is not repeated: the digest is pinned,
    so the bytes that are on the disk are the bytes that are wanted.
    """
    target = dest_dir / f"allure-{version}"
    executable = target / "bin" / "allure"
    if executable.is_file():
        return executable

    problems: list[str] = []
    with tempfile.TemporaryDirectory(prefix="allure-cli-") as workspace:
        archive = Path(workspace) / f"allure-commandline-{version}.tgz"
        staging = Path(workspace) / "unpacked"
        for mirror in mirrors:
            url = artifact_url(mirror, version)
            for attempt in range(1, max(1, attempts) + 1):
                try:
                    fetch(url, archive, opener=opener)
                except (urllib.error.URLError, OSError) as exc:
                    problems.append(f"{url} (attempt {attempt}): {exc}")
                else:
                    digest = sha256_of(archive)
                    if digest != sha256:
                        problems.append(
                            f"{url} (attempt {attempt}): digest {digest} is not the pinned one"
                        )
                    else:
                        staging.mkdir(parents=True, exist_ok=True)
                        safe_extract(archive, staging)
                        unpacked = staging / f"allure-{version}"
                        if not (unpacked / "bin" / "allure").is_file():
                            problems.append(f"{url}: the archive holds no allure-{version}/bin/allure")
                        else:
                            dest_dir.mkdir(parents=True, exist_ok=True)
                            if target.exists():
                                shutil.rmtree(target)
                            shutil.move(str(unpacked), str(target))
                            os.chmod(target / "bin" / "allure", 0o755)
                            return executable
                if attempt < max(1, attempts):
                    sleeper(RETRY_DELAY)
    raise BuildError(
        "the Allure CLI could not be installed: " + "; ".join(problems)
    )


# ── Building the report ───────────────────────────────────────────────────────


def copy_tree(source: Path, destination: Path) -> None:
    """Copy the contents of one tree into another, merging what is there."""
    if not source.is_dir():
        return
    shutil.copytree(source, destination, dirs_exist_ok=True)


def executor_document(
    *,
    report_name: str,
    population: str,
    run_number: str,
    site_url: str | None = None,
    run_id: str | None = None,
    repo: str | None = None,
    server_url: str = "https://github.com",
) -> dict[str, Any]:
    """The executor the report carries: what ran it, and where it is published.

    The address is the one the site answers with. When it is unknown, the
    fields that carry it are left out: a link that leads nowhere is worse
    than no link, and the upstream action wrote the address of a project
    site into every report of a repository that publishes on a custom domain.
    """
    document: dict[str, Any] = {
        "name": "GitHub Actions",
        "type": "github",
        "reportName": report_name,
        "buildOrder": run_number,
    }
    if site_url:
        base = site_url.rstrip("/")
        document["url"] = f"{base}/{population}"
        document["reportUrl"] = f"{base}/{population}/{run_number}"
    if run_id and repo:
        document["buildUrl"] = f"{server_url.rstrip('/')}/{repo}/actions/runs/{run_id}"
        document["buildName"] = f"GitHub Actions Run #{run_id}"
    return document


def write_executor_json(results_dir: Path, document: dict[str, Any]) -> Path:
    """Write the executor into the results directory, where the CLI reads it."""
    path = results_dir / "executor.json"
    path.write_text(json.dumps(document, sort_keys=True), encoding="utf-8")
    return path


def seed_results_history(gh_pages_dir: Path, population: str, results_dir: Path) -> int:
    """Copy the population's history into the results, so the report shows trends.

    The history of a population is the one the previous publications left
    behind. The CLI reads it from the results, and writes the merged history
    into the report — which is what makes a report of this run comparable to
    the reports before it.
    """
    source = gh_pages_dir / population / "last-history"
    if not source.is_dir():
        return 0
    copy_tree(source, results_dir / "history")
    return sum(1 for path in source.rglob("*") if path.is_file())


def generate_report(
    executable: Path,
    results_dir: Path,
    report_dir: Path,
    *,
    runner: Callable[..., Any] = subprocess.run,
) -> None:
    """Generate the report with the Allure CLI.

    A CLI that fails is a failure of the build, and the reason it printed is
    the reason the publication failed: a report that is not there cannot be
    published.
    """
    result = runner(
        [str(executable), "generate", "--clean", str(results_dir), "-o", str(report_dir)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip()
        raise BuildError(f"the Allure CLI failed: {detail[:2000]}")
    if not (report_dir / "index.html").is_file():
        raise BuildError(f"the Allure CLI produced no report in {report_dir}")


def publish_report(report_dir: Path, history_dir: Path, population: str, run_number: str) -> None:
    """Put the generated report into the publish tree.

    The report goes to `<population>/<run number>`, and the history the CLI
    merged goes to `<population>/last-history` — the history the next
    publication reads.
    """
    population_dir = history_dir / population
    copy_tree(report_dir, population_dir / run_number)
    copy_tree(report_dir / "history", population_dir / "last-history")


def build_report_tree(
    *,
    executable: Path,
    results_dir: Path,
    report_dir: Path,
    gh_pages_dir: Path,
    history_dir: Path,
    population: str,
    run_number: str,
    executor: dict[str, Any],
    runner: Callable[..., Any] = subprocess.run,
) -> dict[str, Any]:
    """Build the report and put it into the publish tree.

    The publish tree starts as a copy of the branch: the publication replaces
    the branch, and the tree that replaces it carries everything the branch
    carried — the other population, the manifest, the custom domain.
    """
    copy_tree(gh_pages_dir, history_dir)
    seeded = seed_results_history(gh_pages_dir, population, results_dir)
    write_executor_json(results_dir, executor)
    generate_report(executable, results_dir, report_dir, runner=runner)
    publish_report(report_dir, history_dir, population, run_number)
    return {
        "population": population,
        "run_number": run_number,
        "report": str(report_dir / "index.html"),
        "published": str(history_dir / population / run_number / "index.html"),
        "history_files": seeded,
        "site_url": executor.get("url"),
    }


def resolve_site_url(
    repo: str | None,
    configured: str | None = None,
    runner: Callable[..., Any] = gh_api,
) -> str | None:
    """The address the Pages site answers with, or None when it is unknown.

    The Pages settings are asked first: the answer is the site's own, and it
    is right whether the site has a custom domain or not. When the settings
    do not answer, the address that was configured on purpose is used, and
    when there is none either, there is no address.

    A settings read that fails is not a failure of the build: the address is
    a link inside the report, and a report without a link is better than no
    report at all.
    """
    if not repo:
        return configured
    try:
        from allure_report_link import fetch_pages_site, resolve_site_url as resolve
    except ImportError as exc:  # pragma: no cover - the helper sits next to this one
        raise BuildError(f"the Pages address cannot be read: {exc}") from exc
    try:
        site = fetch_pages_site(repo, runner)
    except (LinkError, OSError):
        return configured
    return resolve(site, configured)


# ── CLI ───────────────────────────────────────────────────────────────────────


def cmd_install(args: argparse.Namespace) -> int:
    executable = install_allure(
        Path(args.dest),
        version=args.version,
        sha256=args.sha256,
        attempts=args.attempts,
    )
    print(
        json.dumps(
            {"allure": str(executable), "version": args.version, "sha256": args.sha256},
            sort_keys=True,
        )
    )
    return 0


def cmd_build(args: argparse.Namespace) -> int:
    version = args.version
    executable = Path(args.allure_bin) if args.allure_bin else (
        Path(args.allure_dir) / f"allure-{version}" / "bin" / "allure"
    )
    if not executable.is_file():
        raise BuildError(
            f"the Allure CLI is not installed: {executable} — run the install step first"
        )
    site_url = args.site_url or resolve_site_url(args.repo, args.site_url)
    executor = executor_document(
        report_name=args.report_name or f"Allure report ({args.population})",
        population=args.population,
        run_number=args.run_number,
        site_url=site_url,
        run_id=args.run_id,
        repo=args.repo,
        server_url=args.server_url,
    )
    result = build_report_tree(
        executable=executable,
        results_dir=Path(args.results_dir),
        report_dir=Path(args.report_dir),
        gh_pages_dir=Path(args.gh_pages_dir),
        history_dir=Path(args.history_dir),
        population=args.population,
        run_number=args.run_number,
        executor=executor,
    )
    print(json.dumps(result, sort_keys=True))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_install = sub.add_parser("install", help="install the pinned Allure CLI")
    p_install.add_argument("--dest", required=True)
    p_install.add_argument("--version", default=ALLURE_VERSION)
    p_install.add_argument("--sha256", default=ALLURE_SHA256)
    p_install.add_argument("--attempts", type=int, default=DEFAULT_ATTEMPTS)
    p_install.set_defaults(func=cmd_install)

    p_build = sub.add_parser("build", help="build the report and assemble the publish tree")
    p_build.add_argument("--allure-dir", default="build/allure-cli")
    p_build.add_argument("--allure-bin")
    p_build.add_argument("--version", default=ALLURE_VERSION)
    p_build.add_argument("--results-dir", required=True)
    p_build.add_argument("--report-dir", required=True)
    p_build.add_argument("--gh-pages-dir", required=True)
    p_build.add_argument("--history-dir", required=True)
    p_build.add_argument("--population", required=True)
    p_build.add_argument("--run-number", required=True)
    p_build.add_argument("--run-id")
    p_build.add_argument("--repo")
    p_build.add_argument("--site-url")
    p_build.add_argument("--server-url", default="https://github.com")
    p_build.add_argument("--report-name")
    p_build.set_defaults(func=cmd_build)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except (BuildError, LinkError, OSError) as exc:
        # One sentence, not a traceback: the publication log gets a reason.
        print(f"allure report build error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
