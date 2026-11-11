#!/usr/bin/env python3
"""Allure Pages publication: history bootstrap, manifest, monotonic latest, and validation.

Covers #2756/#2764/#2766/#2767/#2769/#2774:
- merged Pages input validation
- gh-pages history bootstrap from tarball content/outcome (testable without network)
- per-population (fast/full) namespaces with isolated manifest/history/latest state
- ordering by (run_number, run_attempt), idempotency by (run_id, run_attempt)
- monotonic latest/landing updates, manifest/index validation
- bounded per-population report retention without removing latest/required history
- publish-tree invariants before deploy

The helper owns three things and nothing else: the publication contracts
(Pydantic models), the publication policy (ordering, idempotency, retention),
and the filesystem operations that put the policy on disk. The CLI is a thin
shell over them.

The filesystem rules it follows:

- A tree is replaced only by a complete replacement. A fetched history is
  unpacked into a staging directory and promoted, so a failed fetch never
  leaves a half-unpacked tree behind and never destroys the tree that was
  there.
- The publication state is written as one unit. The manifest, the
  population index and the root index land together or not at all.
- Every path that reaches the filesystem goes through the report-path
  policy, so a path from a manifest cannot address anything outside the
  namespace it belongs to.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Literal

try:
    from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator
except ImportError as exc:  # pragma: no cover - the dependency is declared in scripts/src
    raise SystemExit(
        "allure_pages.py needs pydantic v2: install it with `pip install 'pydantic>=2'`"
    ) from exc

SCHEMA_VERSION = 2
SUPPORTED_POPULATIONS = ("fast", "full")
REPORTS_PER_POPULATION = 20

# One report-path policy for the whole helper: a report lives in a single
# directory of its population, named by a safe segment. The rule is enforced
# once, here, and every path that reaches the filesystem goes through it.
REPORT_PATH_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")

# What a fetched history tarball may hold. The tarball is a copy of a branch
# this repository published: a few thousand files, a few hundred megabytes.
# A tarball that holds more is not one, and unpacking it is how a disk gets
# filled.
MAX_TAR_MEMBERS = 20_000
MAX_TAR_BYTES = 2 * 1024 * 1024 * 1024


class PublishError(Exception):
    """A clear publication failure that must not delete the current site."""


# ── Contracts ─────────────────────────────────────────────────────────────────


def safe_report_path(value: Any) -> str:
    """Return `value` if it is a safe report-path segment, raise otherwise.

    A report path is one directory name inside a population namespace. It may
    not be absolute, may not contain a separator, and may not be `..`, so it
    cannot address anything outside the population directory.
    """
    if not isinstance(value, str) or not REPORT_PATH_RE.match(value):
        raise PublishError(
            f"invalid report_path: expected a single safe segment "
            f"(letters, digits, '.', '_', '-'), got {value!r}"
        )
    return value


def report_dir(population_dir: Path, report_path: str) -> Path:
    """The directory of one report inside a population namespace.

    Every path that reaches the filesystem goes through the report-path
    policy, so a path that came out of a manifest can never address
    anything outside the namespace it belongs to.
    """
    return population_dir / safe_report_path(report_path)


class Publication(BaseModel):
    """One published run: the record a manifest stores."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    run_id: int = Field(strict=True, ge=1)
    run_number: int = Field(strict=True, ge=1)
    run_attempt: int = Field(strict=True, ge=1)
    event: str
    sha: str
    population: Literal["fast", "full"]
    report_path: str

    @field_validator("event", "sha")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be empty")
        return value

    @field_validator("report_path")
    @classmethod
    def _safe_path(cls, value: str) -> str:
        try:
            return safe_report_path(value)
        except PublishError as exc:
            raise ValueError(str(exc)) from exc

    @property
    def identity(self) -> str:
        """The publication identity: stable per execution attempt."""
        return f"{self.run_id}-{self.run_attempt}"

    @property
    def order_key(self) -> tuple[int, int]:
        """The ordering key: newer means a greater (run_number, run_attempt)."""
        return (self.run_number, self.run_attempt)


class Manifest(BaseModel):
    """The publication state of one population namespace."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    schema_version: Literal[2] = SCHEMA_VERSION
    population: Literal["fast", "full"]
    latest: Publication | None = None
    publications: dict[str, Publication] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _invariants(self) -> "Manifest":
        for key, publication in self.publications.items():
            if key != publication.identity:
                raise ValueError(
                    f"identity key {key!r} does not match {publication.identity!r}"
                )
            if publication.population != self.population:
                raise ValueError(
                    f"publication {key!r} population {publication.population!r} does not match "
                    f"manifest population {self.population!r}"
                )
        if self.latest is not None:
            stored = self.publications.get(self.latest.identity)
            if stored is None:
                raise ValueError("latest is not present in publications")
            if stored != self.latest:
                raise ValueError("latest does not match the stored publication record")
        return self


def _model_error(label: str, exc: ValidationError) -> PublishError:
    """Turn a validation failure into the one error the CLI knows how to print."""
    first = exc.errors()[0] if exc.errors() else {}
    location = ".".join(str(part) for part in first.get("loc", ())) or label
    return PublishError(f"invalid {label}: {location}: {first.get('msg', 'invalid value')}")


def _population(value: Any) -> str:
    """The population namespaces this repository publishes."""
    if value not in SUPPORTED_POPULATIONS:
        raise PublishError(
            f"invalid population: expected one of {list(SUPPORTED_POPULATIONS)}, got {value!r}"
        )
    return value


def _positive_int(value: Any, name: str) -> int:
    if isinstance(value, bool):
        raise PublishError(f"invalid {name}: expected positive integer")
    try:
        number = int(value)
    except Exception as exc:
        raise PublishError(f"invalid {name}: expected positive integer") from exc
    if number < 1:
        raise PublishError(f"invalid {name}: expected >= 1")
    return number


def _cli_int(value: Any, name: str) -> int:
    """A CLI integer: digits only, so `10.5` never becomes `10`."""
    text = str(value).strip()
    if not re.fullmatch(r"[0-9]+", text):
        raise PublishError(f"invalid {name}: expected a positive integer, got {value!r}")
    number = int(text)
    if number < 1:
        raise PublishError(f"invalid {name}: expected >= 1")
    return number


# ── Manifest storage ──────────────────────────────────────────────────────────


def manifest_path(population_dir: Path) -> Path:
    return population_dir / "allure-manifest.json"


def empty_manifest(population: str) -> Manifest:
    """The manifest of a population that has not published anything yet."""
    return Manifest(schema_version=SCHEMA_VERSION, population=population)


def load_manifest(path: Path, population: str) -> Manifest:
    """Read one population's manifest; a missing file is an empty manifest."""
    _population(population)
    if not path.exists():
        return empty_manifest(population)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise PublishError(f"malformed publication manifest: {exc}") from exc
    if not isinstance(data, dict):
        raise PublishError("malformed publication manifest: expected object")
    version = data.get("schema_version")
    if version != SCHEMA_VERSION:
        raise PublishError(f"unsupported publication manifest schema_version: {version!r}")
    data.setdefault("population", population)
    try:
        manifest = Manifest.model_validate(data)
    except ValidationError as exc:
        raise _model_error("publication manifest", exc) from exc
    if manifest.population != population:
        raise PublishError(
            f"publication manifest population {manifest.population!r} does not match {population!r}"
        )
    return manifest


def manifest_json(manifest: Manifest) -> str:
    """The on-disk manifest format: sorted keys, compact, one trailing newline."""
    payload = manifest.model_dump(mode="json")
    return json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n"


def save_manifest(path: Path, manifest: Manifest) -> None:
    _atomic_write_text(path, manifest_json(manifest))


# ── Publication policy ────────────────────────────────────────────────────────


def decide(manifest: Manifest, incoming: Publication) -> tuple[str, str]:
    """Decide what an incoming publication does to a population's latest.

    Pure: it reads the manifest and returns an action and a reason. It never
    touches the filesystem.
    """
    if incoming.population != manifest.population:
        raise PublishError(
            f"incoming population {incoming.population!r} does not match "
            f"manifest population {manifest.population!r}"
        )

    existing = manifest.publications.get(incoming.identity)
    if existing is not None:
        if existing != incoming:
            raise PublishError(
                f"publication identity {incoming.identity} already recorded with different metadata"
            )
        if manifest.latest is not None and manifest.latest.identity == incoming.identity:
            return "skip-duplicate", f"{incoming.identity} is already the recorded latest publication"
        return "skip-duplicate", f"{incoming.identity} is already recorded"

    if manifest.latest is not None and incoming.order_key <= manifest.latest.order_key:
        return "skip-stale", (
            f"incoming {list(incoming.order_key)} is not newer than latest "
            f"{list(manifest.latest.order_key)}"
        )
    return "publish", "incoming publication wins latest selection"


# ── Filesystem operations ─────────────────────────────────────────────────────


def _atomic_write_text(path: Path, content: str) -> None:
    """Write a file through a temporary file in the same directory."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def _commit_files(changes: dict[Path, str]) -> None:
    """Write a set of files as one unit: all of them land, or none does.

    The publication state is several files that only make sense together:
    the manifest, the population index and the root index. Written one by
    one, a failure in the middle leaves a state that records one thing and
    shows another. This writes them together and puts the old contents
    back when a write fails.
    """
    previous = {path: (path.read_bytes() if path.is_file() else None) for path in changes}
    written: list[Path] = []
    try:
        for path, content in changes.items():
            _atomic_write_text(path, content)
            written.append(path)
    except Exception:
        for path in written:
            original = previous[path]
            if original is None:
                path.unlink(missing_ok=True)
            else:
                _atomic_write_text(path, original.decode("utf-8"))
        raise


def _population_index_target(report_path: str) -> str:
    """The redirect target of a population index, escaped for an HTML context."""
    return html.escape(f"{safe_report_path(report_path)}/index.html", quote=True)


def _population_index_html(report_path: str) -> str:
    """The redirect page of a population namespace."""
    target = _population_index_target(report_path)
    return (
        '<!DOCTYPE html><meta charset="utf-8">'
        f'<meta http-equiv="refresh" content="0; URL={target}">'
        '<meta http-equiv="Pragma" content="no-cache">'
        '<meta http-equiv="Expires" content="0">\n'
    )


def write_population_index(population_dir: Path, report_path: str) -> None:
    _atomic_write_text(population_dir / "index.html", _population_index_html(report_path))


def build_root_index(populations: Iterable[str]) -> str:
    items = [
        f'<li><a href="{html.escape(pop, quote=True)}/index.html">'
        f"{html.escape(pop)} latest report</a></li>"
        for pop in populations
    ]
    return (
        '<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        "<title>Allure reports</title></head><body>"
        "<h1>Allure reports</h1><p>Population-specific latest reports:</p>"
        f"<ul>{''.join(items)}</ul></body></html>\n"
    )


def present_populations(site_dir: Path) -> list[str]:
    present = [p for p in SUPPORTED_POPULATIONS if manifest_path(site_dir / p).is_file()]
    return present


def write_root_index(site_dir: Path, populations: Iterable[str] | None = None) -> None:
    pops = list(populations) if populations is not None else present_populations(site_dir)
    if not pops:
        pops = list(SUPPORTED_POPULATIONS)
    _atomic_write_text(site_dir / "index.html", build_root_index(pops))


def ensure_population_namespace(gh_pages_dir: Path, population: str) -> Path:
    """Ensure gh-pages/<population> exists with isolated history state.

    Mixed root last-history is never seeded into population namespaces (ADR #2761/#2766).
    """
    pop_dir = gh_pages_dir / population
    pop_dir.mkdir(parents=True, exist_ok=True)
    (pop_dir / "last-history").mkdir(parents=True, exist_ok=True)
    return pop_dir


def prepare_namespace(gh_pages_dir: Path, population: str) -> dict[str, Any]:
    _population(population)
    gh_pages_dir.mkdir(parents=True, exist_ok=True)
    pop_dir = ensure_population_namespace(gh_pages_dir, population)
    mpath = manifest_path(pop_dir)
    manifest = load_manifest(mpath, population)
    if not mpath.exists():
        save_manifest(mpath, manifest)
    fresh = manifest.latest is None and not manifest.publications
    return {"population": population, "path": str(pop_dir), "clean_history": fresh}


def validate_pages_input(results_dir: Path) -> None:
    if not results_dir.exists():
        raise PublishError(f"missing Pages input artifact directory: {results_dir}")
    if not results_dir.is_dir():
        raise PublishError(f"Pages input artifact is not a directory: {results_dir}")
    if not any(results_dir.rglob("*-result.json")):
        raise PublishError(
            f"malformed Pages input artifact: no *-result.json files in {results_dir}"
        )


def validate_population_state(population_dir: Path, population: str, manifest: Manifest | None = None) -> Publication:
    if manifest is None:
        manifest = load_manifest(manifest_path(population_dir), population)
    if manifest.latest is None:
        raise PublishError(f"publication manifest for {population} has no latest entry")
    latest = manifest.latest
    report_dir_path = report_dir(population_dir, latest.report_path)
    if not report_dir_path.is_dir():
        raise PublishError(f"manifest latest points to missing report: {population}/{latest.report_path}")
    if not (report_dir_path / "index.html").is_file():
        raise PublishError(
            f"manifest latest report is missing index.html: {population}/{latest.report_path}"
        )
    pop_index = population_dir / "index.html"
    if not pop_index.is_file():
        raise PublishError(f"population index.html is missing: {population}")
    content = pop_index.read_text(encoding="utf-8")
    expected = f"URL={_population_index_target(latest.report_path)}"
    if expected not in content:
        raise PublishError(
            f"population index.html does not point to latest report: {population} expected {expected!r}"
        )
    if not (population_dir / "last-history").is_dir():
        raise PublishError(f"required history directory missing: {population}/last-history")
    return latest


def validate_publish_tree(site_dir: Path, populations: Iterable[str] | None = None) -> None:
    if not site_dir.exists() or not site_dir.is_dir():
        raise PublishError(f"publish tree does not exist: {site_dir}")
    for git_dir in site_dir.rglob(".git"):
        raise PublishError(f"publish tree contains git metadata: {git_dir.relative_to(site_dir)}")
    for archive in list(site_dir.rglob("*.tar.gz")) + list(site_dir.rglob("*.tgz")):
        raise PublishError(f"publish tree contains temporary archive: {archive.relative_to(site_dir)}")
    root_index = site_dir / "index.html"
    if not root_index.is_file():
        raise PublishError("publish tree missing root index.html")

    pops = list(populations) if populations is not None else present_populations(site_dir)
    if not pops:
        raise PublishError("publish tree has no published populations")
    root_content = root_index.read_text(encoding="utf-8")
    for pop in pops:
        pop_dir = site_dir / pop
        if not pop_dir.is_dir():
            raise PublishError(f"publish tree missing population directory: {pop}")
        if f"{pop}/index.html" not in root_content:
            raise PublishError(f"root index.html does not link to population {pop}")
        mpath = manifest_path(pop_dir)
        if not mpath.is_file():
            raise PublishError(f"publish tree missing manifest for population {pop}")
        manifest = load_manifest(mpath, pop)
        validate_population_state(pop_dir, pop, manifest)

    cname = site_dir / "CNAME"
    if cname.exists() and (not cname.is_file() or not cname.read_text(encoding="utf-8").strip()):
        raise PublishError("publish tree contains invalid CNAME")


def _retention_plan(
    population_dir: Path, manifest: Manifest, keep: int
) -> tuple[dict[str, Publication], list[str]]:
    """The publications to keep and the report paths to remove.

    Retention is manifest-driven: the manifest names what was published,
    so the manifest is what says what may go. A report directory the
    manifest does not name is left alone — the helper does not delete
    history it cannot explain. A publication whose report directory is
    gone is dropped from the manifest instead of being kept as a record
    of something that is not there.
    """
    latest = manifest.latest
    candidates = [
        publication
        for publication in manifest.publications.values()
        if publication.identity == (latest.identity if latest else None)
        or report_dir(population_dir, publication.report_path).is_dir()
    ]
    ordered = sorted(candidates, key=lambda publication: publication.order_key, reverse=True)
    kept = ordered[:keep]
    keep_paths = {publication.report_path for publication in kept}
    if latest is not None:
        keep_paths.add(latest.report_path)
    removed_paths = sorted(
        {publication.report_path for publication in manifest.publications.values()} - keep_paths
    )
    publications = {publication.identity: publication for publication in kept}
    if latest is not None:
        publications[latest.identity] = latest
    return publications, removed_paths


def _remove_reports(population_dir: Path, report_paths: Iterable[str]) -> list[str]:
    """Remove report directories, and report the ones that were there."""
    removed: list[str] = []
    for report_path in report_paths:
        directory = report_dir(population_dir, report_path)
        if directory.is_dir():
            shutil.rmtree(directory)
            removed.append(report_path)
    return removed


def prune_population(population_dir: Path, population: str, keep: int = REPORTS_PER_POPULATION) -> dict[str, Any]:
    """Keep the newest `keep` publications of one population and drop the rest."""
    _population(population)
    keep = _positive_int(keep, "keep-reports")
    mpath = manifest_path(population_dir)
    manifest = load_manifest(mpath, population)
    if manifest.latest is None:
        return {"removed": [], "kept": 0}

    publications, removed_paths = _retention_plan(population_dir, manifest, keep)
    removed = _remove_reports(population_dir, removed_paths)
    if removed or len(publications) != len(manifest.publications):
        manifest = Manifest(
            schema_version=SCHEMA_VERSION,
            population=population,
            latest=manifest.latest,
            publications=publications,
        )
        save_manifest(mpath, manifest)
    return {"removed": removed, "kept": len(publications)}


def reconcile_population(site_dir: Path, population: str) -> None:
    """Ensure population/root indexes reflect recorded latest without promoting new runs."""
    _population(population)
    population_dir = site_dir / population
    manifest = load_manifest(manifest_path(population_dir), population)
    if manifest.latest is None:
        return
    changes = {population_dir / "index.html": _population_index_html(manifest.latest.report_path)}
    pops = present_populations(site_dir)
    if pops:
        changes[site_dir / "index.html"] = build_root_index(pops)
    _commit_files(changes)


def apply_publication(site_dir: Path, incoming: Publication, keep_reports: int = REPORTS_PER_POPULATION) -> dict[str, Any]:
    """Apply the publication decision of `incoming` to one population."""
    population_dir = site_dir / incoming.population
    mpath = manifest_path(population_dir)
    if not population_dir.is_dir():
        raise PublishError(f"population namespace does not exist: {incoming.population}")
    manifest = load_manifest(mpath, incoming.population)
    action, reason = decide(manifest, incoming)

    if action == "publish":
        target = report_dir(population_dir, incoming.report_path)
        if not target.is_dir():
            raise PublishError(
                f"latest report target does not exist: {incoming.population}/{incoming.report_path}"
            )
        if not (target / "index.html").is_file():
            raise PublishError(
                f"latest report target is missing index.html: {incoming.population}/{incoming.report_path}"
            )
        publications = dict(manifest.publications)
        publications[incoming.identity] = incoming
        updated = Manifest(
            schema_version=SCHEMA_VERSION,
            population=incoming.population,
            latest=incoming,
            publications=publications,
        )
        publications, removed_paths = _retention_plan(population_dir, updated, keep_reports)
        updated = Manifest(
            schema_version=SCHEMA_VERSION,
            population=incoming.population,
            latest=incoming,
            publications=publications,
        )
        pops = present_populations(site_dir)
        if incoming.population not in pops:
            pops = [p for p in SUPPORTED_POPULATIONS if p in pops or p == incoming.population]
        # The manifest, the population index and the root index describe one
        # state: they land together or not at all.
        _commit_files(
            {
                mpath: manifest_json(updated),
                population_dir / "index.html": _population_index_html(incoming.report_path),
                site_dir / "index.html": build_root_index(pops),
            }
        )
        # Retention runs after the state is committed: a report directory
        # that is left behind is a leftover, not an inconsistent state.
        removed = _remove_reports(population_dir, removed_paths)
        validate_population_state(population_dir, incoming.population)
        return {"action": action, "reason": reason, "pruned": removed}

    if manifest.latest is None:
        raise PublishError(
            f"{action}: {reason}, but no existing latest publication is recorded to preserve"
        )
    reconcile_population(site_dir, incoming.population)
    validate_population_state(population_dir, incoming.population)
    return {"action": action, "reason": reason, "pruned": []}


def _reset_empty_state(directory: Path) -> None:
    """Make a directory an empty publication tree."""
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "last-history").mkdir(parents=True, exist_ok=True)


def _promote(staging: Path, destination: Path) -> None:
    """Replace the destination with the staged tree.

    The rename is atomic within a filesystem, so the destination is either
    the old tree or the new one, never a mixture of the two.
    """
    backup = destination.with_name(destination.name + ".previous")
    if backup.exists():
        shutil.rmtree(backup)
    had_destination = destination.exists()
    if had_destination:
        destination.rename(backup)
    try:
        staging.rename(destination)
    except OSError:
        if had_destination:
            backup.rename(destination)
        raise
    if had_destination:
        shutil.rmtree(backup, ignore_errors=True)


def _safe_member_path(name: str) -> None:
    """Refuse a tarball member whose path leaves the tree it is unpacked into."""
    if name.startswith("/") or "\\" in name or re.match(r"^[A-Za-z]:", name):
        raise PublishError(f"tarball contains an unsafe path: {name!r}")
    parts = PurePosixPath(name).parts
    if not parts or any(part in ("..", "") for part in parts):
        raise PublishError(f"tarball contains an unsafe path: {name!r}")


def _extract_tarball(tarball_path: Path, destination: Path) -> None:
    """Unpack a gh-pages tarball into an empty directory, or refuse it.

    Only regular files and directories are unpacked. A tarball that holds a
    link or a device is refused: a link is a way out of the tree, and the
    history of this branch is a tree of reports, not a tree of links.
    """
    with tarfile.open(tarball_path, "r:gz") as tar:
        members = tar.getmembers()
        if not members:
            raise PublishError("empty tarball")
        if len(members) > MAX_TAR_MEMBERS:
            raise PublishError(
                f"tarball holds {len(members)} members, more than the {MAX_TAR_MEMBERS} allowed"
            )
        total = sum(member.size for member in members if member.isfile())
        if total > MAX_TAR_BYTES:
            raise PublishError(
                f"tarball holds {total} bytes, more than the {MAX_TAR_BYTES} allowed"
            )
        top_levels = {member.name.split("/", 1)[0] for member in members if member.name}
        if len(top_levels) != 1:
            raise PublishError("unexpected tarball layout")
        prefix = next(iter(top_levels)) + "/"
        for member in members:
            if not (member.isfile() or member.isdir()):
                raise PublishError(f"tarball contains a link or a device: {member.name}")
            if not member.name.startswith(prefix):
                continue
            relative = member.name[len(prefix):]
            if not relative:
                continue
            _safe_member_path(relative)
        for member in members:
            if not member.name.startswith(prefix):
                continue
            relative = member.name[len(prefix):]
            if not relative:
                continue
            member.name = relative
            tar.extract(member, destination, filter="data")


def bootstrap_history_from_tarball(
    gh_pages_dir: Path,
    tarball_path: Path | None,
    http_status: str | int | None,
) -> dict[str, Any]:
    """Prepare the gh-pages tree from the fetched history tarball.

    - 200 + valid tarball: unpack into a staging directory, then promote it
      over the destination. The destination keeps its old tree until the
      new one is complete.
    - 404: the branch does not exist yet — the first publication starts
      from an empty tree.
    - any other failure: the destination is left as it is and the caller
      is told the history is not usable. The helper does not destroy a
      tree it has no replacement for.
    """
    status = str(http_status) if http_status is not None else "missing"

    if status == "404":
        _reset_empty_state(gh_pages_dir)
        return {"status": "missing-branch", "http_status": 404, "extracted": False}

    if status != "200":
        return {"status": "download-failed", "http_status": status, "extracted": False}

    if tarball_path is None or not Path(tarball_path).exists():
        return {"status": "missing-tarball", "http_status": 200, "extracted": False}

    gh_pages_dir.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="allure-history-", dir=str(gh_pages_dir.parent)))
    try:
        _extract_tarball(Path(tarball_path), staging)
        (staging / "last-history").mkdir(parents=True, exist_ok=True)
    except Exception as exc:
        shutil.rmtree(staging, ignore_errors=True)
        return {"status": "malformed-tarball", "http_status": 200, "extracted": False, "error": str(exc)}

    _promote(staging, gh_pages_dir)
    return {"status": "ok", "http_status": 200, "extracted": True}


def tree_stats(path: Path) -> dict[str, int]:
    files = 0
    size = 0
    if not path.exists():
        return {"files": 0, "bytes": 0}
    for p in path.rglob("*"):
        try:
            if p.is_file() and not p.is_symlink():
                files += 1
                size += p.stat().st_size
        except OSError:
            pass
    return {"files": files, "bytes": size}


# ── CLI ───────────────────────────────────────────────────────────────────────


def _publication_from_args(args: argparse.Namespace) -> Publication:
    """Build a publication from CLI values, without silent coercion."""
    try:
        return Publication(
            run_id=_cli_int(args.run_id, "run-id"),
            run_number=_cli_int(args.run_number, "run-number"),
            run_attempt=_cli_int(args.run_attempt, "run-attempt"),
            event=args.event,
            sha=args.sha,
            population=args.population,
            report_path=args.report_path,
        )
    except ValidationError as exc:
        raise _model_error("publication", exc) from exc


def cmd_validate_input(args: argparse.Namespace) -> int:
    validate_pages_input(Path(args.results_dir))
    print(f"pages input OK: {args.results_dir}")
    return 0


def cmd_bootstrap_history(args: argparse.Namespace) -> int:
    tarball = Path(args.tarball) if args.tarball else None
    result = bootstrap_history_from_tarball(Path(args.gh_pages_dir), tarball, args.http_status)
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] in {"ok", "missing-branch"} else 1


def cmd_prepare_namespace(args: argparse.Namespace) -> int:
    result = prepare_namespace(Path(args.gh_pages_dir), args.population)
    print(json.dumps(result, sort_keys=True))
    return 0


def cmd_decide(args: argparse.Namespace) -> int:
    incoming = _publication_from_args(args)
    if args.results_dir:
        validate_pages_input(Path(args.results_dir))
    population_dir = Path(args.site_dir) / incoming.population
    manifest = load_manifest(manifest_path(population_dir), incoming.population)
    action, reason = decide(manifest, incoming)
    print(
        json.dumps(
            {
                "action": action,
                "reason": reason,
                "identity": incoming.identity,
                "population": incoming.population,
            },
            sort_keys=True,
        )
    )
    return 0


def cmd_apply(args: argparse.Namespace) -> int:
    incoming = _publication_from_args(args)
    result = apply_publication(Path(args.site_dir), incoming, _cli_int(args.keep_reports, "keep-reports"))
    print(
        json.dumps(
            {
                "action": result["action"],
                "reason": result["reason"],
                "pruned": result.get("pruned", []),
            },
            sort_keys=True,
        )
    )
    return 0


def cmd_reconcile(args: argparse.Namespace) -> int:
    _population(args.population)
    reconcile_population(Path(args.site_dir), args.population)
    print(f"reconciled: {args.population}")
    return 0


def cmd_validate_state(args: argparse.Namespace) -> int:
    site_dir = Path(args.site_dir)
    populations = list(args.population) if args.population else present_populations(site_dir)
    if not populations:
        raise PublishError("no published populations found to validate")
    validate_publish_tree(site_dir, populations)
    print(f"publication state OK: {site_dir}")
    return 0


def cmd_prune(args: argparse.Namespace) -> int:
    _population(args.population)
    population_dir = Path(args.site_dir) / args.population
    if not population_dir.is_dir():
        raise PublishError(f"population namespace does not exist: {args.population}")
    result = prune_population(population_dir, args.population, _cli_int(args.keep_reports, "keep-reports"))
    print(json.dumps(result, sort_keys=True))
    return 0


def cmd_measure(args: argparse.Namespace) -> int:
    stats = tree_stats(Path(args.path))
    print(json.dumps(stats, sort_keys=True))
    return 0


def cmd_write_root_index(args: argparse.Namespace) -> int:
    site_dir = Path(args.site_dir)
    populations = list(args.population) if args.population else present_populations(site_dir)
    write_root_index(site_dir, populations or list(SUPPORTED_POPULATIONS))
    print(f"root index written: {site_dir / 'index.html'}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    def add_publication_args(cmd: argparse.ArgumentParser) -> None:
        cmd.add_argument("--run-id", required=True)
        cmd.add_argument("--run-number", required=True)
        cmd.add_argument("--run-attempt", required=True)
        cmd.add_argument("--event", required=True)
        cmd.add_argument("--sha", required=True)
        cmd.add_argument("--population", required=True)
        cmd.add_argument("--report-path", required=True)

    p_vi = sub.add_parser("validate-input", help="validate merged Allure Pages input artifact")
    p_vi.add_argument("--results-dir", required=True)
    p_vi.set_defaults(func=cmd_validate_input)

    p_bh = sub.add_parser("bootstrap-history", help="extract gh-pages tarball or initialize clean history state")
    p_bh.add_argument("--gh-pages-dir", required=True)
    p_bh.add_argument("--tarball")
    p_bh.add_argument("--http-status")
    p_bh.set_defaults(func=cmd_bootstrap_history)

    p_pn = sub.add_parser("prepare-namespace", help="prepare per-population namespace with isolated history")
    p_pn.add_argument("--gh-pages-dir", required=True)
    p_pn.add_argument("--population", required=True)
    p_pn.set_defaults(func=cmd_prepare_namespace)

    p_decide = sub.add_parser("decide", help="decide whether incoming publication may become latest for its population")
    p_decide.add_argument("--site-dir", required=True)
    p_decide.add_argument("--results-dir")
    add_publication_args(p_decide)
    p_decide.set_defaults(func=cmd_decide)

    p_apply = sub.add_parser("apply", help="apply monotonic/idempotent publication state for a population")
    p_apply.add_argument("--site-dir", required=True)
    p_apply.add_argument("--keep-reports", default=str(REPORTS_PER_POPULATION))
    add_publication_args(p_apply)
    p_apply.set_defaults(func=cmd_apply)

    p_reconcile = sub.add_parser("reconcile", help="reconcile indexes to recorded latest for a population")
    p_reconcile.add_argument("--site-dir", required=True)
    p_reconcile.add_argument("--population", required=True)
    p_reconcile.set_defaults(func=cmd_reconcile)

    p_validate = sub.add_parser("validate-state", help="validate publish tree, manifests and latest/index consistency")
    p_validate.add_argument("--site-dir", required=True)
    p_validate.add_argument("--population", action="append")
    p_validate.set_defaults(func=cmd_validate_state)

    p_prune = sub.add_parser("prune", help="prune old per-population reports while keeping latest/required history")
    p_prune.add_argument("--site-dir", required=True)
    p_prune.add_argument("--population", required=True)
    p_prune.add_argument("--keep-reports", default=str(REPORTS_PER_POPULATION))
    p_prune.set_defaults(func=cmd_prune)

    p_measure = sub.add_parser("measure", help="report file count and byte size of a directory tree")
    p_measure.add_argument("--path", required=True)
    p_measure.set_defaults(func=cmd_measure)

    p_root = sub.add_parser("write-root-index", help="write root navigation index linking populations")
    p_root.add_argument("--site-dir", required=True)
    p_root.add_argument("--population", action="append")
    p_root.set_defaults(func=cmd_write_root_index)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except PublishError as exc:
        print(f"allure-pages publication error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
